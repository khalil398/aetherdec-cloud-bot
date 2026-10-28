const axios = require("axios");
const { ethers } = require("ethers");
const msgpack = require("msgpack-lite");

const HYPERLIQUID_API_URL = process.env.HYPERLIQUID_API_URL || "https://api.hyperliquid.xyz";

// ============================================================================
// CENTRALIZED RATE LIMITER & SEQUENTIAL FIFO REQUEST QUEUE
// ============================================================================

let infoQueueTail = Promise.resolve();
let lastInfoRequestTime = 0;
const MIN_INFO_INTERVAL_MS = 1000; // Guarantee >= 1000ms spacing between any two /info calls
let global429BackoffUntil = 0;

/**
 * Checks whether global 429 rate limit backoff is currently active.
 */
function isGlobal429Active() {
  return Date.now() < global429BackoffUntil;
}

/**
 * Serializes and paces all POST requests to /info with a minimum delay between consecutive calls.
 * If 429 occurs, activates a 15-second backoff.
 */
function postInfoWithThrottle(payload, timeout = 10000) {
  const task = async () => {
    const now = Date.now();
    if (global429BackoffUntil > now) {
      const waitMs = global429BackoffUntil - now;
      console.warn(`[Hyperliquid] ⏳ In global 429 cooldown. Pacing ${(waitMs / 1000).toFixed(1)}s before /info call (${payload?.type || "info"})...`);
      await new Promise(r => setTimeout(r, waitMs));
    }

    const elapsed = Date.now() - lastInfoRequestTime;
    if (elapsed < MIN_INFO_INTERVAL_MS) {
      await new Promise(r => setTimeout(r, MIN_INFO_INTERVAL_MS - elapsed));
    }

    try {
      const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, payload, { timeout });
      lastInfoRequestTime = Date.now();
      return response;
    } catch (err) {
      lastInfoRequestTime = Date.now();
      if (err.response?.status === 429) {
        global429BackoffUntil = Date.now() + 15000;
        console.warn(`[Hyperliquid] ⚠️ HTTP 429 detected on type: "${payload?.type || 'info'}". Activated 15s global /info cooldown.`);
      }
      throw err;
    }
  };

  const nextPromise = infoQueueTail.then(task, task);
  infoQueueTail = nextPromise.catch(() => {});
  return nextPromise;
}

const clearinghouseCache = new Map();

/**
 * Invalidates the cached clearinghouse state for a user.
 */
function invalidateClearinghouseCache(userAddress) {
  if (!userAddress) {
    clearinghouseCache.clear();
  } else {
    clearinghouseCache.delete(userAddress.toLowerCase());
  }
}

/**
 * Fetches Hyperliquid L1 Clearinghouse state for a user.
 * Adaptive TTL:
 * - 30 seconds if user has 0 active positions (idle state)
 * - 12 seconds if user has active positions
 * Gracefully falls back to stale cache on 429 or network errors.
 */
async function fetchUserClearinghouseState(userAddress) {
  if (!userAddress) return null;
  const key = userAddress.toLowerCase();
  const now = Date.now();
  const cached = clearinghouseCache.get(key);

  if (cached && cached.data) {
    const hasPositions = (cached.data.assetPositions || []).length > 0;
    const ttl = hasPositions ? 12000 : 30000;
    if (now - cached.time < ttl) {
      return cached.data;
    }
    // If in 429 cooldown and we have a cached state, return it immediately rather than queuing
    if (isGlobal429Active()) {
      return cached.data;
    }
  }

  try {
    const response = await postInfoWithThrottle({
      type: "clearinghouseState",
      user: userAddress
    }, 10000);

    if (response.data) {
      clearinghouseCache.set(key, { data: response.data, time: Date.now() });
      return response.data;
    }
    return cached ? cached.data : null;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on clearinghouseState for ${userAddress}. Serving cached snapshot.`);
    } else {
      console.error(`[Hyperliquid] Error fetching clearinghouseState for ${userAddress}:`, error.message);
    }
    return cached ? cached.data : null;
  }
}

/**
 * Fetches spot clearinghouse balances for a user.
 */
async function fetchSpotClearinghouseState(userAddress) {
  try {
    const response = await postInfoWithThrottle({
      type: "spotClearinghouseState",
      user: userAddress
    }, 10000);
    return response.data;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on spotClearinghouseState for ${userAddress}`);
    } else {
      console.error(`[Hyperliquid] Error fetching spotClearinghouseState for ${userAddress}:`, error.message);
    }
    return null;
  }
}

/**
 * Fetches latest mid market prices for all assets.
 */
async function fetchAllMids() {
  try {
    const response = await postInfoWithThrottle({
      type: "allMids"
    }, 10000);
    return response.data;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn("[Hyperliquid] ⚠️ Rate limit (429) on allMids");
    } else {
      console.error("[Hyperliquid] Error fetching allMids:", error.message);
    }
    return {};
  }
}

let cachedSpotMarkets = null;
let lastSpotMarketsFetch = 0;

/**
 * Fetches all dynamic spot market metadata from Hyperliquid API (cached for 2 minutes).
 */
async function fetchSpotMetaAndCtxs() {
  const now = Date.now();
  if (cachedSpotMarkets && (now - lastSpotMarketsFetch < 120000)) {
    return cachedSpotMarkets;
  }
  if (isGlobal429Active() && cachedSpotMarkets) {
    return cachedSpotMarkets;
  }
  try {
    const response = await postInfoWithThrottle({
      type: "spotMetaAndAssetCtxs"
    }, 10000);
    const rootData = response.data;
    const spotMarkets = [];

    if (Array.isArray(rootData) && rootData.length >= 2) {
      const meta = rootData[0];
      const ctxs = rootData[1];
      const tokens = meta.tokens || [];
      const universe = meta.universe || [];

      const tokenMap = new Map();
      tokens.forEach((tok, i) => {
        const idx = tok.index !== undefined ? tok.index : i;
        tokenMap.set(idx, {
          name: tok.name || "",
          szDecimals: tok.szDecimals !== undefined ? tok.szDecimals : 2,
          weiDecimals: tok.weiDecimals !== undefined ? tok.weiDecimals : 8
        });
      });

      universe.forEach((u, i) => {
        const idx = u.index !== undefined ? u.index : i;
        const tokenPair = u.tokens || [];
        const t0Obj = tokenMap.get(tokenPair[0]);
        const t1Obj = tokenMap.get(tokenPair[1]);
        const t0Name = t0Obj ? t0Obj.name : "";
        const t1Name = t1Obj ? t1Obj.name : "USDC";
        const szDecimals = t0Obj && t0Obj.szDecimals !== undefined ? t0Obj.szDecimals : 2;

        const rawName = u.name || "";
        const displayName = (rawName.startsWith("@") || !rawName)
          ? (t0Name ? `${t0Name}/${t1Name}` : `SPOT-${idx}`)
          : rawName;

        const baseCoin = t0Name || displayName.split("/")[0];

        if (baseCoin && !baseCoin.startsWith("@") && !baseCoin.startsWith("SPOT-")) {
          const markPx = ctxs[idx] ? parseFloat(ctxs[idx].markPx || 0) : 0;
          const exchangeSymbol = (idx === 0 || baseCoin === "PURR")
            ? "PURR"
            : (rawName.startsWith("@") ? rawName : `@${idx}`);

          spotMarkets.push({
            universeIndex: idx,
            l1AssetIndex: 10000 + idx,
            displayName,
            coin: baseCoin,
            exchangeCoin: exchangeSymbol,
            quote: t1Name,
            markPrice: markPx,
            szDecimals: szDecimals
          });
        }
      });
    }
    cachedSpotMarkets = spotMarkets;
    lastSpotMarketsFetch = now;
    return spotMarkets;
  } catch (error) {
    if (cachedSpotMarkets) return cachedSpotMarkets;
    console.error("[Hyperliquid] Error fetching spotMetaAndAssetCtxs:", error.message);
    return [];
  }
}

const candleCache = new Map();

function getIntervalMs(interval) {
  switch (interval) {
    case "1m": return 60 * 1000;
    case "3m": return 3 * 60 * 1000;
    case "5m": return 5 * 60 * 1000;
    case "15m": return 15 * 60 * 1000;
    case "30m": return 30 * 60 * 1000;
    case "1h": return 60 * 60 * 1000;
    case "2h": return 2 * 60 * 60 * 1000;
    case "4h": return 4 * 60 * 60 * 1000;
    case "8h": return 8 * 60 * 60 * 1000;
    case "1d": return 24 * 60 * 60 * 1000;
    default: return 5 * 60 * 1000;
  }
}

/**
 * Fetches OHLCV candle snapshot from Hyperliquid API with candle-boundary caching.
 * - Same candle interval: cached for up to 20 seconds
 * - Candle boundary crossed: immediately refreshes to capture closed bars
 * - Fallback on 429: Returns cached candles to ensure zero signal drops
 */
async function fetchCandleSnapshot(coin = "BTC", interval = "5m", count = 300) {
  const cacheKey = `${coin.toUpperCase()}_${interval}_${count}`;
  const now = Date.now();
  const intervalMs = getIntervalMs(interval);
  const currentBoundary = Math.floor(now / intervalMs) * intervalMs;
  const cached = candleCache.get(cacheKey);

  if (cached && cached.data && cached.data.length > 0) {
    const isSameCandle = cached.boundary === currentBoundary;
    const isWithinIntraBarTtl = (now - cached.timestamp) < 20000;

    if (isSameCandle && isWithinIntraBarTtl) {
      return cached.data;
    }

    if (isGlobal429Active()) {
      console.warn(`[Hyperliquid] In 429 cooldown. Serving cached candle snapshot for ${coin} (${interval}).`);
      return cached.data;
    }
  }

  try {
    const startTime = now - (count * intervalMs);
    const response = await postInfoWithThrottle({
      type: "candleSnapshot",
      req: {
        coin,
        interval,
        startTime,
        endTime: now
      }
    }, 10000);

    const candles = response.data || [];
    if (Array.isArray(candles) && candles.length > 0) {
      candleCache.set(cacheKey, {
        data: candles,
        timestamp: Date.now(),
        boundary: currentBoundary
      });
      return candles;
    }
    return cached ? cached.data : candles;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on candleSnapshot for ${coin} (${interval}). Serving cached snapshot.`);
      if (cached && cached.data && cached.data.length > 0) {
        return cached.data;
      }
      return { rateLimited: true, candles: [] };
    } else {
      console.error(`[Hyperliquid] Error fetching candleSnapshot for ${coin} (${interval}):`, error.message);
    }
    return cached ? cached.data : [];
  }
}


/**
 * Formats limit price to comply strictly with Hyperliquid L1 tick size rules:
 * 1. Whole number (integer) rule: For prices >= 10,000 (e.g. BTC), integer rounding is strictly enforced.
 * 2. Maximum of 5 significant figures.
 * 3. Decimal place cap: MAX_DECIMALS - szDecimals (6 for perps, 8 for spot).
 * 4. Strips trailing zeros to prevent serialization/hash discrepancies.
 */
function formatPriceForHyperliquid(price, szDecimals = null, isSpot = false) {
  if (!price || price <= 0) return "0";
  const num = Number(price);
  if (!Number.isFinite(num)) return "0";

  // 1. Whole number (integer) rule:
  // Any number >= 10,000 has 5 or more integer digits.
  // Any decimal added would exceed 5 significant figures.
  // Hyperliquid permits integers of any size, and ticks for >= 10,000 assets (e.g. BTC) are integer.
  if (Math.abs(num) >= 10000) {
    return Math.round(num).toString();
  }

  // 2. Max decimals allowed by exchange metadata:
  const metaMaxDec = (szDecimals !== null && szDecimals !== undefined)
    ? Math.max(0, (isSpot ? 8 : 6) - Number(szDecimals))
    : (isSpot ? 8 : 6);

  // 3. Significant figures precision constraint (max 5 significant figures):
  let sigFigMaxDec = 5;
  if (num >= 1000) sigFigMaxDec = 1;
  else if (num >= 100) sigFigMaxDec = 2;
  else if (num >= 10) sigFigMaxDec = 3;
  else if (num >= 1) sigFigMaxDec = 4;
  else {
    const leadingZeros = Math.max(0, -Math.floor(Math.log10(num)) - 1);
    sigFigMaxDec = leadingZeros + 5;
  }

  const effectiveDecimals = Math.min(metaMaxDec, sigFigMaxDec);

  const factor = Math.pow(10, effectiveDecimals);
  const rounded = Math.round(num * factor) / factor;

  let str = rounded.toFixed(effectiveDecimals);
  if (str.includes(".")) {
    str = str.replace(/\.?0+$/, "");
  }
  return str || "0";
}

/**
 * Places an L1 Order signed using the delegated Agent Key.
 */
async function placeAgentOrder({ agentPrivateKey, masterAddress, assetIndex, isBuy, limitPx, sz, orderType = { limit: { tif: "Gtc" } }, reduceOnly = false }) {
  try {
    const nonce = Date.now();
    // Defensive tick size compliance guard: Ensure limitPx conforms to Hyperliquid tick rules
    const formattedLimitPx = formatPriceForHyperliquid(limitPx);
    const orderWire = {
      a: assetIndex,
      b: isBuy,
      p: formattedLimitPx,
      s: sz.toString(),
      r: reduceOnly,
      t: orderType
    };

    const action = {
      type: "order",
      orders: [orderWire],
      grouping: "na"
    };

    // Serialize action via msgpack
    const actionBytes = msgpack.encode(action);
    
    // Hash connectionId = actionBytes + nonceBytes + vaultMarker(0x00)
    const nonceBuffer = Buffer.alloc(8);
    nonceBuffer.writeBigUInt64BE(BigInt(nonce));
    const vaultMarker = Buffer.from([0x00]);
    const connectionIdBytes = Buffer.concat([actionBytes, nonceBuffer, vaultMarker]);
    const connectionId = ethers.keccak256(connectionIdBytes);

    // EIP-712 Agent digest computation
    const domainSeparator = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32", "uint256", "address"],
        [
          ethers.id("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
          ethers.id("Exchange"),
          ethers.id("1"),
          1337,
          ethers.ZeroAddress
        ]
      )
    );

    const agentStructHash = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32"],
        [
          ethers.id("Agent(string source,bytes32 connectionId)"),
          ethers.id("a"),
          connectionId
        ]
      )
    );

    const digest = ethers.keccak256(
      Buffer.concat([
        Buffer.from("1901", "hex"),
        Buffer.from(domainSeparator.slice(2), "hex"),
        Buffer.from(agentStructHash.slice(2), "hex")
      ])
    );

    // Sign digest using Agent Private Key
    const wallet = new ethers.Wallet(agentPrivateKey);
    const signature = wallet.signingKey.sign(digest);

    const payload = {
      action,
      nonce,
      signature: {
        r: signature.r,
        s: signature.s,
        v: signature.v
      },
      vaultAddress: null
    };

    const response = await axios.post(`${HYPERLIQUID_API_URL}/exchange`, payload, {
      headers: { "Content-Type": "application/json" },
      timeout: 10000
    });

    if (response.data && response.data.status === "err") {
      const errMsg = typeof response.data.response === "string" ? response.data.response : JSON.stringify(response.data.response);
      console.error("[Hyperliquid] Agent order placement rejected by exchange:", errMsg);
      throw new Error(errMsg);
    }

    // Invalidate clearinghouse cache immediately so fresh balances/positions reflect on next tick
    invalidateClearinghouseCache(masterAddress);

    console.log(`[Hyperliquid] Agent order placed for asset ${assetIndex}:`, response.data);
    return response.data;
  } catch (error) {
    console.error("[Hyperliquid] Agent order placement failed:", error.response?.data || error.message);
    throw error;
  }
}

let cachedPerpMarkets = null;
let lastPerpMarketsFetch = 0;

/**
 * Fetches Perpetual Futures universe and context metadata (cached for 2 minutes).
 */
async function fetchPerpMetaAndCtxs() {
  const now = Date.now();
  if (cachedPerpMarkets && (now - lastPerpMarketsFetch < 120000)) {
    return cachedPerpMarkets;
  }
  if (isGlobal429Active() && cachedPerpMarkets) {
    return cachedPerpMarkets;
  }
  try {
    const response = await postInfoWithThrottle({
      type: "metaAndAssetCtxs"
    }, 10000);
    const rootData = response.data;
    const perpMarkets = [];
    if (Array.isArray(rootData) && rootData.length >= 2) {
      const universe = rootData[0].universe || [];
      const ctxs = rootData[1] || [];
      universe.forEach((u, idx) => {
        const markPx = ctxs[idx] ? parseFloat(ctxs[idx].markPx || 0) : 0;
        perpMarkets.push({
          assetIndex: idx,
          coin: u.name,
          displayName: `${u.name}-PERP`,
          szDecimals: u.szDecimals !== undefined ? u.szDecimals : 2,
          maxLeverage: u.maxLeverage !== undefined ? u.maxLeverage : 20,
          onlyIsolated: u.onlyIsolated || false,
          markPrice: markPx
        });
      });
    }
    cachedPerpMarkets = perpMarkets;
    lastPerpMarketsFetch = now;
    return perpMarkets;
  } catch (error) {
    if (cachedPerpMarkets) return cachedPerpMarkets;
    console.error("[Hyperliquid] Error fetching perp metaAndAssetCtxs:", error.message);
    return [];
  }
}

/**
 * Updates leverage and margin mode (isolated vs cross) signed using the delegated Agent Key.
 */
async function updateAgentLeverage({ agentPrivateKey, masterAddress, assetIndex, isCross, leverage }) {
  try {
    const nonce = Date.now();
    const action = {
      type: "updateLeverage",
      asset: assetIndex,
      isCross: isCross,
      leverage: parseInt(leverage)
    };

    const actionBytes = msgpack.encode(action);
    const nonceBuffer = Buffer.alloc(8);
    nonceBuffer.writeBigUInt64BE(BigInt(nonce));
    const vaultMarker = Buffer.from([0x00]);
    const connectionIdBytes = Buffer.concat([actionBytes, nonceBuffer, vaultMarker]);
    const connectionId = ethers.keccak256(connectionIdBytes);

    const domainSeparator = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32", "uint256", "address"],
        [
          ethers.id("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
          ethers.id("Exchange"),
          ethers.id("1"),
          1337,
          ethers.ZeroAddress
        ]
      )
    );

    const agentStructHash = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32"],
        [
          ethers.id("Agent(string source,bytes32 connectionId)"),
          ethers.id("a"),
          connectionId
        ]
      )
    );

    const digest = ethers.keccak256(
      Buffer.concat([
        Buffer.from("1901", "hex"),
        Buffer.from(domainSeparator.slice(2), "hex"),
        Buffer.from(agentStructHash.slice(2), "hex")
      ])
    );

    const wallet = new ethers.Wallet(agentPrivateKey);
    const signature = wallet.signingKey.sign(digest);

    const payload = {
      action,
      nonce,
      signature: {
        r: signature.r,
        s: signature.s,
        v: signature.v
      },
      vaultAddress: null
    };

    const response = await axios.post(`${HYPERLIQUID_API_URL}/exchange`, payload, {
      headers: { "Content-Type": "application/json" }
    });

    if (response.data && response.data.status === "err") {
      const errMsg = typeof response.data.response === "string" ? response.data.response : JSON.stringify(response.data.response);
      console.error(`[Hyperliquid] Agent updateLeverage rejected for asset ${assetIndex}:`, errMsg);
      throw new Error(errMsg);
    }

    // Invalidate clearinghouse cache immediately so fresh leverage reflects on next tick
    invalidateClearinghouseCache(masterAddress);

    console.log(`[Hyperliquid] Agent updateLeverage for asset ${assetIndex} (isCross=${isCross}, leverage=${leverage}):`, response.data);
    return response.data;
  } catch (error) {
    console.error(`[Hyperliquid] Agent updateLeverage failed for asset ${assetIndex}:`, error.response?.data || error.message);
    throw error;
  }
}

const userAbstractionCache = new Map();

/**
 * Checks whether user has Unified Account or Portfolio Margin active (cached 1 hour).
 */
async function checkUserAbstraction(userAddress) {
  if (!userAddress) return "disabled";
  const cached = userAbstractionCache.get(userAddress);
  if (cached && (Date.now() - cached.timestamp < 3600000)) {
    return cached.val;
  }
  if (isGlobal429Active() && cached) {
    return cached.val;
  }
  try {
    const response = await postInfoWithThrottle({
      type: "userAbstraction",
      user: userAddress
    }, 10000);
    const val = response.data || "disabled";
    userAbstractionCache.set(userAddress, { val, timestamp: Date.now() });
    return val;
  } catch (error) {
    userAbstractionCache.set(userAddress, { val: "disabled", timestamp: Date.now() - 3000000 });
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on userAbstraction for ${userAddress}. Pausing userAbstraction queries.`);
    } else {
      console.error(`[Hyperliquid] Error checking userAbstraction for ${userAddress}:`, error.message);
    }
    return cached ? cached.val : "disabled";
  }
}

const userFillsCache = new Map();

/**
 * Fetches recent trade execution fills for a user (cached for 15s).
 */
async function fetchUserFills(userAddress) {
  if (!userAddress) return [];
  const key = userAddress.toLowerCase();
  const cached = userFillsCache.get(key);
  if (cached && (Date.now() - cached.timestamp < 15000)) {
    return cached.data;
  }
  if (isGlobal429Active() && cached) {
    return cached.data;
  }
  try {
    const response = await postInfoWithThrottle({
      type: "userFills",
      user: userAddress
    }, 10000);
    const fills = Array.isArray(response.data) ? response.data : [];
    userFillsCache.set(key, { data: fills, timestamp: Date.now() });
    return fills;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on userFills for ${userAddress}. Serving cached fills.`);
    } else {
      console.error(`[Hyperliquid] Error fetching userFills for ${userAddress}:`, error.message);
    }
    return cached ? cached.data : [];
  }
}

module.exports = {
  fetchUserClearinghouseState,
  invalidateClearinghouseCache,
  fetchSpotClearinghouseState,
  fetchAllMids,
  fetchSpotMetaAndCtxs,
  fetchPerpMetaAndCtxs,
  fetchCandleSnapshot,
  placeAgentOrder,
  updateAgentLeverage,
  checkUserAbstraction,
  formatPriceForHyperliquid,
  fetchUserFills
};



