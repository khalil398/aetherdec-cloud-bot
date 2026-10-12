const {
  fetchUserClearinghouseState,
  fetchSpotClearinghouseState,
  fetchAllMids,
  fetchSpotMetaAndCtxs,
  fetchCandleSnapshot,
  placeAgentOrder
} = require("../services/hyperliquid");

/**
 * Calculates Exponential Moving Average (EMA) array for a series of values.
 */
function calculateEMA(values, period) {
  if (!values || values.length < period) return [];
  const k = 2 / (period + 1);
  const emas = new Array(values.length);

  // Initial SMA for first 'period' elements
  let sum = 0;
  for (let i = 0; i < period; i++) {
    sum += values[i];
  }
  let currentEma = sum / period;
  emas[period - 1] = currentEma;

  for (let i = period; i < values.length; i++) {
    currentEma = (values[i] - currentEma) * k + currentEma;
    emas[i] = currentEma;
  }
  return emas;
}

/**
 * Known fallback szDecimals for core Hyperliquid spot tokens.
 */
const FALLBACK_SZ_DECIMALS = {
  BTC: 5,
  ETH: 4,
  SOL: 2,
  HYPE: 2,
  PURR: 0,
  HFUN: 2,
  SUI: 2,
  PEPE: 0,
  DOGE: 1,
  AVAX: 2,
  LINK: 2,
  XRP: 1
};

/**
 * Rounds down size strictly to szDecimals without scientific notation or decimal overflow.
 */
function formatSizeForHyperliquid(size, szDecimals = 2) {
  const dec = Math.max(0, parseInt(szDecimals) || 0);
  const factor = Math.pow(10, dec);
  const floored = Math.floor(Number(size) * factor) / factor;
  return floored.toFixed(dec);
}

/**
 * Formats limit price to comply strictly with Hyperliquid rules:
 * Max 5 significant figures, formatted cleanly.
 */
function formatPriceForHyperliquid(price) {
  if (!price || price <= 0) return "0";
  const num = Number(price);
  if (num >= 100000) return Math.round(num).toString();
  if (num >= 10000) return (Math.round(num * 10) / 10).toFixed(1);
  if (num >= 1000) return (Math.round(num * 10) / 10).toFixed(1);
  if (num >= 100) return (Math.round(num * 100) / 100).toFixed(2);
  if (num >= 10) return (Math.round(num * 100) / 100).toFixed(2);
  if (num >= 1) return (Math.round(num * 1000) / 1000).toFixed(3);
  if (num >= 0.1) return (Math.round(num * 10000) / 10000).toFixed(4);
  if (num >= 0.01) return (Math.round(num * 100000) / 100000).toFixed(5);
  return Number(num.toPrecision(5)).toString();
}

/**
 * Dynamically calculates relative distance between White Channel (EMA 8 High/Low)
 * and Yellow Channel (EMA 34 High/Low) toward a valid ▲ BUY breakout target (0% - 100%).
 * 
 * Rules:
 * - Downtrend / Below Channel: If the White Channel is slanting downwards or below the Yellow Channel,
 *   calculates percentage progress toward crossing above the Yellow Channel. Returns 0% if oversold/downtrending without upward momentum.
 * - Upward Convergence: As the White Channel curls up towards the top boundary of the Yellow Channel,
 *   calculates proximity percentage (10% to 99% remaining to breakout).
 * - Full Breakout: When White Channel (both lines) strictly breaks above Yellow Channel, returns 100%.
 */
function calculateBreakoutProximity(ema8High, ema8Low, ema34High, ema34Low, prevEma8High = 0, prevEma8Low = 0) {
  // Full breakout confirmed: both White channel lines strictly above Yellow channel
  if (ema8Low > ema34High && ema8High > ema34High) {
    return 100;
  }

  const channelHeight = Math.max(ema34High - ema34Low, 1e-8);
  const isSlantingDown = (prevEma8High > 0 && ema8High < prevEma8High && ema8Low < prevEma8Low);

  // Downtrend / completely below the Yellow channel
  if (ema8High <= ema34Low) {
    if (isSlantingDown) {
      // Completely oversold/downtrending without upward momentum
      return 0;
    }
    const distBelow = ema34Low - ema8High;
    if (distBelow >= channelHeight) {
      return 0;
    }
    // Curving upwards towards Yellow channel bottom boundary
    const upwardProgress = ((channelHeight - distBelow) / channelHeight) * 25.0;
    return Math.max(0, Math.min(24, Math.floor(upwardProgress)));
  }

  // White channel interacting with / ascending through Yellow channel:
  const pHigh = Math.max(0, Math.min(1.0, (ema8High - ema34Low) / channelHeight));
  const pLow = Math.max(0, Math.min(1.0, (ema8Low - ema34Low) / channelHeight));

  const proximity = 25.0 + (pHigh * 25.0) + (pLow * 49.0);
  return Math.max(0, Math.min(99, Math.floor(proximity)));
}

/**
 * Evaluates High/Low EMA Channel Strategy on 30-minute timeframe with Smart Multi-Candle Breakout & Exit Logic.
 * 
 * Rules:
 * 1. BUY Signal (▲ BUY):
 *    - Full Channel Breakout Requirement:
 *      Both EMA 8 lines (White Channel: EMA 8 High and EMA 8 Low) MUST be strictly ABOVE the Yellow Channel (EMA 34 High and EMA 34 Low).
 *      Condition: EMA_8_Low > EMA_34_High AND EMA_8_High > EMA_34_High.
 *      If only one white line has crossed while the other is still inside/below the yellow channel, DO NOT buy.
 *    - Trend Filter: Both channels strictly ABOVE EMA 200 & EMA 233.
 *    - Smart Breakout Window (Current Candle or within Last 1-2 Candles):
 *      Executes a BUY if the breakout transition occurred on the current candle (offset 0)
 *      OR within the last 1 to 2 candles (offset 1 or 2).
 * 
 * 2. SELL / Exit Signal (▼ SELL):
 *    - Full Channel Exit Requirement:
 *      Both EMA 8 lines (White Channel) MUST be strictly BELOW the Yellow Channel (EMA 34 High and EMA 34 Low).
 *      Condition: EMA_8_High < EMA_34_Low AND EMA_8_Low < EMA_34_Low.
 *    - Smart Exit Window:
 *      Executes a SELL/CLOSE order if the downward exit transition occurred on the current candle (offset 0)
 *      OR within the last 1 to 2 candles (offset 1 or 2).
 */
async function evaluateEmaRibbonStrategy(coin = "BTC", candles) {
  if (!candles || candles.length < 240) {
    console.warn(`[Strategy] Insufficient 30m candles for ${coin} (${candles?.length || 0}/240 required)`);
    return { isUptrend: false, isBuySignal: false, isSellSignal: false, breakoutCandleOffset: -1, exitCandleOffset: -1, proximityScore: 0, metrics: {} };
  }

  // Parse OHLCV data series
  const closePrices = candles.map(c => parseFloat(c.c));
  const highPrices = candles.map(c => parseFloat(c.h));
  const lowPrices = candles.map(c => parseFloat(c.l));

  // Compute EMAs
  const ema200Series = calculateEMA(closePrices, 200);
  const ema233Series = calculateEMA(closePrices, 233);
  const ema8HighSeries = calculateEMA(highPrices, 8);
  const ema8LowSeries = calculateEMA(lowPrices, 8);
  const ema34HighSeries = calculateEMA(highPrices, 34);
  const ema34LowSeries = calculateEMA(lowPrices, 34);

  const idx = candles.length - 1; // Current bar

  // Helper 1: Full White Channel strictly ABOVE Yellow Channel (both lines)
  const isWhiteAboveYellow = (i) => {
    if (i < 0 || i >= candles.length) return false;
    const e8H = ema8HighSeries[i];
    const e8L = ema8LowSeries[i];
    const e34H = ema34HighSeries[i];
    return (e8L > e34H && e8H > e34H);
  };

  // Helper 2: Full White Channel strictly BELOW Yellow Channel (both lines)
  const isWhiteBelowYellow = (i) => {
    if (i < 0 || i >= candles.length) return false;
    const e8H = ema8HighSeries[i];
    const e8L = ema8LowSeries[i];
    const e34L = ema34LowSeries[i];
    return (e8H < e34L && e8L < e34L);
  };

  // Helper 3: Trend Filter (Both channels strictly above EMA 200 & EMA 233)
  const checkUptrend = (i) => {
    if (i < 0 || i >= candles.length) return false;
    const maxBaseline = Math.max(ema200Series[i], ema233Series[i]);
    return (
      ema8HighSeries[i] > maxBaseline &&
      ema8LowSeries[i] > maxBaseline &&
      ema34HighSeries[i] > maxBaseline &&
      ema34LowSeries[i] > maxBaseline
    );
  };

  const isCurrentAbove = isWhiteAboveYellow(idx);
  const isCurrentBelow = isWhiteBelowYellow(idx);
  const isUptrend = checkUptrend(idx);

  // 1. Smart Breakout Window (Current Candle or within Last 1-2 Candles)
  let breakoutCandleOffset = -1; // 0 = current candle, 1 = 1 candle ago, 2 = 2 candles ago
  if (isCurrentAbove && isUptrend) {
    for (let offset = 0; offset <= 2; offset++) {
      const k = idx - offset;
      if (k >= 1 && isWhiteAboveYellow(k) && !isWhiteAboveYellow(k - 1)) {
        breakoutCandleOffset = offset;
        break;
      }
    }
  }
  const isBuySignal = (breakoutCandleOffset !== -1);

  // 2. Smart Exit Window (Current Candle or within Last 1-2 Candles)
  let exitCandleOffset = -1; // 0 = current candle, 1 = 1 candle ago, 2 = 2 candles ago
  if (isCurrentBelow) {
    for (let offset = 0; offset <= 2; offset++) {
      const k = idx - offset;
      if (k >= 1 && isWhiteBelowYellow(k) && !isWhiteBelowYellow(k - 1)) {
        exitCandleOffset = offset;
        break;
      }
    }
  }
  const isSellSignal = (exitCandleOffset !== -1);

  const ema200 = ema200Series[idx];
  const ema233 = ema233Series[idx];
  const ema8_high = ema8HighSeries[idx];
  const ema8_low = ema8LowSeries[idx];
  const ema34_high = ema34HighSeries[idx];
  const ema34_low = ema34LowSeries[idx];
  const maxBaseline = Math.max(ema200, ema233);

  // Calculate Breakout Proximity (% towards valid ▲ BUY breakout target)
  const prevE8H = idx >= 1 ? ema8HighSeries[idx - 1] : 0;
  const prevE8L = idx >= 1 ? ema8LowSeries[idx - 1] : 0;
  const proximityScore = isBuySignal ? 100 : calculateBreakoutProximity(ema8_high, ema8_low, ema34_high, ema34_low, prevE8H, prevE8L);

  const metrics = {
    ema200,
    ema233,
    ema8_high,
    ema8_low,
    ema34_high,
    ema34_low,
    maxBaseline,
    isUptrend,
    isCurrentAbove,
    isCurrentBelow,
    breakoutCandleOffset,
    exitCandleOffset,
    proximityScore
  };

  return { isUptrend, isBuySignal, isSellSignal, breakoutCandleOffset, exitCandleOffset, proximityScore, metrics };
}

/**
 * Executes a 24/7 algorithmic strategy tick cycle for a specific user.
 */
async function executeBotTick(uid, db, config, botSecret) {
  const { agentPrivateKey, masterAddress } = botSecret;
  if (!masterAddress) {
    console.warn(`[${uid}] Missing masterAddress in botSecret. Skipping tick.`);
    return;
  }

  console.log(`[${uid}] ⚡ Running 30m Multi-Pair EMA Scanner Engine '${config.strategy}' (Allocation: ${config.allocation_pct}%)...`);

  // 1. Fetch Spot Clearinghouse, Dynamic Spot Meta & All Mid Prices
  const spotClearinghouse = await fetchSpotClearinghouseState(masterAddress);
  const perpClearinghouse = await fetchUserClearinghouseState(masterAddress);
  const allMids = await fetchAllMids();
  const spotMarkets = await fetchSpotMetaAndCtxs();

  let totalPnlUsd = 0.0;
  let activeTradesCount = 0;
  let accountValue = 0.0;

  if (perpClearinghouse && perpClearinghouse.marginSummary) {
    accountValue = parseFloat(perpClearinghouse.marginSummary.accountValue || 0);
    const assetPositions = perpClearinghouse.assetPositions || [];
    activeTradesCount = assetPositions.length;

    totalPnlUsd = assetPositions.reduce((sum, item) => {
      const pos = item.position;
      return sum + parseFloat(pos.unrealizedPnl || 0);
    }, 0.0);
  }

  // Map spot balances and asset indices
  const spotBalances = new Map();
  let usdcBalance = 0.0;
  if (spotClearinghouse && spotClearinghouse.balances) {
    spotClearinghouse.balances.forEach(b => {
      const avail = parseFloat(b.total || 0) - parseFloat(b.hold || 0);
      spotBalances.set(b.coin, avail);
      if (b.coin === "USDC") usdcBalance = avail;
    });
  }

  const assetIndexMap = new Map();
  const szDecimalsMap = new Map();
  const spotMarketMap = new Map();
  const FALLBACK_UNIVERSE = ["BTC", "ETH", "SOL", "HYPE", "PURR", "HFUN", "SUI", "PEPE", "DOGE", "AVAX", "LINK", "XRP"];
  
  // Populate fallback asset indices and szDecimals
  Object.entries(FALLBACK_SZ_DECIMALS).forEach(([c, dec]) => szDecimalsMap.set(c, dec));
  assetIndexMap.set("PURR", 10000);
  assetIndexMap.set("HFUN", 10001);
  assetIndexMap.set("HYPE", 10150);
  assetIndexMap.set("UBTC", 10142);
  assetIndexMap.set("BTC", 10142);
  assetIndexMap.set("HPENGU", 10184);
  assetIndexMap.set("UENA", 10206);
  assetIndexMap.set("ENA", 10206);
  assetIndexMap.set("UAVAX", 10306);
  assetIndexMap.set("AVAX", 10306);

  if (spotMarkets && spotMarkets.length > 0) {
    spotMarkets.forEach(m => {
      assetIndexMap.set(m.coin, m.l1AssetIndex);
      spotMarketMap.set(m.coin.toUpperCase(), m);
      if (m.exchangeCoin) {
        spotMarketMap.set(m.exchangeCoin.toUpperCase(), m);
      }
      // Support matching without 'U' prefix (e.g. BTC -> UBTC, ENA -> UENA, AVAX -> UAVAX)
      if (m.coin.startsWith("U")) {
        const stripped = m.coin.substring(1).toUpperCase();
        if (!spotMarketMap.has(stripped)) {
          spotMarketMap.set(stripped, m);
        }
        if (!assetIndexMap.has(stripped)) {
          assetIndexMap.set(stripped, m.l1AssetIndex);
        }
      }
      if (m.szDecimals !== undefined) {
        szDecimalsMap.set(m.coin, m.szDecimals);
      }
    });
  }

  const availableCoins = (spotMarkets && spotMarkets.length > 0)
    ? spotMarkets.map(m => m.coin)
    : FALLBACK_UNIVERSE;

  const targetCoin = config.target_coin || "BTC";
  const scanQueue = Array.from(new Set([targetCoin, ...availableCoins]));

  const pnlPct = accountValue > 0 ? (totalPnlUsd / accountValue) * 100.0 : 0.0;
  const isEmaStrategyActive = (config.strategy === "30m EMA High/Low" || config.strategy === "5m EMA High/Low" || config.strategy === "Trend Following" || !config.strategy);

  if (isEmaStrategyActive) {
    console.log(`[${uid}] 🔍 Scanning ${scanQueue.length} Hyperliquid Spot pairs simultaneously in parallel (30m Timeframe)...`);

    // Simultaneous Parallel 30m Candle Snapshot Queries across all spot pairs
    const scanResults = await Promise.all(
      scanQueue.map(async (coin) => {
        try {
          const market = spotMarketMap.get(coin.toUpperCase());
          const querySymbol = market ? market.exchangeCoin : coin;
          const coinPrice = parseFloat(allMids[querySymbol] || allMids[coin] || (market ? market.markPrice : 0) || 0);
          if (coinPrice <= 0) return null;

          const candles30m = await fetchCandleSnapshot(querySymbol, "30m", 260);
          const signalResult = await evaluateEmaRibbonStrategy(coin, candles30m);
          return { coin, coinPrice, market, ...signalResult };
        } catch (e) {
          return null;
        }
      })
    );

    const validResults = scanResults.filter(Boolean);

    // 1. Process SELL Signals for held token positions
    for (const res of validResults) {
      const { coin, coinPrice, isSellSignal, exitCandleOffset, market } = res;
      const heldBalance = spotBalances.get(coin) || 
        spotBalances.get(`U${coin}`) || 
        (coin.startsWith("U") ? spotBalances.get(coin.substring(1)) : 0) || 
        0.0;

      if (isSellSignal && heldBalance > 0.0001 && config.active) {
        const offsetDesc = exitCandleOffset === 0 ? "current candle" : `${exitCandleOffset} candle(s) ago`;
        console.log(`[${uid}] 🔻 MULTI-PAIR SCANNER: SELL/EXIT SIGNAL on ${coin} (exit transition: ${offsetDesc})! Selling 100% of held ${heldBalance} ${coin}...`);
        try {
          const resolvedMarket = market || spotMarketMap.get(coin.toUpperCase());
          const assetIdx = resolvedMarket ? resolvedMarket.l1AssetIndex : (assetIndexMap.get(coin) || 10000);
          const szDec = resolvedMarket?.szDecimals !== undefined 
            ? resolvedMarket.szDecimals 
            : (szDecimalsMap.get(coin) !== undefined ? szDecimalsMap.get(coin) : 2);
          const formattedSz = formatSizeForHyperliquid(heldBalance, szDec);
          const limitPx = formatPriceForHyperliquid(coinPrice * 0.99);

          if (parseFloat(formattedSz) > 0) {
            await placeAgentOrder({
              agentPrivateKey,
              masterAddress,
              assetIndex: assetIdx,
              isBuy: false,
              limitPx: limitPx,
              sz: formattedSz
            });
            console.log(`[${uid}] ✅ Spot SELL placed: ${formattedSz} ${coin} (assetIndex: ${assetIdx}) @ limit ${limitPx} (szDecimals: ${szDec})`);
          } else {
            console.warn(`[${uid}] Formatted sell size for ${coin} rounded to zero with szDecimals=${szDec}`);
          }
        } catch (err) {
          console.error(`[${uid}] Spot Sell Execution Failed for ${coin}:`, err.message);
        }
      }
    }

    // Asset-Level Trading Permission: Check user-enabled assets
    const rawEnabled = config.enabled_assets;
    const enabledAssets = Array.isArray(rawEnabled)
      ? rawEnabled.map(a => a.toString().toUpperCase().trim())
      : (typeof rawEnabled === "string" && rawEnabled.length > 0 ? [rawEnabled.toUpperCase().trim()] : []);

    const isAssetAuthorizedForBuy = (coin) => {
      if (!enabledAssets || enabledAssets.length === 0) return false;
      const cleanCoin = coin.toUpperCase().trim().replace("/USDC", "");
      return enabledAssets.some(enabledRaw => {
        const enabled = enabledRaw.toUpperCase().trim().replace("/USDC", "");
        return (
          enabled === cleanCoin ||
          enabled === `U${cleanCoin}` ||
          cleanCoin === `U${enabled}`
        );
      });
    };

    if (enabledAssets.length === 0) {
      console.log(`[${uid}] 🔒 AI Bot auto-buy is PAUSED: 0 assets toggled ON in user settings. (Toggle assets ON in Spot Asset modal to authorize auto-trading).`);
    } else {
      console.log(`[${uid}] 🎯 Authorized Auto-Buy Assets (${enabledAssets.length}): ${enabledAssets.join(", ")}`);
    }

    // Filter scanned results for assets explicitly authorized (toggled ON) by user
    const authorizedResults = validResults.filter(r => isAssetAuthorizedForBuy(r.coin));

    // Prioritize authorized assets by proximity score descending (closest to or at 100% breakout transition)
    authorizedResults.sort((a, b) => (b.proximityScore || 0) - (a.proximityScore || 0));

    if (authorizedResults.length > 0) {
      const radarLog = authorizedResults.map(r => `${r.coin}: ${r.proximityScore}%${r.isBuySignal ? ' [▲ BUY]' : ''}`).join(", ");
      console.log(`[${uid}] 📡 TARGET RADAR (Toggled Assets Proximity): ${radarLog}`);
    }

    // 2. Automatically execute Spot BUY on whichever authorized coin has a confirmed buy signal,
    // prioritizing the asset closest to / at confirmed 100% breakout transition.
    const bestBuySignal = authorizedResults.find(r => 
      r.isBuySignal && 
      (spotBalances.get(r.coin) || 0) <= 0.0001
    );

    if (bestBuySignal && usdcBalance > 5.0 && config.active) {
      const { coin, coinPrice, breakoutCandleOffset, proximityScore, market } = bestBuySignal;
      const offsetDesc = breakoutCandleOffset === 0 ? "current candle" : `${breakoutCandleOffset} candle(s) ago`;
      console.log(`[${uid}] 🚀 MULTI-PAIR SCANNER: 30M GOLDEN CROSS BUY SIGNAL DETECTED on ${coin} (Proximity: ${proximityScore}%, breakout transition: ${offsetDesc})! Executing Spot Buy with $${usdcBalance.toFixed(2)} USDC...`);
      try {
        const resolvedMarket = market || spotMarketMap.get(coin.toUpperCase());
        const allocPct = (config.allocation_pct || 100) / 100.0;
        const buyAmountUsdc = usdcBalance * allocPct;
        const rawSize = buyAmountUsdc / coinPrice;
        const szDec = resolvedMarket?.szDecimals !== undefined 
          ? resolvedMarket.szDecimals 
          : (szDecimalsMap.get(coin) !== undefined ? szDecimalsMap.get(coin) : 2);
        const formattedSz = formatSizeForHyperliquid(rawSize, szDec);
        const limitPx = formatPriceForHyperliquid(coinPrice * 1.01);

        if (parseFloat(formattedSz) > 0) {
          const assetIdx = resolvedMarket ? resolvedMarket.l1AssetIndex : (assetIndexMap.get(coin) || 10000);
          await placeAgentOrder({
            agentPrivateKey,
            masterAddress,
            assetIndex: assetIdx,
            isBuy: true,
            limitPx: limitPx,
            sz: formattedSz
          });
          console.log(`[${uid}] ✅ Spot BUY placed: ${formattedSz} ${coin} (assetIndex: ${assetIdx}) @ limit ${limitPx} (szDecimals: ${szDec})`);
        } else {
          console.warn(`[${uid}] Formatted buy size for ${coin} rounded to zero with szDecimals=${szDec} for $${buyAmountUsdc.toFixed(2)} USDC allocation`);
        }
      } catch (err) {
        console.error(`[${uid}] Spot Buy Execution Failed for ${coin}:`, err.message);
      }
    }
  } else if (config.strategy === "Smart DCA / Dip Buyer") {
    console.log(`[${uid}] 💡 Smart DCA: Monitoring 24h market dips across ${availableCoins.length} Spot pairs`);
  } else if (config.strategy === "Grid Trading") {
    console.log(`[${uid}] 🕸️ Grid Trading: Monitoring grid levels across ${availableCoins.length} Spot pairs`);
  }

  // Build Target Radar telemetry object for toggled assets
  const targetRadarTelemetry = {};
  if (Array.isArray(validResults)) {
    validResults.forEach(r => {
      targetRadarTelemetry[r.coin] = {
        score: r.proximityScore || 0,
        isBuySignal: r.isBuySignal || false,
        price: r.coinPrice || 0
      };
    });
  }

  // Write Telemetry Stats back to Firestore users/{uid}/bot/bot_status
  const statusDocRef = db.doc(`users/${uid}/bot/bot_status`);
  await statusDocRef.set({
    pnl_usd: totalPnlUsd,
    pnl_pct: pnlPct,
    active_trades: activeTradesCount,
    strategy: config.strategy,
    timeframe: "30m",
    scanned_markets_count: availableCoins.length,
    target_radar: targetRadarTelemetry,
    updated_at: Date.now()
  }, { merge: true });

  console.log(`[${uid}] ✅ Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Scanned Spot Markets: ${availableCoins.length}`);
}

module.exports = {
  executeBotTick,
  evaluateEmaRibbonStrategy,
  calculateBreakoutProximity,
  calculateEMA,
  formatSizeForHyperliquid,
  formatPriceForHyperliquid,
  FALLBACK_SZ_DECIMALS
};

