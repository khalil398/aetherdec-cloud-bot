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
 * Evaluates High/Low EMA Channel Strategy on 30-minute timeframe.
 * 
 * Pine Script Equivalent:
 * ema200 = ta.ema(close, 200)
 * ema233 = ta.ema(close, 233)
 * ema8_high = ta.ema(high, 8), ema8_low = ta.ema(low, 8)
 * ema34_high = ta.ema(high, 34), ema34_low = ta.ema(low, 34)
 */
async function evaluateEmaRibbonStrategy(coin = "BTC", candles) {
  if (!candles || candles.length < 240) {
    console.warn(`[Strategy] Insufficient 30m candles for ${coin} (${candles?.length || 0}/240 required)`);
    return { isUptrend: false, isBuySignal: false, isSellSignal: false, metrics: {} };
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

  const ema200 = ema200Series[idx];
  const ema233 = ema233Series[idx];
  const ema8_high = ema8HighSeries[idx];
  const ema8_low = ema8LowSeries[idx];
  const ema34_high = ema34HighSeries[idx];
  const ema34_low = ema34LowSeries[idx];

  // Trend Filter Condition: Both White (8 High/Low) & Yellow (34 High/Low) strictly ABOVE EMA200 & EMA233
  const maxBaseline = Math.max(ema200, ema233);
  const isUptrend = (
    ema8_high > maxBaseline &&
    ema8_low > maxBaseline &&
    ema34_high > maxBaseline &&
    ema34_low > maxBaseline
  );

  // BUY Signal: Trend Filter MET AND White Channel crosses ABOVE Yellow Channel (ema8_low > ema34_high)
  const isBuySignal = isUptrend && (ema8_low > ema34_high);

  // SELL Signal: White Channel crosses BELOW Yellow Channel (ema8_high < ema34_low)
  const isSellSignal = (ema8_high < ema34_low);

  const metrics = {
    ema200,
    ema233,
    ema8_high,
    ema8_low,
    ema34_high,
    ema34_low,
    maxBaseline,
    isUptrend
  };

  return { isUptrend, isBuySignal, isSellSignal, metrics };
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
  const FALLBACK_UNIVERSE = ["BTC", "ETH", "SOL", "HYPE", "PURR", "HFUN", "SUI", "PEPE", "DOGE", "AVAX", "LINK", "XRP"];
  
  if (spotMarkets && spotMarkets.length > 0) {
    spotMarkets.forEach(m => {
      assetIndexMap.set(m.coin, m.l1AssetIndex);
    });
  } else {
    assetIndexMap.set("PURR", 10000);
    assetIndexMap.set("HFUN", 10001);
    assetIndexMap.set("HYPE", 10150);
    assetIndexMap.set("BTC", 10200);
    assetIndexMap.set("ETH", 10201);
    assetIndexMap.set("SOL", 10202);
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
          const coinPrice = parseFloat(allMids[coin] || 0);
          if (coinPrice <= 0) return null;

          const candles30m = await fetchCandleSnapshot(coin, "30m", 260);
          const signalResult = await evaluateEmaRibbonStrategy(coin, candles30m);
          return { coin, coinPrice, ...signalResult };
        } catch (e) {
          return null;
        }
      })
    );

    const validResults = scanResults.filter(Boolean);

    // 1. Process SELL Signals for held token positions
    for (const res of validResults) {
      const { coin, coinPrice, isSellSignal } = res;
      const heldBalance = spotBalances.get(coin) || 0.0;

      if (isSellSignal && heldBalance > 0.0001 && config.active) {
        console.log(`[${uid}] 🔻 MULTI-PAIR SCANNER: SELL SIGNAL on ${coin}! Selling 100% of ${heldBalance} ${coin}...`);
        try {
          const assetIdx = assetIndexMap.get(coin) || (coin === "BTC" ? 10200 : 10000);
          await placeAgentOrder({
            agentPrivateKey,
            masterAddress,
            assetIndex: assetIdx,
            isBuy: false,
            limitPx: (coinPrice * 0.99).toFixed(2),
            sz: heldBalance.toFixed(4)
          });
        } catch (err) {
          console.error(`[${uid}] Spot Sell Execution Failed for ${coin}:`, err.message);
        }
      }
    }

    // 2. Automatically execute Spot BUY on whichever coin generates a valid 30m EMA Golden Cross signal FIRST
    const firstBuySignal = validResults.find(r => r.isBuySignal && (spotBalances.get(r.coin) || 0) <= 0.0001);

    if (firstBuySignal && usdcBalance > 5.0 && config.active) {
      const { coin, coinPrice } = firstBuySignal;
      console.log(`[${uid}] 🚀 MULTI-PAIR SCANNER: 30M GOLDEN CROSS BUY SIGNAL DETECTED on ${coin}! Executing Spot Buy with $${usdcBalance.toFixed(2)} USDC...`);
      try {
        const allocPct = (config.allocation_pct || 100) / 100.0;
        const buyAmountUsdc = usdcBalance * allocPct;
        const sizeToBuy = (buyAmountUsdc / coinPrice).toFixed(4);

        const assetIdx = assetIndexMap.get(coin) || (coin === "BTC" ? 10200 : 10000);
        await placeAgentOrder({
          agentPrivateKey,
          masterAddress,
          assetIndex: assetIdx,
          isBuy: true,
          limitPx: (coinPrice * 1.01).toFixed(2),
          sz: sizeToBuy
        });
      } catch (err) {
        console.error(`[${uid}] Spot Buy Execution Failed for ${coin}:`, err.message);
      }
    }
  } else if (config.strategy === "Smart DCA / Dip Buyer") {
    console.log(`[${uid}] 💡 Smart DCA: Monitoring 24h market dips across ${availableCoins.length} Spot pairs`);
  } else if (config.strategy === "Grid Trading") {
    console.log(`[${uid}] 🕸️ Grid Trading: Monitoring grid levels across ${availableCoins.length} Spot pairs`);
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
    updated_at: Date.now()
  }, { merge: true });

  console.log(`[${uid}] ✅ Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Scanned Spot Markets: ${availableCoins.length}`);
}

module.exports = {
  executeBotTick,
  evaluateEmaRibbonStrategy,
  calculateEMA
};

