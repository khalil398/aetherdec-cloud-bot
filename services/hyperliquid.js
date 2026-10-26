const axios = require("axios");
const { ethers } = require("ethers");
const msgpack = require("msgpack-lite");

const HYPERLIQUID_API_URL = process.env.HYPERLIQUID_API_URL || "https://api.hyperliquid.xyz";

/**
 * Fetches Hyperliquid L1 Clearinghouse state for a user.
 */
async function fetchUserClearinghouseState(userAddress) {
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "clearinghouseState",
      user: userAddress
    }, { timeout: 10000 });
    return response.data;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on clearinghouseState for ${userAddress}`);
    } else {
      console.error(`[Hyperliquid] Error fetching clearinghouseState for ${userAddress}:`, error.message);
    }
    return null;
  }
}

/**
 * Fetches spot clearinghouse balances for a user.
 */
async function fetchSpotClearinghouseState(userAddress) {
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "spotClearinghouseState",
      user: userAddress
    }, { timeout: 10000 });
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
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "allMids"
    }, { timeout: 10000 });
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
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "spotMetaAndAssetCtxs"
    }, { timeout: 10000 });
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


/**
 * Fetches OHLCV candle snapshot from Hyperliquid API.
 */
async function fetchCandleSnapshot(coin = "BTC", interval = "5m", count = 300) {
  try {
    const now = Date.now();
    const intervalMinutes = interval === "1h" ? 60 :
                            interval === "30m" ? 30 :
                            interval === "15m" ? 15 :
                            interval === "1m" ? 1 :
                            interval === "4h" ? 240 :
                            interval === "1d" ? 1440 : 5;
    const startTime = now - (count * intervalMinutes * 60 * 1000);
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "candleSnapshot",
      req: {
        coin,
        interval,
        startTime,
        endTime: now
      }
    }, { timeout: 10000 });
    return response.data || [];
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on candleSnapshot for ${coin} (${interval}). Triggering 15s pause.`);
      return { rateLimited: true, candles: [] };
    } else {
      console.error(`[Hyperliquid] Error fetching candleSnapshot for ${coin} (${interval}):`, error.message);
    }
    return [];
  }
}


/**
 * Places an L1 Order signed using the delegated Agent Key.
 */
async function placeAgentOrder({ agentPrivateKey, masterAddress, assetIndex, isBuy, limitPx, sz, orderType = { limit: { tif: "Gtc" } }, reduceOnly = false }) {
  try {
    const nonce = Date.now();
    const orderWire = {
      a: assetIndex,
      b: isBuy,
      p: limitPx.toString(),
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
        ["bytes32", "bytes32", "bytes32", "uint256", "bytes32"],
        [
          ethers.id("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
          ethers.id("Exchange"),
          ethers.id("1"),
          1337,
          "0x0000000000000000000000000000000000000000"
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
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "metaAndAssetCtxs"
    }, { timeout: 10000 });
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
        ["bytes32", "bytes32", "bytes32", "uint256", "bytes32"],
        [
          ethers.id("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
          ethers.id("Exchange"),
          ethers.id("1"),
          1337,
          "0x0000000000000000000000000000000000000000"
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

    console.log(`[Hyperliquid] Agent updateLeverage for asset ${assetIndex} (isCross=${isCross}, leverage=${leverage}):`, response.data);
    return response.data;
  } catch (error) {
    console.error(`[Hyperliquid] Agent updateLeverage failed for asset ${assetIndex}:`, error.response?.data || error.message);
    throw error;
  }
}

const userAbstractionCache = new Map();

/**
 * Checks whether user has Unified Account or Portfolio Margin active (cached 5 minutes).
 */
async function checkUserAbstraction(userAddress) {
  const cached = userAbstractionCache.get(userAddress);
  if (cached && (Date.now() - cached.timestamp < 300000)) {
    return cached.val;
  }
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "userAbstraction",
      user: userAddress
    }, { timeout: 10000 });
    const val = response.data || "disabled";
    userAbstractionCache.set(userAddress, { val, timestamp: Date.now() });
    return val;
  } catch (error) {
    if (error.response?.status === 429) {
      console.warn(`[Hyperliquid] ⚠️ Rate limit (429) on userAbstraction for ${userAddress}`);
    } else {
      console.error(`[Hyperliquid] Error checking userAbstraction for ${userAddress}:`, error.message);
    }
    return cached ? cached.val : "disabled";
  }
}

module.exports = {
  fetchUserClearinghouseState,
  fetchSpotClearinghouseState,
  fetchAllMids,
  fetchSpotMetaAndCtxs,
  fetchPerpMetaAndCtxs,
  fetchCandleSnapshot,
  placeAgentOrder,
  updateAgentLeverage,
  checkUserAbstraction
};



