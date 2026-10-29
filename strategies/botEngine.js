const {
  fetchUserClearinghouseState,
  invalidateClearinghouseCache,
  fetchSpotClearinghouseState,
  fetchSpotMetaAndCtxs,
  fetchPerpMetaAndCtxs,
  fetchCandleSnapshot,
  placeAgentOrder,
  placeAgentPositionTpslOrders,
  fetchFrontendOpenOrders,
  updateAgentLeverage,
  checkUserAbstraction,
  formatPriceForHyperliquid,
  fetchUserFills
} = require("../services/hyperliquid");

/**
 * Calculates Exponential Moving Average (EMA) array for a series of values.
 */
function calculateEMA(values, period) {
  if (!values || values.length === 0) return [];
  const alpha = 2 / (period + 1);
  const emas = new Array(values.length);
  let currentEma = values[0];
  emas[0] = currentEma;

  for (let i = 1; i < values.length; i++) {
    currentEma = (values[i] * alpha) + (currentEma * (1 - alpha));
    emas[i] = currentEma;
  }
  return emas;
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

// Rate limit cooldown map: symbol -> timestamp until which requests for this symbol are paused (15s backoff)
const rateLimitedSymbols = new Map();

// Order rejection cooldown map: symbol -> timestamp until which order placement for this symbol is paused (30s backoff)
const orderRejectionCooldowns = new Map();

// Candle timestamp of last executed signal per symbol (ensures strictly 1 order per candle close)
const lastTriggeredCandleTimeBySymbol = new Map();

// Map to track active positions with native TP/SL trigger orders placed: key -> timestamp
const hasDispatchedTpslMap = new Map();

function cleanCoinSymbol(coin) {
  if (!coin) return "";
  return coin.toString().toUpperCase().trim()
    .replace("/USDC", "")
    .replace("/USDT", "")
    .replace("USDC", "")
    .replace("USDT", "")
    .replace("/", "");
}

function isSymbolCoolingDown(symbol) {
  const clean = cleanCoinSymbol(symbol);
  const expiry = rateLimitedSymbols.get(clean) || 0;
  return Date.now() < expiry;
}

function recordSymbol429(symbol) {
  const clean = cleanCoinSymbol(symbol);
  console.warn(`[Hyperliquid] ⚠️ Rate limit (429) active for ${clean}. Pausing evaluation for 15 seconds.`);
  rateLimitedSymbols.set(clean, Date.now() + 15000);
}

function recordOrderRejection(symbol, cooldownMs = 30000) {
  const clean = cleanCoinSymbol(symbol);
  console.warn(`[Hyperliquid] ⏳ Activated ${cooldownMs / 1000}s order rejection cooldown for ${clean}.`);
  orderRejectionCooldowns.set(clean, Date.now() + cooldownMs);
}

function isOrderInRejectionCooldown(symbol) {
  const clean = cleanCoinSymbol(symbol);
  const expiry = orderRejectionCooldowns.get(clean) || 0;
  return Date.now() < expiry;
}

/**
 * Synchronizes recent Hyperliquid trade fills to Firestore users/{targetId}/trade_history.
 * Captures manual closes, SL/TP fills, and bot fills so Trade History is never out of sync.
 */
async function syncUserFillsToFirestore(db, masterAddress, writeStatusTargets) {
  if (!db || !masterAddress) return;
  try {
    const fills = await fetchUserFills(masterAddress);
    if (!Array.isArray(fills) || fills.length === 0) return;

    // Filter for closed position fills
    const closeFills = fills.filter(f => {
      const isClose = f.dir && f.dir.toLowerCase().includes("close");
      const hasClosedPnl = f.closedPnl !== undefined && f.closedPnl !== null && parseFloat(f.closedPnl) !== 0;
      return isClose || hasClosedPnl;
    });

    for (const fill of closeFills) {
      const fillTime = Number(fill.time || 0);
      const tid = fill.tid || fill.hash || fill.oid || fillTime;
      const tradeId = `hl_fill_${tid}`;

      const exitPrice = parseFloat(fill.px || 0);
      const realizedPnl = parseFloat(fill.closedPnl || 0);
      const szCrypto = parseFloat(fill.sz || 0);
      const posSizeUsd = szCrypto * exitPrice;
      const isLong = fill.dir ? fill.dir.toLowerCase().includes("long") : (fill.side === "A");

      let entryPx = exitPrice;
      if (szCrypto > 0 && realizedPnl !== 0) {
        entryPx = isLong ? (exitPrice - (realizedPnl / szCrypto)) : (exitPrice + (realizedPnl / szCrypto));
      }

      const closedRecord = {
        id: tradeId,
        symbol: `${fill.coin}/USDC`,
        coin: fill.coin,
        isLong: isLong,
        side: isLong ? "LONG" : "SHORT",
        leverage: 10,
        margin: parseFloat((posSizeUsd / 10).toFixed(2)),
        positionSizeUsdc: parseFloat(posSizeUsd.toFixed(2)),
        entryPrice: parseFloat(entryPx.toFixed(2)),
        exitPrice: parseFloat(exitPrice.toFixed(2)),
        realizedPnl: parseFloat(realizedPnl.toFixed(4)),
        realizedPnlPercent: posSizeUsd > 0 ? parseFloat(((realizedPnl / (posSizeUsd / 10)) * 100).toFixed(2)) : 0.0,
        closeReason: fill.dir || "Hyperliquid Fill",
        fee: parseFloat(fill.fee || 0),
        timestamp: fillTime,
        isDemo: false,
        walletAddress: masterAddress
      };

      for (const targetId of writeStatusTargets) {
        try {
          await db.collection(`users/${targetId}/trade_history`).doc(tradeId).set(closedRecord, { merge: true });
        } catch (_) {}
      }
    }
  } catch (err) {
    console.error(`[Hyperliquid] Error syncing userFills to trade_history:`, err.message);
  }
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
 * Resolves a spot market object from any symbol representation (e.g., UZEC, UZEC/USDC, ZEC, @272, etc.)
 */
function resolveSpotMarket(symbol, spotMarketMap) {
  if (!symbol || !spotMarketMap) return null;
  const raw = symbol.toString().trim();
  const upper = raw.toUpperCase();
  if (spotMarketMap.has(upper)) return spotMarketMap.get(upper);

  const clean = upper
    .replace("/USDC", "")
    .replace("/USDT", "")
    .replace("USDC", "")
    .replace("USDT", "")
    .replace("/", "");
  if (spotMarketMap.has(clean)) return spotMarketMap.get(clean);

  if (clean.startsWith("U") && spotMarketMap.has(clean.substring(1))) {
    return spotMarketMap.get(clean.substring(1));
  }
  if (!clean.startsWith("U") && spotMarketMap.has(`U${clean}`)) {
    return spotMarketMap.get(`U${clean}`);
  }
  return null;
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
 *    - Trend Filter: Both channels strictly ABOVE EMA 200 & EMA 233 if available.
 *    - State Persistence:
 *      When an asset achieves a confirmed breakout (▲ BUY), it enters and remains in IN_BUY state
 *      until an opposite exit breakdown (▼ SELL) occurs, accurately matching BotStrategyChartView.kt.
 * 
 * 2. SELL / Exit Signal (▼ SELL):
 *    - Full Channel Exit Requirement:
 *      Both EMA 8 lines (White Channel) MUST be strictly BELOW the Yellow Channel (EMA 34 High and EMA 34 Low).
 *      Condition: EMA_8_High < EMA_34_Low AND EMA_8_Low < EMA_34_Low.
 */
async function evaluateEmaRibbonStrategy(coin = "BTC", candles) {
  if (!candles || candles.length < 35) {
    console.warn(`[Strategy] Insufficient 30m candles for ${coin} (${candles?.length || 0}/35 required)`);
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

  const n = candles.length;
  const idx = n - 1; // Current bar

  // Helper 1: Full White Channel strictly ABOVE Yellow Channel (both lines)
  const isWhiteAboveYellow = (i) => {
    if (i < 0 || i >= n) return false;
    const e8H = ema8HighSeries[i];
    const e8L = ema8LowSeries[i];
    const e34H = ema34HighSeries[i];
    return (e8L > e34H && e8H > e34H);
  };

  // Helper 2: Full White Channel strictly BELOW Yellow Channel (both lines)
  const isWhiteBelowYellow = (i) => {
    if (i < 0 || i >= n) return false;
    const e8H = ema8HighSeries[i];
    const e8L = ema8LowSeries[i];
    const e34L = ema34LowSeries[i];
    return (e8H < e34L && e8L < e34L);
  };

  // Helper 3: Trend Filter (Both channels strictly above EMA 200 & EMA 233 if available)
  const checkUptrend = (i) => {
    if (i < 0 || i >= n) return false;
    const e200 = (ema200Series && ema200Series[i]) ? ema200Series[i] : 0;
    const e233 = (ema233Series && ema233Series[i]) ? ema233Series[i] : e200;
    if (e200 <= 0 && e233 <= 0) return true;
    const maxBaseline = Math.max(e200, e233);
    return (
      ema8HighSeries[i] > maxBaseline &&
      ema8LowSeries[i] > maxBaseline &&
      ema34HighSeries[i] > maxBaseline
    );
  };

  // Sequential State Machine tracking matching BotStrategyChartView.kt exactly
  let currentPositionState = "NEUTRAL"; // "NEUTRAL", "IN_BUY", "IN_SELL"
  let lastBuySignalIdx = -1;
  let lastSellSignalIdx = -1;

  for (let i = 0; i < n; i++) {
    const isAbove = isWhiteAboveYellow(i);
    const isBelow = isWhiteBelowYellow(i);

    if (isAbove) {
      if (currentPositionState !== "IN_BUY") {
        currentPositionState = "IN_BUY";
        lastBuySignalIdx = i;
      }
    } else if (isBelow) {
      if (currentPositionState !== "IN_SELL") {
        currentPositionState = "IN_SELL";
        lastSellSignalIdx = i;
      }
    }
  }

  const isCurrentAbove = isWhiteAboveYellow(idx);
  const isCurrentBelow = isWhiteBelowYellow(idx);
  const isUptrend = checkUptrend(idx);

  const breakoutCandleOffset = lastBuySignalIdx >= 0 ? (idx - lastBuySignalIdx) : -1;
  const exitCandleOffset = lastSellSignalIdx >= 0 ? (idx - lastSellSignalIdx) : -1;

  // Active BUY signal is confirmed if current position state is IN_BUY
  const isBuySignal = (currentPositionState === "IN_BUY");
  const isSellSignal = (currentPositionState === "IN_SELL");

  const ema200 = (ema200Series && ema200Series[idx]) ? ema200Series[idx] : 0;
  const ema233 = (ema233Series && ema233Series[idx]) ? ema233Series[idx] : ema200;
  const ema8_high = ema8HighSeries[idx];
  const ema8_low = ema8LowSeries[idx];
  const ema34_high = ema34HighSeries[idx];
  const ema34_low = ema34LowSeries[idx];
  const maxBaseline = Math.max(ema200, ema233);

  // Calculate Breakout Proximity (% towards valid ▲ BUY breakout target)
  const prevE8H = idx >= 1 ? ema8HighSeries[idx - 1] : 0;
  const prevE8L = idx >= 1 ? ema8LowSeries[idx - 1] : 0;
  const proximityScore = (isBuySignal || isCurrentAbove)
    ? 100
    : calculateBreakoutProximity(ema8_high, ema8_low, ema34_high, ema34_low, prevE8H, prevE8L);

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
    currentPositionState,
    breakoutCandleOffset,
    exitCandleOffset,
    proximityScore
  };

  return { isUptrend, isBuySignal, isSellSignal, breakoutCandleOffset, exitCandleOffset, proximityScore, currentPositionState, metrics };
}

/**
 * Evaluates ICT Market Structure (HH, HL, LH, LL) and Structural Ray Breakouts (CHoCh)
 * - Fractal Highs & Lows: Williams/ICT 5-bar pivots
 * - Classification:
 *     HH: new Fractal High > previous Fractal High
 *     HL: new Fractal Low > previous Fractal Low (Uptrend)
 *     LH: new Fractal High < previous Fractal High
 *     LL: new Fractal Low < previous Fractal Low (Downtrend)
 * - Trend & Range Context:
 *     Uptrend: continuous HH and HL formation
 *     Downtrend: continuous LH and LL formation
 *     Range / Sideways: overlapping swings without creating new highs or lows
 * - Clean Ray Lines & Termination:
 *     Active structural boundaries extend until broken by a valid body close
 */
async function evaluateIctMarketStructure(coin = "BTC", candles) {
  if (!candles || candles.length < 15) {
    console.warn(`[ICT Strategy] Insufficient 30m candles for ${coin} (${candles?.length || 0}/15 required)`);
    return {
      isUptrend: false,
      isBuySignal: false,
      isSellSignal: false,
      marketState: "RANGE",
      lastHighType: "",
      lastLowType: "",
      proximityScore: 0,
      breakoutCandleOffset: -1,
      exitCandleOffset: -1,
      currentPositionState: "NEUTRAL",
      metrics: {}
    };
  }

  const n = candles.length;
  const highPrices = candles.map(c => parseFloat(c.h));
  const lowPrices = candles.map(c => parseFloat(c.l));
  const closePrices = candles.map(c => parseFloat(c.c));

  // 1. Identify 5-bar confirmed Fractal Highs and Fractal Lows
  const allCandidates = [];
  for (let i = 2; i < n - 2; i++) {
    const isHigh = highPrices[i] >= highPrices[i - 2] &&
                   highPrices[i] >= highPrices[i - 1] &&
                   highPrices[i] >= highPrices[i + 1] &&
                   highPrices[i] >= highPrices[i + 2];

    const isLow = lowPrices[i] <= lowPrices[i - 2] &&
                  lowPrices[i] <= lowPrices[i - 1] &&
                  lowPrices[i] <= lowPrices[i + 1] &&
                  lowPrices[i] <= lowPrices[i + 2];

    if (isHigh) {
      let breakIdx = -1;
      for (let k = i + 1; k < n; k++) {
        // Valid body close breaks the level
        if (closePrices[k] > highPrices[i]) {
          breakIdx = k;
          break;
        }
      }
      allCandidates.push({
        isHigh: true,
        price: highPrices[i],
        startIndex: i,
        breakIndex: breakIdx,
        isBroken: breakIdx !== -1,
        endIndex: breakIdx !== -1 ? breakIdx : (n - 1)
      });
    }

    if (isLow) {
      let breakIdx = -1;
      for (let k = i + 1; k < n; k++) {
        // Valid body close breaks the level
        if (closePrices[k] < lowPrices[i]) {
          breakIdx = k;
          break;
        }
      }
      allCandidates.push({
        isHigh: false,
        price: lowPrices[i],
        startIndex: i,
        breakIndex: breakIdx,
        isBroken: breakIdx !== -1,
        endIndex: breakIdx !== -1 ? breakIdx : (n - 1)
      });
    }
  }

  // 2. Classify Swings into HH, HL, LH, LL
  const highCandidates = allCandidates.filter(c => c.isHigh).sort((a, b) => a.startIndex - b.startIndex);
  let prevHigh = null;
  highCandidates.forEach(h => {
    if (prevHigh === null) {
      h.swingType = "HH";
    } else if (h.price > prevHigh) {
      h.swingType = "HH"; // Higher High
    } else {
      h.swingType = "LH"; // Lower High
    }
    prevHigh = h.price;
  });

  const lowCandidates = allCandidates.filter(c => !c.isHigh).sort((a, b) => a.startIndex - b.startIndex);
  let prevLow = null;
  lowCandidates.forEach(l => {
    if (prevLow === null) {
      l.swingType = "LL";
    } else if (l.price > prevLow) {
      l.swingType = "HL"; // Higher Low
    } else {
      l.swingType = "LL"; // Lower Low
    }
    prevLow = l.price;
  });

  // 3. Trend & Range Contextual Rules
  const lastHigh = highCandidates.length > 0 ? highCandidates[highCandidates.length - 1] : null;
  const lastLow = lowCandidates.length > 0 ? lowCandidates[lowCandidates.length - 1] : null;

  let marketState = "RANGE"; // "UPTREND", "DOWNTREND", "RANGE"
  if (lastHigh && lastLow) {
    if (lastHigh.swingType === "HH" && lastLow.swingType === "HL") {
      marketState = "UPTREND"; // Continuous HH and HL formation
    } else if (lastHigh.swingType === "LH" && lastLow.swingType === "LL") {
      marketState = "DOWNTREND"; // Continuous LH and LL formation
    } else {
      marketState = "RANGE"; // Swings overlap without creating new highs or lows
    }
  }

  // Active unbroken structural boundary levels
  const activeHighs = highCandidates.filter(h => !h.isBroken);
  const activeLows = lowCandidates.filter(l => !l.isBroken);
  const lastActiveHigh = activeHighs.length > 0 ? activeHighs[activeHighs.length - 1] : null;
  const lastActiveLow = activeLows.length > 0 ? activeLows[activeLows.length - 1] : null;

  // Break of structure / body close breakout confirmation
  const isBreakoutHigh = lastHigh && lastHigh.isBroken && lastHigh.breakIndex >= n - 2;
  const isBreakdownLow = lastLow && lastLow.isBroken && lastLow.breakIndex >= n - 2;

  const isBuySignal = marketState === "UPTREND" || isBreakoutHigh;
  const isSellSignal = marketState === "DOWNTREND" || isBreakdownLow;

  const proximityScore = marketState === "UPTREND" ? 100 : (marketState === "DOWNTREND" ? 0 : 50);

  const metrics = {
    marketState,
    lastHighType: lastHigh ? lastHigh.swingType : "",
    lastHighPrice: lastHigh ? lastHigh.price : 0,
    lastLowType: lastLow ? lastLow.swingType : "",
    lastLowPrice: lastLow ? lastLow.price : 0,
    activeHighPrice: lastActiveHigh ? lastActiveHigh.price : 0,
    activeLowPrice: lastActiveLow ? lastActiveLow.price : 0,
    proximityScore
  };

  return {
    isUptrend: marketState === "UPTREND",
    isBuySignal,
    isSellSignal,
    marketState,
    lastHighType: lastHigh ? lastHigh.swingType : "",
    lastLowType: lastLow ? lastLow.swingType : "",
    proximityScore,
    breakoutCandleOffset: isBreakoutHigh ? 0 : -1,
    exitCandleOffset: isBreakdownLow ? 0 : -1,
    currentPositionState: isBuySignal ? "IN_BUY" : (isSellSignal ? "IN_SELL" : "NEUTRAL"),
    metrics
  };
}

/**
 * Evaluates 5-Minute Dual-Ribbon Strategy (Combined Ribbon):
 * 
 * 1. Ribbon 1 (134 EMA Green Band):
 *    - EMA 134 High: ta.ema(high, 134)
 *    - EMA 134 Low:  ta.ema(low, 134)
 *    - Thick green structural boundary band (#00E676)
 * 
 * 2. Ribbon 2 (Krypt Fast EMAs):
 *    - 8 EMAs on close: lengths [20, 25, 30, 35, 40, 45, 50, 55]
 *    - Fastest MA-1 (length 20, color #F5EB5D) acts as primary yellow reference line
 * 
 * 3. Core Entry Signals & Pullback Rules:
 *    Long Position Entry Setup (Upside Reversal):
 *      Step 1 (Cross): EMA 20 and price cross and trade above both 134 EMA high and low green band.
 *      Step 2 (Pullback): Price pulls back into Krypt ribbon space without a complete candle body closing entirely below the ribbon.
 *      Step 3 (Confirmation): Trigger Buy Signal when a green candle closes above yellow line (EMA 20).
 *    Short Position Entry Setup (Downside Reversal):
 *      Step 1 (Cross): EMA 20 and price cross and trade below both 134 EMA high and low green band.
 *      Step 2 (Pullback): Price pulls back into Krypt ribbon space without a complete candle body closing entirely above the ribbon.
 *      Step 3 (Confirmation): Trigger Sell Signal when a red candle closes below yellow line (EMA 20).
 * 
 * 4. Risk Management & Targets (SL / TP):
 *    - Stop Loss (SL): Automatically placed outside 134 EMA green band (below for long, above for short).
 *    - Take Profit (TP): Set dynamically to achieve a minimum 1:2 Risk-to-Reward Ratio (RRR).
 */
async function evaluateDualRibbonStrategy(coin = "BTC", candles) {
  if (!candles || candles.length < 136) {
    console.warn(`[Dual-Ribbon Strategy] Insufficient 5m candles for ${coin} (${candles?.length || 0}/136 required)`);
    return {
      isUptrend: false,
      isBuySignal: false,
      isSellSignal: false,
      isBuyTriggered: false,
      isSellTriggered: false,
      breakoutCandleOffset: -1,
      exitCandleOffset: -1,
      stopLoss: 0,
      takeProfit: 0,
      entryPrice: 0,
      riskRewardRatio: 2.0,
      proximityScore: 0,
      currentPositionState: "NEUTRAL",
      metrics: {}
    };
  }

  const n = candles.length;
  const openPrices = candles.map(c => parseFloat(c.o !== undefined ? c.o : c.c));
  const highPrices = candles.map(c => parseFloat(c.h));
  const lowPrices = candles.map(c => parseFloat(c.l));
  const closePrices = candles.map(c => parseFloat(c.c));

  // Ribbon 1: 134 EMA High & Low Bands
  const r1Ema134High = calculateEMA(highPrices, 134);
  const r1Ema134Low = calculateEMA(lowPrices, 134);

  // Ribbon 2: Krypt Fast EMAs (20, 25, 30, 35, 40, 45, 50, 55)
  const KRYPT_LENGTHS = [20, 25, 30, 35, 40, 45, 50, 55];
  const kryptEmaSeries = KRYPT_LENGTHS.map(len => calculateEMA(closePrices, len));

  const ma1_20 = kryptEmaSeries[0]; // Primary yellow reference line (length 20)
  const ma8_55 = kryptEmaSeries[7]; // Slowest Krypt line (length 55)

  let lastSignalDirection = 0; // 0 = none, 1 = BUY, -1 = SELL (Strict lock: cannot fire same signal consecutively)
  let hasExpandedLong = false;
  let pullbackConfirmedLong = false;
  let hasExpandedShort = false;
  let pullbackConfirmedShort = false;
  let currentPositionState = "NEUTRAL";
  let lastBuySignalIdx = -1;
  let lastSellSignalIdx = -1;

  const buySignals = new Array(n).fill(false);
  const sellSignals = new Array(n).fill(false);
  const slSeries = new Array(n).fill(0);
  const tpSeries = new Array(n).fill(0);

  for (let i = 0; i < n; i++) {
    const e134H = r1Ema134High[i];
    const e134L = r1Ema134Low[i];
    const e20 = ma1_20[i];
    const e55 = ma8_55[i];
    const o = openPrices[i];
    const h = highPrices[i];
    const l = lowPrices[i];
    const c = closePrices[i];

    // Krypt Ribbon space boundaries
    const ribbonTop = Math.max(e20, e55);
    const ribbonBottom = Math.min(e20, e55);
    const bodyTop = Math.max(o, c);
    const bodyBottom = Math.min(o, c);

    const isAboveGreenBand = (e20 > e134H && c > e134H);
    const isBelowGreenBand = (e20 < e134L && c < e134L);

    // LONG 3-STEP CONFIRMATION SEQUENCE:
    // Step 1: Regime filter established completely above 134 green band & price expands above yellow line (EMA 20).
    // Step 2: Clear retracement/pullback into Krypt ribbon (touching/entering between EMA 20 & 55, no body below ribbon).
    // Step 3: Trigger ▲ BUY strictly on close of first green candle that closes back above yellow line (EMA 20).
    if (lastSignalDirection !== 1) {
      if (c < e134L || e20 < e134L || bodyTop < ribbonBottom) {
        hasExpandedLong = false;
        pullbackConfirmedLong = false;
      } else if (isAboveGreenBand) {
        if (!hasExpandedLong) {
          if (c > e20) {
            hasExpandedLong = true;
          }
        } else {
          if (!pullbackConfirmedLong) {
            const touchesOrEntersRibbon = (l <= ribbonTop && bodyTop >= ribbonBottom);
            if (touchesOrEntersRibbon && (c <= e20 || c < o)) {
              pullbackConfirmedLong = true;
            }
          } else {
            const isGreenCandle = c > o;
            const closesAboveEma20 = c > e20;

            if (isGreenCandle && closesAboveEma20) {
              buySignals[i] = true;
              lastBuySignalIdx = i;
              lastSignalDirection = 1; // STRICT SINGLE-SIGNAL LOCK: Blocks all BUY signals until opposite SELL!
              currentPositionState = "IN_BUY";
              hasExpandedLong = false;
              pullbackConfirmedLong = false;

              // Stop Loss sits snugly right against 134 EMA Low green band boundary
              let sl = e134L;
              if (sl >= c) {
                sl = c * 0.999;
              }
              const risk = Math.max(c - sl, c * 0.001);
              const tp = c + (risk * 2.0); // Exact 1:2 RRR

              slSeries[i] = sl;
              tpSeries[i] = tp;
            }
          }
        }
      }
    }

    // SHORT 3-STEP CONFIRMATION SEQUENCE:
    // Step 1: Regime filter established completely below 134 green band & price expands below yellow line (EMA 20).
    // Step 2: Clear retracement/pullback up into Krypt ribbon (touching/entering between EMA 20 & 55, no body above ribbon).
    // Step 3: Trigger ▼ SELL strictly on close of first red candle that closes back below yellow line (EMA 20).
    if (lastSignalDirection !== -1) {
      if (c > e134H || e20 > e134H || bodyBottom > ribbonTop) {
        hasExpandedShort = false;
        pullbackConfirmedShort = false;
      } else if (isBelowGreenBand) {
        if (!hasExpandedShort) {
          if (c < e20) {
            hasExpandedShort = true;
          }
        } else {
          if (!pullbackConfirmedShort) {
            const touchesOrEntersRibbon = (h >= ribbonBottom && bodyBottom <= ribbonTop);
            if (touchesOrEntersRibbon && (c >= e20 || c > o)) {
              pullbackConfirmedShort = true;
            }
          } else {
            const isRedCandle = c < o;
            const closesBelowEma20 = c < e20;

            if (isRedCandle && closesBelowEma20) {
              sellSignals[i] = true;
              lastSellSignalIdx = i;
              lastSignalDirection = -1; // STRICT SINGLE-SIGNAL LOCK: Blocks all SELL signals until opposite BUY!
              currentPositionState = "IN_SELL";
              hasExpandedShort = false;
              pullbackConfirmedShort = false;

              // Stop Loss sits snugly right against 134 EMA High green band boundary
              let sl = e134H;
              if (sl <= c) {
                sl = c * 1.001;
              }
              const risk = Math.max(sl - c, c * 0.001);
              const tp = c - (risk * 2.0); // Exact 1:2 RRR

              slSeries[i] = sl;
              tpSeries[i] = tp;
            }
          }
        }
      }
    }
  }

  const idx = n - 1;
  const lastClosedIdx = n >= 2 ? n - 2 : idx;
  const currentE134H = r1Ema134High[idx];
  const currentE134L = r1Ema134Low[idx];
  const currentE20 = ma1_20[idx];
  const currentE55 = ma8_55[idx];

  const isBuyTriggeredNow = buySignals[idx];
  const isSellTriggeredNow = sellSignals[idx];
  const isBuyTriggeredOnClose = buySignals[lastClosedIdx] === true;
  const isSellTriggeredOnClose = sellSignals[lastClosedIdx] === true;
  const latestClosedCandleTime = Number(candles[lastClosedIdx]?.t || 0);

  const isBuySignal = (lastSignalDirection === 1);
  const isSellSignal = (lastSignalDirection === -1);
  const isUptrend = (currentE20 > currentE134H && currentE20 > currentE55);

  const breakoutCandleOffset = (lastSignalDirection === 1 && lastBuySignalIdx >= 0) ? (idx - lastBuySignalIdx) : -1;
  const exitCandleOffset = (lastSignalDirection === -1 && lastSellSignalIdx >= 0) ? (idx - lastSellSignalIdx) : -1;

  // Derive active dynamic Stop Loss & Take Profit levels
  let returnEntry = 0;
  let returnSl = 0;
  let returnTp = 0;

  if (lastSignalDirection === 1 && lastBuySignalIdx >= 0) {
    returnEntry = closePrices[lastBuySignalIdx];
    returnSl = slSeries[lastBuySignalIdx];
    returnTp = tpSeries[lastBuySignalIdx];
  } else if (lastSignalDirection === -1 && lastSellSignalIdx >= 0) {
    returnEntry = closePrices[lastSellSignalIdx];
    returnSl = slSeries[lastSellSignalIdx];
    returnTp = tpSeries[lastSellSignalIdx];
  } else if (currentE20 > currentE134H) {
    returnEntry = closePrices[idx];
    returnSl = currentE134L;
    returnTp = returnEntry + (Math.max(returnEntry - returnSl, returnEntry * 0.001) * 2.0);
  } else if (currentE20 < currentE134L) {
    returnEntry = closePrices[idx];
    returnSl = currentE134H;
    returnTp = returnEntry - (Math.max(returnSl - returnEntry, returnEntry * 0.001) * 2.0);
  }

  // Proximity Calculation (0 - 100%)
  let proximityScore = 0;
  if (isBuySignal || currentE20 >= currentE134H) {
    proximityScore = 100;
  } else if (currentE20 <= currentE134L) {
    const bandHeight = Math.max(currentE134H - currentE134L, 1e-6);
    const distBelow = currentE134L - currentE20;
    const prevE20 = idx >= 1 ? ma1_20[idx - 1] : currentE20;
    const isMovingUp = currentE20 > prevE20;
    if (distBelow >= bandHeight * 2 || !isMovingUp) {
      proximityScore = Math.max(0, Math.min(25, Math.floor((bandHeight / (bandHeight + distBelow)) * 25)));
    } else {
      const prog = ((bandHeight * 2 - distBelow) / (bandHeight * 2)) * 50;
      proximityScore = Math.max(10, Math.min(49, Math.floor(prog)));
    }
  } else {
    // Inside 134 Green Band [r1Ema134Low, r1Ema134High]
    const bandHeight = Math.max(currentE134H - currentE134L, 1e-6);
    const insideProg = (currentE20 - currentE134L) / bandHeight;
    proximityScore = Math.max(50, Math.min(99, Math.floor(50 + insideProg * 49)));
  }

  const metrics = {
    r1Ema134High: currentE134H,
    r1Ema134Low: currentE134L,
    kryptMa1_20: currentE20,
    kryptMa8_55: currentE55,
    stopLoss: returnSl,
    takeProfit: returnTp,
    entryPrice: returnEntry,
    riskRewardRatio: 2.0,
    isUptrend,
    currentPositionState,
    breakoutCandleOffset,
    exitCandleOffset,
    proximityScore
  };

  return {
    isUptrend,
    isBuySignal,
    isSellSignal,
    isBuyTriggered: isBuyTriggeredNow,
    isSellTriggered: isSellTriggeredNow,
    isBuyTriggeredOnClose,
    isSellTriggeredOnClose,
    latestClosedCandleTime,
    breakoutCandleOffset,
    exitCandleOffset,
    stopLoss: returnSl,
    takeProfit: returnTp,
    entryPrice: returnEntry,
    riskRewardRatio: 2.0,
    proximityScore,
    currentPositionState,
    metrics
  };
}

// In-memory cache for active position dynamic risk parameters (SL/TP)
const activePositionsRiskCache = new Map();

// In-memory cache for already configured leverage & margin mode on Hyperliquid
// Key: `${masterAddress.toLowerCase()}_${coin.toUpperCase()}` -> { isCross, leverage, timestamp }
const configuredLeverageCache = new Map();

/**
 * Executes a 24/7 algorithmic strategy tick cycle for a specific user.
 */
async function executeBotTick(uid, db, config, botSecret) {
  const { agentPrivateKey, masterAddress } = botSecret;
  if (!masterAddress) {
    console.warn(`[${uid}] Missing masterAddress in botSecret. Skipping tick.`);
    return;
  }

  const writeStatusTargets = Array.from(new Set([
    uid,
    masterAddress,
    masterAddress ? masterAddress.toLowerCase() : null
  ])).filter(Boolean);

  const writeBotStatus = async (statusPayload, merge = true) => {
    for (const targetId of writeStatusTargets) {
      try {
        await db.doc(`users/${targetId}/bot/bot_status`).set(statusPayload, { merge });
      } catch (e) {}
    }
  };

  // 0. Synchronize recent Hyperliquid trade fills to Firestore users/{uid}/trade_history
  // Captures manual closes from web UI, SL/TP fills, and bot fills so Trade History is never out of sync.
  await syncUserFillsToFirestore(db, masterAddress, writeStatusTargets);

  // 1. Decoupled Market Activation States - Evaluated FIRST before any network calls
  const isFuturesExplicitlyDisabled = 
    config.is_futures_bot_active === false || 
    config.futures_active === false || 
    config.futures_config?.active === false ||
    config.futures_config?.is_futures_bot_active === false ||
    config.active === false;

  const isFuturesActive = !isFuturesExplicitlyDisabled && Boolean(
    config.is_futures_bot_active ||
    config.futures_active ||
    config.futures_config?.active ||
    config.futures_config?.is_futures_bot_active ||
    ((config.market_type === "PERP" || config.market_type === "FUTURES") && config.active)
  );

  const isSpotExplicitlyDisabled = 
    config.is_spot_bot_active === false || 
    config.spot_active === false || 
    config.spot_config?.active === false ||
    config.active === false;

  const isSpotActive = !isSpotExplicitlyDisabled && Boolean(
    config.is_spot_bot_active ||
    config.spot_active ||
    config.spot_config?.active ||
    (config.market_type === "SPOT" && config.active)
  );

  console.log(`[${uid}] ⚡ Bot Tick Evaluation: isSpotActive=${isSpotActive}, isFuturesActive=${isFuturesActive}`);

  if (!isSpotActive && !isFuturesActive) {
    console.log(`[${uid}] 🛑 HARD-FAIL GUARD: Both Spot and Futures bot engines are INACTIVE (Disabled). Skipping tick.`);
    await writeBotStatus({
      status: "IDLE",
      active: false,
      is_futures_bot_active: false,
      is_spot_bot_active: false,
      active_trades: 0,
      pnl_usd: 0.0,
      updated_at: Date.now()
    }, true);
    return;
  }

  // LIVE FIRESTORE FRESHNESS GUARD:
  // Directly verify live Firestore configuration to guarantee that a user toggle-off
  // immediately halts trading, even if an in-memory worker interval has not yet re-read state.
  if (db && masterAddress) {
    try {
      const targets = Array.from(new Set([masterAddress, masterAddress.toLowerCase(), uid])).filter(Boolean);
      for (const t of targets) {
        const snap = await db.doc(`users/${t}/bot/bot_config`).get();
        if (snap.exists) {
          const liveData = snap.data();
          if (liveData) {
            const liveFuturesDisabled = 
              liveData.is_futures_bot_active === false || 
              liveData.futures_active === false || 
              liveData.active === false ||
              liveData.futures_config?.active === false ||
              liveData.futures_config?.is_futures_bot_active === false;
            
            if (liveFuturesDisabled && isFuturesActive) {
              console.log(`[${uid}] 🛑 LIVE FIRESTORE GUARD: Live bot_config under users/${t}/bot/bot_config is DISABLED (active=${liveData.active}, futures=${liveData.is_futures_bot_active}). Aborting tick.`);
              return;
            }
          }
        }
      }
    } catch (e) {
      console.warn(`[${uid}] Live Firestore guard warning:`, e.message);
    }
  }

  console.log(`[${uid}] ⚡ Running Engine (Master Wallet: ${masterAddress}, Strategy: '${config.strategy || config.futures_strategy || '5m Dual-Ribbon'}', Alloc: ${config.allocation_pct || config.futures_allocation_pct || 25}%)...`);

  // 2. Fetch Perpetual Futures Clearinghouse (only when Futures is active, throttled/cached)
  let perpClearinghouse = null;
  let totalPnlUsd = 0.0;
  let activeTradesCount = 0;
  let accountValue = 0.0;

  if (isFuturesActive) {
    perpClearinghouse = await fetchUserClearinghouseState(masterAddress);
    if (perpClearinghouse && perpClearinghouse.marginSummary) {
      accountValue = parseFloat(perpClearinghouse.marginSummary.accountValue || 0);
      const assetPositions = perpClearinghouse.assetPositions || [];
      activeTradesCount = assetPositions.length;

      totalPnlUsd = assetPositions.reduce((sum, item) => {
        const pos = item.position;
        return sum + parseFloat(pos.unrealizedPnl || 0);
      }, 0.0);
      console.log(`[${uid}] 💰 Hyperliquid Futures Account (${masterAddress}): Balance/Value = $${accountValue.toFixed(2)} USDC, Active Positions: ${activeTradesCount}, Unrealized PnL: $${totalPnlUsd.toFixed(2)}`);
    }
  }

  // 3. ZERO-OUT SPOT CALLS: Fetch Spot Clearinghouse & Spot Markets ONLY IF Spot is Active!
  let spotClearinghouse = null;
  let spotMarkets = [];
  const spotBalances = new Map();
  let usdcBalance = 0.0;
  const assetIndexMap = new Map();
  const szDecimalsMap = new Map();
  const spotMarketMap = new Map();
  const FALLBACK_UNIVERSE = ["BTC", "ETH", "SOL", "HYPE", "PURR", "HFUN", "SUI", "PEPE", "DOGE", "AVAX", "LINK", "XRP"];
  Object.entries(FALLBACK_SZ_DECIMALS).forEach(([c, dec]) => szDecimalsMap.set(c, dec));

  if (isSpotActive) {
    spotClearinghouse = await fetchSpotClearinghouseState(masterAddress);
    spotMarkets = await fetchSpotMetaAndCtxs();

    if (spotClearinghouse && spotClearinghouse.balances) {
      spotClearinghouse.balances.forEach(b => {
        const avail = parseFloat(b.total || 0) - parseFloat(b.hold || 0);
        spotBalances.set(b.coin, avail);
        if (b.coin === "USDC") usdcBalance = avail;
      });
    }

    if (spotMarkets && spotMarkets.length > 0) {
      spotMarkets.forEach(m => {
        const keys = [
          m.coin.toUpperCase(),
          m.displayName.toUpperCase(),
          m.displayName.replace("/", "").toUpperCase(),
          m.exchangeCoin ? m.exchangeCoin.toUpperCase() : null,
          `@${m.universeIndex}`,
          m.universeIndex.toString()
        ].filter(Boolean);

        if (m.coin.startsWith("U")) {
          const stripped = m.coin.substring(1).toUpperCase();
          keys.push(stripped);
          keys.push(`${stripped}/USDC`);
          keys.push(`${stripped}USDC`);
        }

        keys.forEach(k => {
          spotMarketMap.set(k, m);
          assetIndexMap.set(k, m.l1AssetIndex);
          if (m.szDecimals !== undefined) {
            szDecimalsMap.set(k, m.szDecimals);
          }
        });
      });
    }
  }

  assetIndexMap.set("PURR", 10000);
  assetIndexMap.set("HFUN", 10001);
  assetIndexMap.set("HYPE", 10107);
  assetIndexMap.set("UBTC", 10142);
  assetIndexMap.set("BTC", 10142);
  assetIndexMap.set("HPENGU", 10184);
  assetIndexMap.set("UENA", 10206);
  assetIndexMap.set("ENA", 10206);
  assetIndexMap.set("UAVAX", 10306);
  assetIndexMap.set("AVAX", 10306);

  const availableCoins = (spotMarkets && spotMarkets.length > 0)
    ? spotMarkets.map(m => m.coin)
    : FALLBACK_UNIVERSE;

  const targetCoin = config.target_coin || "BTC";

  // Asset-Level Trading Permission: Check user-enabled assets
  const rawEnabled = config.enabled_assets;
  const enabledAssets = Array.isArray(rawEnabled)
    ? rawEnabled.map(a => a.toString().toUpperCase().trim())
    : (typeof rawEnabled === "string" && rawEnabled.length > 0 ? [rawEnabled.toUpperCase().trim()] : []);

  const cleanCoinSymbol = (coin) => {
    if (!coin) return "";
    return coin.toString().toUpperCase().trim()
      .replace("/USDC", "")
      .replace("/USDT", "")
      .replace("USDC", "")
      .replace("USDT", "")
      .replace("/", "");
  };

  // Strictly lock to user-enabled whitelisted assets only - eliminates 429 rate limits!
  let whitelistedAssets = [];
  if (enabledAssets && enabledAssets.length > 0) {
    whitelistedAssets = enabledAssets.map(cleanCoinSymbol).filter(Boolean);
  }
  if (whitelistedAssets.length === 0 && targetCoin) {
    const clnTarget = cleanCoinSymbol(targetCoin);
    if (clnTarget) whitelistedAssets.push(clnTarget);
  }
  if (whitelistedAssets.length === 0) {
    whitelistedAssets = ["BTC"];
  }
  whitelistedAssets = Array.from(new Set(whitelistedAssets));

  const isAssetAuthorizedForBuy = (coin) => {
    const cleanCoin = cleanCoinSymbol(coin);

    // 1. If target_coin is specified, it is always authorized for auto-trading
    const targetClean = cleanCoinSymbol(targetCoin);
    if (targetClean && (
      cleanCoin === targetClean ||
      cleanCoin === `U${targetClean}` ||
      `U${cleanCoin}` === targetClean ||
      cleanCoin.replace(/^U/, "") === targetClean.replace(/^U/, "")
    )) {
      return true;
    }

    // 2. If enabled_assets are configured, check if this coin is toggled ON
    if (enabledAssets && enabledAssets.length > 0) {
      return enabledAssets.some(enabledRaw => {
        const enabled = cleanCoinSymbol(enabledRaw);
        return (
          enabled === cleanCoin ||
          enabled === `U${cleanCoin}` ||
          cleanCoin === `U${enabled}` ||
          cleanCoin.replace(/^U/, "") === enabled.replace(/^U/, "")
        );
      });
    }

    // 3. Fallback: if no assets explicitly enabled and no target_coin, authorize default target ("BTC")
    return cleanCoin === "BTC" || cleanCoin === "UBTC";
  };

  const futuresConfig = config.futures_config || config;
  const futuresStrategy = futuresConfig.strategy || config.strategy || "30m EMA High/Low";
  const rawFuturesStrats = futuresConfig.strategies || config.futures_strategies || config.strategies || futuresStrategy;
  const futuresStrategyList = Array.isArray(rawFuturesStrats)
    ? rawFuturesStrats
    : (typeof rawFuturesStrats === "string" ? rawFuturesStrats.split(",").map(s => s.trim()) : ["30m EMA High/Low"]);
  const futuresAllocPct = futuresConfig.allocation_pct || config.allocation_pct || 25;
  const configuredLeverage = Math.max(1, Math.min(50, parseInt(futuresConfig.leverage || config.leverage) || 10));
  const marginMode = (futuresConfig.margin_mode || config.margin_mode || (config.is_isolated !== false ? "ISOLATED" : "CROSS")).toUpperCase();
  const isIsolated = marginMode === "ISOLATED" || futuresConfig.is_isolated === true || config.is_isolated === true;
  const positionDirection = (futuresConfig.position_direction || config.position_direction || "LONG").toUpperCase();

  // =========================================================================
  // DUAL-MARKET ENGINE: 1. PERPETUAL FUTURES MODE
  // =========================================================================
  if (isFuturesActive) {
    console.log(`[${uid}] ⚡ Running 30m Perpetual Futures Bot Engine '${futuresStrategyList.join(" + ")}' (Leverage: ${configuredLeverage}x, Margin: ${marginMode}, Direction: ${positionDirection}, Alloc: ${futuresAllocPct}%)...`);

    const perpMarkets = await fetchPerpMetaAndCtxs();
    const perpMarketMap = new Map();
    if (perpMarkets && perpMarkets.length > 0) {
      perpMarkets.forEach(m => {
        perpMarketMap.set(m.coin.toUpperCase(), m);
        perpMarketMap.set(`${m.coin.toUpperCase()}/USDC`, m);
        perpMarketMap.set(`${m.coin.toUpperCase()}USDC`, m);
      });
    }

    const perpAvailableCoins = (perpMarkets && perpMarkets.length > 0)
      ? perpMarkets.map(m => m.coin)
      : ["BTC", "ETH", "SOL", "HYPE", "AVAX", "SUI", "DOGE", "LINK", "XRP", "ARB", "OP"];

    const rawPerpBal = parseFloat(perpClearinghouse?.withdrawable || perpClearinghouse?.marginSummary?.accountValue || 0.0);
    // Purged userAbstraction polling on tick loop to conserve rate limit tokens; use spot USDC only if perp ledger is empty
    const isUnifiedAccount = (rawPerpBal <= 0.01 && usdcBalance > 0);
    const perpBalance = isUnifiedAccount ? usdcBalance : rawPerpBal;
    if (isUnifiedAccount) {
      console.log(`[${uid}] ⚡ Unified Account Active: Pooled USDC margin for Futures = $${perpBalance.toFixed(2)} (Spot: $${usdcBalance.toFixed(2)}, Perp ledger: $${rawPerpBal.toFixed(2)})`);
    }
    const pnlPct = accountValue > 0 ? (totalPnlUsd / accountValue) * 100.0 : 0.0;

    // STRICT SINGLE/WHITELISTED ASSET LOCK:
    // Only scan the user-selected/enabled whitelisted asset(s).
    // Completely bypass scanning all 239 pairs in the background to eliminate 429 rate limits!
    const scanQueue = whitelistedAssets;

    const isDualRibbonActive = futuresStrategyList.some(s =>
      s.toLowerCase().includes("dual-ribbon") ||
      s.toLowerCase().includes("combined ribbon") ||
      s.toLowerCase().includes("krypt") ||
      s.toLowerCase().includes("5m ribbon") ||
      s.toLowerCase().includes("dual ribbon")
    );
    const isChochActive = !isDualRibbonActive && futuresStrategyList.some(s => 
      s.toLowerCase().includes("choch") || s.toLowerCase().includes("fractal") || s.toLowerCase().includes("ict")
    );
    const futuresInterval = isDualRibbonActive ? "5m" : "30m";
    const minCandlesReq = isDualRibbonActive ? 136 : (isChochActive ? 15 : 35);

    const isEmaStrategyActive = isDualRibbonActive || isChochActive || futuresStrategyList.some(s => 
      s.includes("EMA") || s.includes("Trend") || s.includes("DCA") || s.includes("Grid")
    ) || futuresStrategyList.length === 0;

    let validResults = [];

    if (isEmaStrategyActive) {
      console.log(`[${uid}] 🎯 Futures whitelisted asset locked: [${scanQueue.join(", ")}] on ${futuresInterval} (Strategy: ${futuresStrategyList.join("+")}, Margin: ${marginMode}). Paced sequential evaluation.`);

      // Deliberate pacing delay: ensures account state polling and market candle fetching are separated by >= 1000ms
      await sleep(1000);

      const scanResults = [];
      for (let i = 0; i < scanQueue.length; i++) {
        if (i > 0) {
          // Mandatory 1-second delay between whitelisted asset fetches to pace API traffic
          await sleep(1000);
        }
        const coin = scanQueue[i];
        const cleanCoin = cleanCoinSymbol(coin);

        // Graceful Rate Limit Handling: Check 15-second cooldown
        if (isSymbolCoolingDown(cleanCoin)) {
          console.warn(`[${uid}] ⏳ Symbol ${cleanCoin} is in 429 rate limit cooldown (15s backoff). Skipping tick.`);
          continue;
        }

        try {
          const market = perpMarketMap.get(cleanCoin);
          const querySymbol = market ? market.coin : cleanCoin;

          // Strategy execution engine queries targeted candle timeframe for this single symbol
          const candleResult = await fetchCandleSnapshot(querySymbol, futuresInterval, 300);
          if (candleResult?.rateLimited) {
            recordSymbol429(cleanCoin);
            continue;
          }

          const candles = Array.isArray(candleResult) ? candleResult : (candleResult?.candles || []);
          if (!candles || candles.length < minCandlesReq) continue;

          // Strictly derive current price from the latest candle close of the target symbol (eliminates allMids request)
          const latestCandle = candles[candles.length - 1];
          const coinPrice = parseFloat(latestCandle.close || (market ? market.markPrice : 0) || 0);
          if (coinPrice <= 0) continue;

          const signalResult = isDualRibbonActive
            ? await evaluateDualRibbonStrategy(cleanCoin, candles)
            : (isChochActive
              ? await evaluateIctMarketStructure(cleanCoin, candles)
              : await evaluateEmaRibbonStrategy(cleanCoin, candles));

          scanResults.push({ coin: cleanCoin, coinPrice, market, ...signalResult });
        } catch (e) {
          console.error(`[${uid}] Error evaluating perp ${coin}:`, e.message);
        }
      }

      validResults = scanResults.filter(Boolean);

      // Map existing perpetual positions
      const currentPositionsMap = new Map();
      if (perpClearinghouse && perpClearinghouse.assetPositions) {
        perpClearinghouse.assetPositions.forEach(item => {
          if (item.position && item.position.coin) {
            currentPositionsMap.set(item.position.coin.toUpperCase(), item.position);
          }
        });
      }

      // 1. Process EXITS for active positions
      for (const res of validResults) {
        const { coin, coinPrice, isSellSignal, isBuySignal, market, stopLoss, takeProfit } = res;
        const currentPos = currentPositionsMap.get(coin);
        if (!currentPos) continue;

        const currentSzi = parseFloat(currentPos.szi || 0);
        const isCurrentlyLong = currentSzi > 0.0001;
        const isCurrentlyShort = currentSzi < -0.0001;
        const assetIdx = market ? market.assetIndex : perpAvailableCoins.indexOf(coin);
        const szDec = market?.szDecimals !== undefined ? market.szDecimals : 2;

        if (assetIdx < 0) continue;

        // Check SL/TP triggers for Dual-Ribbon Risk Management
        let isDualRibbonLongExit = false;
        let isDualRibbonShortExit = false;
        let exitReason = "";

        const cachedRisk = activePositionsRiskCache.get(`${masterAddress}_${coin}`);
        const effectiveSl = cachedRisk?.stopLoss || stopLoss;
        const effectiveTp = cachedRisk?.takeProfit || takeProfit;

        // Ensure real native on-chain Hyperliquid TP/SL trigger orders are active for this open position
        const tpslKey = `${masterAddress.toLowerCase()}_${coin.toUpperCase()}`;
        if (!hasDispatchedTpslMap.has(tpslKey) && (effectiveSl > 0 || effectiveTp > 0) && isFuturesActive) {
          hasDispatchedTpslMap.set(tpslKey, Date.now());
          const activeSz = formatSizeForHyperliquid(Math.abs(currentSzi), szDec);
          placeAgentPositionTpslOrders({
            agentPrivateKey,
            masterAddress,
            assetIndex: assetIdx,
            isLong: isCurrentlyLong,
            sz: activeSz,
            stopLoss: effectiveSl,
            takeProfit: effectiveTp,
            szDec
          }).then(res => {
            console.log(`[${uid}] 🛡️ Native on-chain TP/SL active for ${coin}:`, res?.data?.response || res?.data || "ok");
          }).catch(err => {
            console.warn(`[${uid}] Native TP/SL check note for ${coin}:`, err.message);
          });
        }

        if (isDualRibbonActive && effectiveSl > 0 && effectiveTp > 0) {
          if (isCurrentlyLong) {
            if (coinPrice <= effectiveSl) {
              isDualRibbonLongExit = true;
              exitReason = `Dual-Ribbon Stop Loss Triggered (${coinPrice} <= SL: ${effectiveSl})`;
            } else if (coinPrice >= effectiveTp) {
              isDualRibbonLongExit = true;
              exitReason = `Dual-Ribbon Take Profit (1:2 RRR) Triggered (${coinPrice} >= TP: ${effectiveTp})`;
            }
          } else if (isCurrentlyShort) {
            if (coinPrice >= effectiveSl) {
              isDualRibbonShortExit = true;
              exitReason = `Dual-Ribbon Stop Loss Triggered (${coinPrice} >= SL: ${effectiveSl})`;
            } else if (coinPrice <= effectiveTp) {
              isDualRibbonShortExit = true;
              exitReason = `Dual-Ribbon Take Profit (1:2 RRR) Triggered (${coinPrice} <= TP: ${effectiveTp})`;
            }
          }
        }

        // Two-Way Futures: Exit Long on SELL breakdown or SL/TP trigger
        if (isCurrentlyLong && (isSellSignal || isDualRibbonLongExit) && isFuturesActive) {
          const closeSz = formatSizeForHyperliquid(Math.abs(currentSzi), szDec);
          const limitPx = formatPriceForHyperliquid(coinPrice * 0.98, szDec, false); // 2% market slippage buffer
          const reasonStr = exitReason || "30m/5m BEARISH SELL breakdown";
          console.log(`[${uid}] 🔻 TWO-WAY FUTURES: Closing LONG on ${coin} (${closeSz} size, assetIndex: ${assetIdx}) @ limit ${limitPx} on ${reasonStr}`);
          try {
            const exitResult = await placeAgentOrder({
              agentPrivateKey,
              masterAddress,
              assetIndex: assetIdx,
              isBuy: false,
              limitPx,
              sz: closeSz,
              reduceOnly: true
            });
            console.log(`[${uid}] ✅ Futures LONG closed for ${coin}:`, exitResult?.response?.data?.statuses?.[0] || exitResult);
            currentPositionsMap.delete(coin); // Mark position deleted to allow immediate two-way SHORT reversal
            invalidateClearinghouseCache(masterAddress);

            // Archive closed trade record to Firestore users/{walletAddress}/trade_history
            const entryPx = parseFloat(currentPos.entryPx || coinPrice);
            const closeSizeCrypto = Math.abs(currentSzi);
            const posSizeUsd = closeSizeCrypto * entryPx;
            const pnlUsd = (coinPrice - entryPx) * closeSizeCrypto;
            const marginUsed = parseFloat(currentPos.marginUsed || (posSizeUsd / configuredLeverage));
            const pnlPctCalc = marginUsed > 0 ? (pnlUsd / marginUsed) * 100.0 : 0.0;
            const entryTime = cachedRisk?.timestamp || (Date.now() - 300000);
            const tradeId = `trade_${coin}_${Date.now()}`;

            const closedTradeRecord = {
              id: tradeId,
              symbol: `${coin}/USDC`,
              coin: coin,
              isLong: true,
              side: "LONG",
              leverage: configuredLeverage,
              margin: marginUsed,
              positionSizeUsdc: posSizeUsd,
              positionSizeCrypto: closeSizeCrypto,
              entryPrice: entryPx,
              exitPrice: coinPrice,
              realizedPnl: pnlUsd,
              realizedPnlPercent: pnlPctCalc,
              closeReason: reasonStr,
              duration: Date.now() - entryTime,
              timestamp: Date.now(),
              isDemo: false,
              walletAddress: masterAddress
            };

            for (const targetId of writeStatusTargets) {
              try {
                await db.collection(`users/${targetId}/trade_history`).doc(tradeId).set(closedTradeRecord);
              } catch (archErr) {
                console.error(`[${uid}] Failed to archive trade_history for ${targetId}:`, archErr.message);
              }
            }
            activePositionsRiskCache.delete(`${masterAddress}_${coin}`);
            hasDispatchedTpslMap.delete(tpslKey);
          } catch (err) {
            console.error(`[${uid}] Failed to close LONG on ${coin}:`, err.message);
          }
        }

        // Two-Way Futures: Exit Short on BUY breakout or SL/TP trigger
        if (isCurrentlyShort && (isBuySignal || isDualRibbonShortExit) && isFuturesActive) {
          const closeSz = formatSizeForHyperliquid(Math.abs(currentSzi), szDec);
          const limitPx = formatPriceForHyperliquid(coinPrice * 1.02, szDec, false); // 2% market slippage buffer
          const reasonStr = exitReason || "30m/5m BULLISH BUY breakout";
          console.log(`[${uid}] 🔺 TWO-WAY FUTURES: Closing SHORT on ${coin} (${closeSz} size, assetIndex: ${assetIdx}) @ limit ${limitPx} on ${reasonStr}`);
          try {
            const exitResult = await placeAgentOrder({
              agentPrivateKey,
              masterAddress,
              assetIndex: assetIdx,
              isBuy: true,
              limitPx,
              sz: closeSz,
              reduceOnly: true
            });
            console.log(`[${uid}] ✅ Futures SHORT closed for ${coin}:`, exitResult?.response?.data?.statuses?.[0] || exitResult);
            currentPositionsMap.delete(coin); // Mark position deleted to allow immediate two-way LONG reversal
            invalidateClearinghouseCache(masterAddress);

            // Archive closed trade record to Firestore users/{walletAddress}/trade_history
            const entryPx = parseFloat(currentPos.entryPx || coinPrice);
            const closeSizeCrypto = Math.abs(currentSzi);
            const posSizeUsd = closeSizeCrypto * entryPx;
            const pnlUsd = (entryPx - coinPrice) * closeSizeCrypto;
            const marginUsed = parseFloat(currentPos.marginUsed || (posSizeUsd / configuredLeverage));
            const pnlPctCalc = marginUsed > 0 ? (pnlUsd / marginUsed) * 100.0 : 0.0;
            const entryTime = cachedRisk?.timestamp || (Date.now() - 300000);
            const tradeId = `trade_${coin}_${Date.now()}`;

            const closedTradeRecord = {
              id: tradeId,
              symbol: `${coin}/USDC`,
              coin: coin,
              isLong: false,
              side: "SHORT",
              leverage: configuredLeverage,
              margin: marginUsed,
              positionSizeUsdc: posSizeUsd,
              positionSizeCrypto: closeSizeCrypto,
              entryPrice: entryPx,
              exitPrice: coinPrice,
              realizedPnl: pnlUsd,
              realizedPnlPercent: pnlPctCalc,
              closeReason: reasonStr,
              duration: Date.now() - entryTime,
              timestamp: Date.now(),
              isDemo: false,
              walletAddress: masterAddress
            };

            for (const targetId of writeStatusTargets) {
              try {
                await db.collection(`users/${targetId}/trade_history`).doc(tradeId).set(closedTradeRecord);
              } catch (archErr) {
                console.error(`[${uid}] Failed to archive trade_history for ${targetId}:`, archErr.message);
              }
            }
            activePositionsRiskCache.delete(`${masterAddress}_${coin}`);
            hasDispatchedTpslMap.delete(tpslKey);
          } catch (err) {
            console.error(`[${uid}] Failed to close SHORT on ${coin}:`, err.message);
          }
        }
      }

      // 2. Process ENTRIES (Two-Way Futures: Open Long on Bullish, Open Short on Bearish)
      const authorizedResults = validResults.filter(r => isAssetAuthorizedForBuy(r.coin));
      authorizedResults.sort((a, b) => (b.proximityScore || 0) - (a.proximityScore || 0));

      const bestEntrySignal = authorizedResults.find(r => {
        const currentPos = currentPositionsMap.get(r.coin);
        const currentSzi = currentPos ? parseFloat(currentPos.szi || 0) : 0;
        if (Math.abs(currentSzi) > 0.0001) return false; // Already has open position

        // 1. Must not be in 30s rejection cooldown
        if (isOrderInRejectionCooldown(r.coin)) return false;

        // 2. Strict Candle Close & Signal Transition check:
        // Entry requires a fresh signal confirmation on the latest completed (closed) candle.
        // We reject stale persistent state where the crossover happened bars ago.
        const closedTime = r.latestClosedCandleTime || 0;
        const lastTriggeredTime = lastTriggeredCandleTimeBySymbol.get(r.coin) || 0;
        const isNewClosedCandle = closedTime > lastTriggeredTime;

        // Fresh trigger check: either triggered on the latest closed candle, or breakout offset <= 1
        const isFreshBuy = (r.isBuyTriggeredOnClose || (r.breakoutCandleOffset !== undefined && r.breakoutCandleOffset >= 0 && r.breakoutCandleOffset <= 1));
        const isFreshSell = (r.isSellTriggeredOnClose || (r.exitCandleOffset !== undefined && r.exitCandleOffset >= 0 && r.exitCandleOffset <= 1));

        if (isDualRibbonActive) {
          if (!isNewClosedCandle) return false;
          return isFreshBuy || isFreshSell;
        }

        if (!isNewClosedCandle) return false;
        const isBuyOpportunity = isFreshBuy || (r.proximityScore >= 100 && r.isCurrentAbove && r.breakoutCandleOffset <= 1);
        const isSellOpportunity = isFreshSell || (r.proximityScore <= 0 && !r.isCurrentAbove && r.exitCandleOffset <= 1);
        return isBuyOpportunity || isSellOpportunity;
      });

      if (bestEntrySignal && perpBalance >= 1.0 && isFuturesActive) {
        const { coin, coinPrice, proximityScore, isBuySignal, isCurrentAbove, market } = bestEntrySignal;
        const isEnteringLong = (isBuySignal || (proximityScore >= 100 && isCurrentAbove));

        const targetSide = isEnteringLong ? "BUY/LONG" : "SELL/SHORT";
        const isOrderBuy = isEnteringLong;
        const assetIdx = market ? market.assetIndex : perpAvailableCoins.indexOf(coin);
        const szDec = market?.szDecimals !== undefined ? market.szDecimals : 2;

        if (assetIdx >= 0) {
          // LEVERAGE FALLBACK SAFETY:
          // Check asset's maximum supported leverage via Hyperliquid metadata API before placing order
          const assetMaxLeverage = market?.maxLeverage !== undefined ? parseInt(market.maxLeverage) : 20;
          let effectiveLeverage = configuredLeverage;

          if (configuredLeverage > assetMaxLeverage) {
            effectiveLeverage = assetMaxLeverage;
            console.log(`[${uid}] ⚠️ LEVERAGE FALLBACK SAFETY: Asset ${coin} max supported leverage is ${assetMaxLeverage}x (requested: ${configuredLeverage}x). Safely falling back to exchange maximum allowable ${effectiveLeverage}x.`);
          } else {
            console.log(`[${uid}] 🎯 Asset ${coin} supports requested leverage ${configuredLeverage}x (exchange max: ${assetMaxLeverage}x). Applying ${effectiveLeverage}x.`);
          }

          // STRICT RESPECT FOR MARGIN MODE (Isolated vs. Cross) & Dynamic Leverage Fallback
          let marginConfiguredOk = false;
          const cacheKey = `${masterAddress.toLowerCase()}_${coin.toUpperCase()}`;
          const cachedLev = configuredLeverageCache.get(cacheKey);

          // Check if clearinghouse state or in-memory cache already has this asset at the requested isolated/cross & leverage
          const existingPos = perpClearinghouse?.assetPositions?.find(ap => ap.position?.coin?.toUpperCase() === coin.toUpperCase())?.position;
          const currentIsCross = existingPos?.leverage?.type === "cross";
          const currentLeverage = existingPos?.leverage?.value !== undefined ? parseInt(existingPos.leverage.value) : null;

          const isAlreadyConfiguredOnChain = existingPos && (currentIsCross === !isIsolated) && (currentLeverage === effectiveLeverage);
          const isAlreadyCached = cachedLev && (cachedLev.isCross === !isIsolated) && (cachedLev.leverage === effectiveLeverage);

          if (isAlreadyConfiguredOnChain || isAlreadyCached) {
            console.log(`[${uid}] ⚡ Leverage & Margin Mode already set to ${effectiveLeverage}x (${marginMode}) for ${coin}. Skipping redundant updateLeverage call.`);
            marginConfiguredOk = true;
          } else {
            try {
              console.log(`[${uid}] 🛡️ Enforcing Margin Mode: ${marginMode} (${effectiveLeverage}x) on ${coin} (assetIndex: ${assetIdx})`);
              const levResult = await updateAgentLeverage({
                agentPrivateKey,
                masterAddress,
                assetIndex: assetIdx,
                isCross: !isIsolated,
                leverage: effectiveLeverage
              });
              marginConfiguredOk = !levResult?.response?.data?.error && levResult?.status !== "err";
              if (marginConfiguredOk) {
                invalidateClearinghouseCache(masterAddress);
                configuredLeverageCache.set(cacheKey, { isCross: !isIsolated, leverage: effectiveLeverage, timestamp: Date.now() });
              }
            } catch (levErr) {
              const errStr = (levErr.response?.data?.error || levErr.message || "").toLowerCase();
              if (errStr.includes("leverage") && effectiveLeverage > 1) {
                const fallbackLev = Math.max(1, Math.min(effectiveLeverage - 1, assetMaxLeverage, 5));
                console.warn(`[${uid}] ⚠️ Leverage rejection on ${coin} (${effectiveLeverage}x): "${errStr}". Attempting automated fallback to ${fallbackLev}x...`);
                try {
                  const fallbackResult = await updateAgentLeverage({
                    agentPrivateKey,
                    masterAddress,
                    assetIndex: assetIdx,
                    isCross: !isIsolated,
                    leverage: fallbackLev
                  });
                  effectiveLeverage = fallbackLev;
                  marginConfiguredOk = !fallbackResult?.response?.data?.error && fallbackResult?.status !== "err";
                  if (marginConfiguredOk) {
                    invalidateClearinghouseCache(masterAddress);
                    configuredLeverageCache.set(cacheKey, { isCross: !isIsolated, leverage: effectiveLeverage, timestamp: Date.now() });
                    console.log(`[${uid}] ✅ Leverage fallback succeeded: Applied ${effectiveLeverage}x on ${coin}`);
                  }
                } catch (e2) {
                  if (isIsolated) {
                    console.error(`[${uid}] ❌ STRICT MARGIN PROTECTION: Isolated margin configuration failed for ${coin}: "${e2.message}". Order aborted.`);
                    marginConfiguredOk = false;
                  } else {
                    marginConfiguredOk = true;
                  }
                }
              } else if (isIsolated) {
                console.error(`[${uid}] ❌ STRICT MARGIN PROTECTION: Failed to configure ISOLATED margin mode on Hyperliquid for ${coin}: "${levErr.message}". Order aborted to prevent cross-margin exposure.`);
                marginConfiguredOk = false;
              } else {
                marginConfiguredOk = true;
              }
            }
          }

          if (isIsolated && !marginConfiguredOk) {
            console.error(`[${uid}] ⛔ STRICT ISOLATION GUARD: Aborting ${targetSide} on ${coin}. Bot is strictly configured for Isolated margin and will NEVER place a Cross-margin order.`);
          } else {
            // Sizing with safe margin buffer for exchange taker fees and liquidation cushion (max 95% allocation)
            const SAFE_MARGIN_RATIO = 0.95;
            const effectiveAllocPct = Math.min(futuresAllocPct || 25, 95) / 100.0;
            const marginUsd = perpBalance * effectiveAllocPct * SAFE_MARGIN_RATIO;
            const notionalUsd = marginUsd * effectiveLeverage;
            const rawSize = notionalUsd / coinPrice;
            let formattedSz = formatSizeForHyperliquid(rawSize, szDec);

            if (parseFloat(formattedSz) <= 0 && szDec > 0) {
              formattedSz = Math.pow(10, -szDec).toFixed(szDec);
            }

            const limitPx = isOrderBuy
              ? formatPriceForHyperliquid(coinPrice * 1.02, szDec, false)
              : formatPriceForHyperliquid(coinPrice * 0.98, szDec, false);

            if (parseFloat(formattedSz) > 0) {
              // STRICT PRE-ORDER ASSERTION: Verify futures bot is actively enabled
              if (!isFuturesActive) {
                console.error(`[${uid}] ⛔ CRITICAL SECURITY GUARD: Attempted to place Futures ${targetSide} on ${coin} while isFuturesActive=false! Order BLOCKED.`);
                return;
              }

              // Live Firestore check immediately before order signature/dispatch
              if (db && masterAddress) {
                try {
                  const checkRef = db.doc(`users/${masterAddress}/bot/bot_config`);
                  const checkSnap = await checkRef.get();
                  if (checkSnap.exists) {
                    const d = checkSnap.data();
                    if (d && (d.is_futures_bot_active === false || d.futures_active === false || d.active === false)) {
                      console.error(`[${uid}] ⛔ CRITICAL PRE-ORDER GUARD: Live Firestore shows Futures is DISABLED for ${masterAddress}! Order BLOCKED.`);
                      return;
                    }
                  }
                } catch (_) {}
              }

              console.log(`[${uid}] 🚀 TWO-WAY FUTURES ENGINE: Executing ${marginMode} ${effectiveLeverage}x ${targetSide} on ${coin} (Size: ${formattedSz}, Notional: ~$${notionalUsd.toFixed(2)} USD, Margin: ~$${marginUsd.toFixed(2)} USDC @ limit ${limitPx})...`);

              try {
                const orderResult = await placeAgentOrder({
                  agentPrivateKey,
                  masterAddress,
                  assetIndex: assetIdx,
                  isBuy: isOrderBuy,
                  limitPx,
                  sz: formattedSz,
                  reduceOnly: false
                });

                const statusEntry = orderResult?.response?.data?.statuses?.[0];
                if (statusEntry?.error) {
                  console.error(`[${uid}] ❌ Futures ${targetSide} REJECTED by Hyperliquid: "${statusEntry.error}"`);
                  recordOrderRejection(coin, 30000);
                  await writeBotStatus({
                    last_order_result: {
                      timestamp: Date.now(),
                      coin,
                      side: isOrderBuy ? "LONG" : "SHORT",
                      market: "PERP",
                      margin_mode: marginMode,
                      leverage: effectiveLeverage,
                      requested_leverage: configuredLeverage,
                      max_allowed_leverage: assetMaxLeverage,
                      status: "REJECTED",
                      error: statusEntry.error,
                      attempted_size: formattedSz,
                      attempted_price: limitPx,
                      asset_index: assetIdx
                    }
                  }, true);
                } else {
                  console.log(`[${uid}] ✅ Futures ${targetSide} order submitted successfully on Hyperliquid for ${coin}`);
                  invalidateClearinghouseCache(masterAddress);
                  lastTriggeredCandleTimeBySymbol.set(coin, bestEntrySignal.latestClosedCandleTime || Date.now());

                  // Re-fetch clearinghouse state so active_positions telemetry reflects the fresh fill immediately
                  try {
                    await sleep(1000);
                    const freshPerpState = await fetchUserClearinghouseState(masterAddress);
                    if (freshPerpState) {
                      perpClearinghouse = freshPerpState;
                    }
                  } catch (_) {}

                  activePositionsRiskCache.set(`${masterAddress}_${coin}`, {
                    stopLoss: bestEntrySignal.stopLoss || 0,
                    takeProfit: bestEntrySignal.takeProfit || 0,
                    entryPrice: coinPrice,
                    isLong: isOrderBuy,
                    timestamp: Date.now(),
                    leverage: effectiveLeverage,
                    margin: marginUsd
                  });

                  // Dispatch real native on-chain Hyperliquid TP/SL trigger orders immediately upon fill
                  if (bestEntrySignal.stopLoss > 0 || bestEntrySignal.takeProfit > 0) {
                    try {
                      console.log(`[${uid}] 🛡️ Dispatching native on-chain Hyperliquid TP/SL trigger orders for ${coin}...`);
                      const tpslResult = await placeAgentPositionTpslOrders({
                        agentPrivateKey,
                        masterAddress,
                        assetIndex: assetIdx,
                        isLong: isOrderBuy,
                        sz: formattedSz,
                        stopLoss: bestEntrySignal.stopLoss || 0,
                        takeProfit: bestEntrySignal.takeProfit || 0,
                        szDec
                      });
                      hasDispatchedTpslMap.set(`${masterAddress.toLowerCase()}_${coin.toUpperCase()}`, Date.now());
                      console.log(`[${uid}] ✅ Native TP/SL trigger orders confirmed on Hyperliquid for ${coin}:`, tpslResult?.data?.response || tpslResult?.data || "ok");
                    } catch (tpSlErr) {
                      console.warn(`[${uid}] ⚠️ Native TP/SL trigger order note for ${coin}:`, tpSlErr.message);
                    }
                  }
                  await writeBotStatus({
                    last_order_result: {
                      timestamp: Date.now(),
                      coin,
                      side: isOrderBuy ? "LONG" : "SHORT",
                      market: "PERP",
                      margin_mode: marginMode,
                      leverage: effectiveLeverage,
                      requested_leverage: configuredLeverage,
                      max_allowed_leverage: assetMaxLeverage,
                      status: "SUCCESS",
                      size: formattedSz,
                      price: limitPx,
                      stop_loss: bestEntrySignal.stopLoss || 0,
                      take_profit: bestEntrySignal.takeProfit || 0,
                      risk_reward_ratio: bestEntrySignal.riskRewardRatio || 2.0,
                      asset_index: assetIdx
                    }
                  }, true);
                }
              } catch (err) {
                console.error(`[${uid}] Futures ${targetSide} Execution Failed for ${coin}:`, err.message);
                recordOrderRejection(coin, 30000);
              }
            }
          }
        }
      }
    }

    // Build active positions telemetry with explicit dynamic TP/SL levels
    const activePositionsList = [];
    if (perpClearinghouse && perpClearinghouse.assetPositions) {
      perpClearinghouse.assetPositions.forEach(item => {
        const p = item.position;
        if (p && p.coin) {
          const posCoin = p.coin.toUpperCase();
          const posSzi = parseFloat(p.szi || 0);
          if (Math.abs(posSzi) > 0.0001) {
            const isLong = posSzi > 0;
            const entryPx = parseFloat(p.entryPx || 0);
            const marginUsed = parseFloat(p.marginUsed || 0);
            const lev = typeof p.leverage === "object" ? parseInt(p.leverage.value || configuredLeverage) : parseInt(p.leverage || configuredLeverage);
            const posSizeUsd = Math.abs(posSzi * (entryPx || 1));
            const res = Array.isArray(validResults) ? validResults.find(r => r.coin === posCoin) : null;
            const cached = activePositionsRiskCache.get(`${masterAddress}_${posCoin}`);

            const sl = cached?.stopLoss || res?.stopLoss || (isLong ? entryPx * 0.98 : entryPx * 1.02);
            const tp = cached?.takeProfit || res?.takeProfit || (isLong ? entryPx * 1.04 : entryPx * 0.96);

            activePositionsList.push({
              id: `${posCoin}_${entryPx}_${isLong ? 'LONG' : 'SHORT'}`,
              symbol: `${posCoin}/USDC`,
              coin: posCoin,
              isLong: isLong,
              side: isLong ? "LONG" : "SHORT",
              entryPrice: entryPx,
              exitPrice: 0,
              leverage: lev,
              margin: marginUsed > 0 ? marginUsed : (lev > 0 ? posSizeUsd / lev : 0),
              positionSizeUsdc: posSizeUsd,
              positionSizeCrypto: Math.abs(posSzi),
              unrealizedPnl: parseFloat(p.unrealizedPnl || 0),
              tp: tp,
              sl: sl,
              take_profit: tp,
              stop_loss: sl,
              estLiquidationPrice: parseFloat(p.liquidationPx || 0),
              timestamp: cached?.timestamp || Date.now(),
              isDemo: false
            });
          }
        }
      });
    }

    // Write Perpetual Futures Telemetry Stats back to Firestore
    const targetRadarTelemetry = {};
    if (Array.isArray(validResults)) {
      validResults.forEach(r => {
        targetRadarTelemetry[r.coin] = {
          score: r.proximityScore || 0,
          isBuySignal: r.isBuySignal || false,
          isSellSignal: r.isSellSignal || false,
          price: r.coinPrice || 0,
          stopLoss: r.stopLoss || 0,
          takeProfit: r.takeProfit || 0,
          riskRewardRatio: r.riskRewardRatio || 2.0
        };
      });
    }

    await writeBotStatus({
      pnl_usd: totalPnlUsd,
      pnl_pct: pnlPct,
      active_trades: activeTradesCount,
      active_positions: activePositionsList,
      positions: activePositionsList,
      strategy: futuresStrategyList.join(","),
      strategies: futuresStrategyList,
      timeframe: futuresInterval,
      market_type: "PERP",
      margin_mode: marginMode,
      leverage: configuredLeverage,
      position_direction: positionDirection,
      scanned_markets_count: perpAvailableCoins.length,
      target_radar: targetRadarTelemetry,
      updated_at: Date.now()
    }, { merge: true });

    for (const targetId of writeStatusTargets) {
      try {
        await db.doc(`users/${targetId}/bot/positions`).set({
          positions: activePositionsList,
          updated_at: Date.now()
        }, { merge: true });
      } catch (_) {}
    }

    console.log(`[${uid}] ✅ Futures Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Active Trades: ${activeTradesCount}, Margin: ${marginMode}`);

    // If Spot trading bot is NOT enabled, strictly exit now and NEVER place Spot orders
    if (!isSpotActive) {
      console.log(`[${uid}] 🛑 Spot bot is DISABLED. Strictly skipping all Spot evaluation and order placement.`);
      return;
    }
  }

  // =========================================================================
  // DUAL-MARKET ENGINE: 2. SPOT TRADING MODE
  // =========================================================================
  if (isSpotActive) {
    const spotConfig = config.spot_config || config;
    const spotStrategy = spotConfig.strategy || config.strategy || "30m EMA High/Low";
    const rawSpotStrats = spotConfig.strategies || config.spot_strategies || config.strategies || spotStrategy;
    const spotStrategyList = Array.isArray(rawSpotStrats)
      ? rawSpotStrats
      : (typeof rawSpotStrats === "string" ? rawSpotStrats.split(",").map(s => s.trim()) : ["30m EMA High/Low"]);
    const spotAllocPct = spotConfig.allocation_pct || config.allocation_pct || 25;

    console.log(`[${uid}] ⚡ Running 30m Spot Bot Engine '${spotStrategyList.join(" + ")}' (Alloc: ${spotAllocPct}%)...`);

    // STRICT SINGLE/WHITELISTED ASSET LOCK:
    // Only scan the user-selected/enabled whitelisted asset(s).
    // Completely bypass scanning all universe pairs to eliminate 429 rate limits!
    const scanQueue = whitelistedAssets;

    const pnlPct = accountValue > 0 ? (totalPnlUsd / accountValue) * 100.0 : 0.0;
    const isDualRibbonActive = spotStrategyList.some(s =>
      s.toLowerCase().includes("dual-ribbon") ||
      s.toLowerCase().includes("combined ribbon") ||
      s.toLowerCase().includes("krypt") ||
      s.toLowerCase().includes("5m ribbon") ||
      s.toLowerCase().includes("dual ribbon")
    );
    const isChochActive = !isDualRibbonActive && spotStrategyList.some(s => 
      s.toLowerCase().includes("choch") || s.toLowerCase().includes("fractal") || s.toLowerCase().includes("ict")
    );
    const spotInterval = isDualRibbonActive ? "5m" : "30m";
    const minCandlesReq = isDualRibbonActive ? 136 : (isChochActive ? 15 : 35);

    const isEmaStrategyActive = isDualRibbonActive || isChochActive || spotStrategyList.some(s => 
      s.includes("EMA") || s.includes("Trend") || s.includes("DCA") || s.includes("Grid")
    ) || spotStrategyList.length === 0;

    if (isEmaStrategyActive) {
      console.log(`[${uid}] 🎯 Spot whitelisted asset locked: [${scanQueue.join(", ")}] on ${spotInterval} (Strategy: ${spotStrategyList.join("+")}). Paced sequential evaluation.`);

    // Sequential Paced Candle Queries across whitelisted spot pairs
    const scanResults = [];
    for (let i = 0; i < scanQueue.length; i++) {
      if (i > 0) {
        // Mandatory 1-second delay between whitelisted asset fetches
        await sleep(1000);
      }
      const coin = scanQueue[i];
      const cleanCoin = cleanCoinSymbol(coin);

      if (isSymbolCoolingDown(cleanCoin)) {
        console.warn(`[${uid}] ⏳ Spot symbol ${cleanCoin} is in 429 rate limit cooldown (15s backoff). Skipping tick.`);
        continue;
      }

      try {
        const market = resolveSpotMarket(coin, spotMarketMap);
        const querySymbol = market ? market.exchangeCoin : coin;

        const candleResult = await fetchCandleSnapshot(querySymbol, spotInterval, 300);
        if (candleResult?.rateLimited) {
          recordSymbol429(cleanCoin);
          continue;
        }

        const candles = Array.isArray(candleResult) ? candleResult : (candleResult?.candles || []);
        if (!candles || candles.length < minCandlesReq) continue;

        const latestCandle = candles[candles.length - 1];
        const coinPrice = parseFloat(latestCandle.close || (market ? market.markPrice : 0) || 0);
        if (coinPrice <= 0) continue;

        const signalResult = isDualRibbonActive
          ? await evaluateDualRibbonStrategy(coin, candles)
          : (isChochActive
            ? await evaluateIctMarketStructure(coin, candles)
            : await evaluateEmaRibbonStrategy(coin, candles));

        scanResults.push({ coin, coinPrice, market, ...signalResult });
      } catch (e) {
        console.error(`[${uid}] Error evaluating ${coin}:`, e.message);
      }
    }

    const validResults = scanResults.filter(Boolean);

    // 1. Process SELL Signals for held token positions
    for (const res of validResults) {
      const { coin, coinPrice, isSellSignal, exitCandleOffset, market } = res;
      const heldBalance = spotBalances.get(coin) || 
        spotBalances.get(`U${coin}`) || 
        (coin.startsWith("U") ? spotBalances.get(coin.substring(1)) : 0) || 
        0.0;

      if (isSellSignal && heldBalance > 0.0001 && isSpotActive) {
        const offsetDesc = exitCandleOffset === 0 ? "current candle" : `${exitCandleOffset} candle(s) ago`;
        console.log(`[${uid}] 🔻 MULTI-PAIR SCANNER: SELL/EXIT SIGNAL on ${coin} (exit transition: ${offsetDesc})! Selling 100% of held ${heldBalance} ${coin}...`);
        try {
          const resolvedMarket = market || resolveSpotMarket(coin, spotMarketMap);
          const assetIdx = resolvedMarket ? resolvedMarket.l1AssetIndex : (assetIndexMap.get(coin) || 10000);
          const szDec = resolvedMarket?.szDecimals !== undefined 
            ? resolvedMarket.szDecimals 
            : (szDecimalsMap.get(coin) !== undefined ? szDecimalsMap.get(coin) : 2);
          const formattedSz = formatSizeForHyperliquid(heldBalance, szDec);
          const limitPx = formatPriceForHyperliquid(coinPrice * 0.99, szDec, true);

          if (parseFloat(formattedSz) > 0) {
            const sellResult = await placeAgentOrder({
              agentPrivateKey,
              masterAddress,
              assetIndex: assetIdx,
              isBuy: false,
              limitPx: limitPx,
              sz: formattedSz
            });

            const statusEntry = sellResult?.response?.data?.statuses?.[0];
            if (statusEntry?.error) {
              console.error(`[${uid}] ❌ Spot SELL REJECTED by Hyperliquid for ${coin} (assetIndex: ${assetIdx}): "${statusEntry.error}"`);
            } else {
              console.log(`[${uid}] ✅ Spot SELL placed: ${formattedSz} ${coin} (assetIndex: ${assetIdx}) @ limit ${limitPx} (szDecimals: ${szDec})`);
            }
          } else {
            console.warn(`[${uid}] Formatted sell size for ${coin} rounded to zero with szDecimals=${szDec}`);
          }
        } catch (err) {
          console.error(`[${uid}] Spot Sell Execution Failed for ${coin}:`, err.message);
        }
      }
    }

    if (enabledAssets.length === 0 && !targetCoin) {
      console.log(`[${uid}] 🔒 AI Bot auto-buy is PAUSED: 0 assets toggled ON in user settings. (Toggle assets ON in Spot Asset modal to authorize auto-trading).`);
    } else {
      console.log(`[${uid}] 🎯 Authorized Auto-Buy Target: ${targetCoin} (Enabled Assets: ${enabledAssets.length > 0 ? enabledAssets.join(", ") : "Target Default"})`);
    }

    // Filter scanned results for assets explicitly authorized (toggled ON or target coin) by user
    const authorizedResults = validResults.filter(r => isAssetAuthorizedForBuy(r.coin));

    // Prioritize authorized assets by proximity score descending (closest to or at 100% breakout transition)
    authorizedResults.sort((a, b) => (b.proximityScore || 0) - (a.proximityScore || 0));

    if (authorizedResults.length > 0) {
      const radarLog = authorizedResults.map(r => `${r.coin}: ${r.proximityScore}%${(r.isBuySignal || (r.proximityScore >= 100 && r.isCurrentAbove)) ? ' [▲ BUY]' : ''}`).join(", ");
      console.log(`[${uid}] 📡 TARGET RADAR (Authorized Assets Proximity): ${radarLog}`);
    }

    const getHeldBalance = (coin) => {
      const clean = coin.toUpperCase().replace(/^U/, "").replace("/USDC", "").replace("USDC", "").replace("/", "");
      return (
        spotBalances.get(coin) ||
        spotBalances.get(`U${clean}`) ||
        spotBalances.get(clean) ||
        0.0
      );
    };

    // 2. Automatically execute Spot BUY on authorized coin when confirmed buy signal or 100% radar signal reached
    const bestBuySignal = authorizedResults.find(r => 
      (r.isBuySignal || (r.proximityScore >= 100 && r.isCurrentAbove)) && 
      getHeldBalance(r.coin) <= 0.0001
    );

    if (bestBuySignal && usdcBalance > 0.5 && isSpotActive) {
      const { coin, coinPrice, breakoutCandleOffset, proximityScore, market } = bestBuySignal;
      const offsetDesc = breakoutCandleOffset === 0 ? "current candle" : `${breakoutCandleOffset} candle(s) ago`;
      console.log(`[${uid}] 🚀 MULTI-PAIR SCANNER: 100% RADAR BREAKOUT BUY SIGNAL on ${coin} (Proximity: ${proximityScore}%, state: IN_BUY, breakout transition: ${offsetDesc})! Executing Spot Buy with available $${usdcBalance.toFixed(2)} USDC...`);
      try {
        const resolvedMarket = market || resolveSpotMarket(coin, spotMarketMap);
        const allocPct = (spotAllocPct || config.allocation_pct || 100) / 100.0;
        let buyAmountUsdc = usdcBalance * allocPct;
        // Utilize full available spot balance (e.g., $9.43)
        if (buyAmountUsdc < 10.0 && usdcBalance >= 1.0) {
          buyAmountUsdc = usdcBalance;
        }

        const rawSize = buyAmountUsdc / coinPrice;
        const szDec = resolvedMarket?.szDecimals !== undefined 
          ? resolvedMarket.szDecimals 
          : (szDecimalsMap.get(coin) !== undefined ? szDecimalsMap.get(coin) : 2);
        let formattedSz = formatSizeForHyperliquid(rawSize, szDec);

        // If formatted size rounded to zero because size is small, step up to minimum decimal increment
        if (parseFloat(formattedSz) <= 0 && szDec > 0) {
          const minStep = Math.pow(10, -szDec);
          formattedSz = minStep.toFixed(szDec);
        }

        const limitPx = formatPriceForHyperliquid(coinPrice * 1.02, szDec, true); // 2% market buy slippage cap
        const assetIdx = resolvedMarket ? resolvedMarket.l1AssetIndex : (assetIndexMap.get(coin) || 10000);

        if (parseFloat(formattedSz) > 0) {
          const notionalUsd = parseFloat((parseFloat(formattedSz) * coinPrice).toFixed(2));
          console.log(`[${uid}] 📤 Submitting Spot Market BUY to Hyperliquid: ${formattedSz} ${coin} (assetIndex: ${assetIdx}, notional: ~$${notionalUsd} USD) using available $${usdcBalance.toFixed(2)} USDC...`);

          const orderResult = await placeAgentOrder({
            agentPrivateKey,
            masterAddress,
            assetIndex: assetIdx,
            isBuy: true,
            limitPx: limitPx,
            sz: formattedSz
          });

          // Inspect order response explicitly for transparent logging & error reporting
          const statusEntry = orderResult?.response?.data?.statuses?.[0];
          if (statusEntry?.error) {
            console.error(`[${uid}] ❌ Spot BUY REJECTED by Hyperliquid Exchange for ${coin} (assetIndex: ${assetIdx}): "${statusEntry.error}" [Attempted: ${formattedSz} @ limit ${limitPx}, Notional: ~$${notionalUsd} USD]`);
            await writeBotStatus({
              last_order_result: {
                timestamp: Date.now(),
                coin,
                side: "BUY",
                status: "REJECTED",
                error: statusEntry.error,
                attempted_size: formattedSz,
                attempted_price: limitPx,
                attempted_notional_usd: notionalUsd,
                available_usdc: usdcBalance,
                asset_index: assetIdx
              }
            }, true);
          } else if (statusEntry?.filled) {
            console.log(`[${uid}] ✅ Spot BUY FILLED on Hyperliquid: ${statusEntry.filled.totalSz} ${coin} @ avgPx ${statusEntry.filled.avgPx} (oid: ${statusEntry.filled.oid})`);
            await writeBotStatus({
              last_order_result: {
                timestamp: Date.now(),
                coin,
                side: "BUY",
                status: "FILLED",
                filled_size: statusEntry.filled.totalSz,
                avg_price: statusEntry.filled.avgPx,
                oid: statusEntry.filled.oid,
                asset_index: assetIdx
              }
            }, true);
          } else if (statusEntry?.resting) {
            console.log(`[${uid}] ✅ Spot BUY RESTING order placed on Hyperliquid: ${formattedSz} ${coin} @ ${limitPx} (oid: ${statusEntry.resting.oid})`);
            await writeBotStatus({
              last_order_result: {
                timestamp: Date.now(),
                coin,
                side: "BUY",
                status: "RESTING",
                size: formattedSz,
                price: limitPx,
                oid: statusEntry.resting.oid,
                asset_index: assetIdx
              }
            }, true);
          } else if (orderResult?.status === "err") {
            console.error(`[${uid}] ❌ Spot BUY FAILED with server error:`, orderResult.response);
            await writeBotStatus({
              last_order_result: {
                timestamp: Date.now(),
                coin,
                side: "BUY",
                status: "FAILED",
                error: String(orderResult.response),
                asset_index: assetIdx
              }
            }, true);
          } else {
            console.log(`[${uid}] ℹ️ Spot order response for ${coin}:`, JSON.stringify(orderResult));
          }
        } else {
          console.warn(`[${uid}] Formatted buy size for ${coin} rounded to zero with szDecimals=${szDec} for $${buyAmountUsdc.toFixed(2)} USDC allocation`);
        }
      } catch (err) {
        console.error(`[${uid}] Spot Buy Execution Failed for ${coin}:`, err.message);
        await writeBotStatus({
          last_order_result: {
            timestamp: Date.now(),
            coin,
            side: "BUY",
            status: "EXCEPTION",
            error: err.message
          }
        }, true);
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

  // Write Telemetry Stats back to Firestore
  await writeBotStatus({
    pnl_usd: totalPnlUsd,
    pnl_pct: pnlPct,
    active_trades: activeTradesCount,
    strategy: spotStrategyList.join(","),
    strategies: spotStrategyList,
    timeframe: spotInterval,
    market_type: "SPOT",
    is_spot_bot_active: isSpotActive,
    is_futures_bot_active: isFuturesActive,
    scanned_markets_count: availableCoins.length,
    target_radar: targetRadarTelemetry,
    updated_at: Date.now()
  }, true);

  console.log(`[${uid}] ✅ Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Scanned Spot Markets: ${availableCoins.length}`);
  }

  // Memory Leak Prevention: Clear temporary maps and arrays after tick evaluation
  try {
    perpMarketMap?.clear();
    spotMarketMap?.clear();
    spotBalances?.clear();
    assetIndexMap?.clear();
    szDecimalsMap?.clear();
  } catch (e) {}
}

module.exports = {
  executeBotTick,
  evaluateEmaRibbonStrategy,
  evaluateIctMarketStructure,
  evaluateDualRibbonStrategy,
  calculateBreakoutProximity,
  calculateEMA,
  formatSizeForHyperliquid,
  formatPriceForHyperliquid,
  FALLBACK_SZ_DECIMALS
};

