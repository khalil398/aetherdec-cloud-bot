const {
  fetchUserClearinghouseState,
  fetchSpotClearinghouseState,
  fetchAllMids,
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
 * Evaluates High/Low EMA Channel Strategy on 5-minute timeframe.
 * 
 * Pine Script Equivalent:
 * ema200 = ta.ema(close, 200)
 * ema233 = ta.ema(close, 233)
 * ema8_high = ta.ema(high, 8), ema8_low = ta.ema(low, 8)
 * ema34_high = ta.ema(high, 34), ema34_low = ta.ema(low, 34)
 */
async function evaluateEmaRibbonStrategy(coin = "BTC", candles) {
  if (!candles || candles.length < 240) {
    console.warn(`[Strategy] Insufficient 5m candles for ${coin} (${candles?.length || 0}/240 required)`);
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

  console.log(`[${uid}] ⚡ Running 5m High/Low EMA Channel Strategy '${config.strategy}' (Allocation: ${config.allocation_pct}%)...`);

  // 1. Fetch Spot Clearinghouse & Mid Prices
  const spotClearinghouse = await fetchSpotClearinghouseState(masterAddress);
  const perpClearinghouse = await fetchUserClearinghouseState(masterAddress);
  const allMids = await fetchAllMids();
  const targetCoin = config.target_coin || "BTC";
  const currentPrice = parseFloat(allMids[targetCoin] || allMids["BTC"] || 0);

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

  // Check Spot Balances
  let usdcBalance = 0.0;
  let tokenBalance = 0.0;
  if (spotClearinghouse && spotClearinghouse.balances) {
    spotClearinghouse.balances.forEach(b => {
      if (b.coin === "USDC") usdcBalance = parseFloat(b.total || 0) - parseFloat(b.hold || 0);
      if (b.coin === targetCoin) tokenBalance = parseFloat(b.total || 0) - parseFloat(b.hold || 0);
    });
  }

  const pnlPct = accountValue > 0 ? (totalPnlUsd / accountValue) * 100.0 : 0.0;

  // 2. Fetch 5m Candle Snapshot & Evaluate Indicators
  const candles5m = await fetchCandleSnapshot(targetCoin, "5m", 300);
  const signalResult = await evaluateEmaRibbonStrategy(targetCoin, candles5m);
  const { isUptrend, isBuySignal, isSellSignal, metrics } = signalResult;

  const isEmaStrategyActive = (config.strategy === "5m EMA High/Low" || config.strategy === "Trend Following" || !config.strategy);

  console.log(`[${uid}] 📊 5m Indicators (${targetCoin}): Price=$${currentPrice}, EMA200=${metrics.ema200?.toFixed(2)}, EMA8_low=${metrics.ema8_low?.toFixed(2)}, EMA34_high=${metrics.ema34_high?.toFixed(2)}`);
  console.log(`[${uid}] 🚦 Signals (Mode '${config.strategy}'): Uptrend=${isUptrend}, BUY=${isBuySignal}, SELL=${isSellSignal}`);

  // 3. Trade Execution Logic
  if (isEmaStrategyActive) {
    if (isBuySignal && usdcBalance > 5.0 && config.active) {
      console.log(`[${uid}] 🚀 SPOT BUY SIGNAL TRIGGERED! Executing Spot Buy for ${targetCoin} with $${usdcBalance.toFixed(2)} USDC...`);
      try {
        const allocPct = (config.allocation_pct || 100) / 100.0;
        const buyAmountUsdc = usdcBalance * allocPct;
        const sizeToBuy = (buyAmountUsdc / currentPrice).toFixed(4);

        const spotAssetIndex = targetCoin === "BTC" ? 10200 : 10000;
        await placeAgentOrder({
          agentPrivateKey,
          masterAddress,
          assetIndex: spotAssetIndex,
          isBuy: true,
          limitPx: (currentPrice * 1.01).toFixed(2),
          sz: sizeToBuy
        });
      } catch (err) {
        console.error(`[${uid}] Spot Buy Execution Failed:`, err.message);
      }
    } else if (isSellSignal && tokenBalance > 0.0001 && config.active) {
      console.log(`[${uid}] 🔻 SPOT SELL SIGNAL TRIGGERED! Selling 100% of ${tokenBalance} ${targetCoin} back to USDC...`);
      try {
        const spotAssetIndex = targetCoin === "BTC" ? 10200 : 10000;
        await placeAgentOrder({
          agentPrivateKey,
          masterAddress,
          assetIndex: spotAssetIndex,
          isBuy: false,
          limitPx: (currentPrice * 0.99).toFixed(2),
          sz: tokenBalance.toFixed(4)
        });
      } catch (err) {
        console.error(`[${uid}] Spot Sell Execution Failed:`, err.message);
      }
    }
  } else if (config.strategy === "Smart DCA / Dip Buyer") {
    console.log(`[${uid}] 💡 Smart DCA: Monitoring 24h market dips for ${targetCoin}`);
  } else if (config.strategy === "Grid Trading") {
    console.log(`[${uid}] 🕸️ Grid Trading: Monitoring grid levels for ${targetCoin}`);
  }


  // 4. Write Telemetry Stats back to Firestore users/{uid}/bot/bot_status
  const statusDocRef = db.doc(`users/${uid}/bot/bot_status`);
  await statusDocRef.set({
    pnl_usd: totalPnlUsd,
    pnl_pct: pnlPct,
    active_trades: activeTradesCount,
    strategy: config.strategy,
    timeframe: "5m",
    signal_status: isBuySignal ? "BUY_SIGNAL" : isSellSignal ? "SELL_SIGNAL" : isUptrend ? "UPTREND_HOLD" : "DOWNTREND_IDLE",
    indicators: {
      ema200: metrics.ema200 || 0,
      ema233: metrics.ema233 || 0,
      ema8_high: metrics.ema8_high || 0,
      ema8_low: metrics.ema8_low || 0,
      ema34_high: metrics.ema34_high || 0,
      ema34_low: metrics.ema34_low || 0
    },
    updated_at: Date.now()
  }, { merge: true });

  console.log(`[${uid}] ✅ Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Signal: ${isBuySignal ? "BUY" : isSellSignal ? "SELL" : "NEUTRAL"}`);
}

module.exports = {
  executeBotTick,
  evaluateEmaRibbonStrategy,
  calculateEMA
};
