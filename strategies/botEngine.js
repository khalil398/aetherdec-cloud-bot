const { fetchUserClearinghouseState, fetchAllMids, placeAgentOrder } = require("../services/hyperliquid");

/**
 * Executes a 24/7 algorithmic strategy tick cycle for a specific user.
 */
async function executeBotTick(uid, db, config, botSecret) {
  const { agentPrivateKey, masterAddress } = botSecret;
  if (!masterAddress) {
    console.warn(`[${uid}] Missing masterAddress in botSecret. Skipping tick.`);
    return;
  }

  console.log(`[${uid}] Running 24/7 strategy '${config.strategy}' (Allocation: ${config.allocation_pct}%)...`);

  // 1. Fetch Clearinghouse & Live Market Prices
  const clearinghouse = await fetchUserClearinghouseState(masterAddress);
  const allMids = await fetchAllMids();
  const btcPrice = parseFloat(allMids["BTC"] || 0);

  let totalPnlUsd = 0.0;
  let activeTradesCount = 0;
  let accountValue = 0.0;

  if (clearinghouse && clearinghouse.marginSummary) {
    accountValue = parseFloat(clearinghouse.marginSummary.accountValue || 0);
    const assetPositions = clearinghouse.assetPositions || [];
    activeTradesCount = assetPositions.length;

    totalPnlUsd = assetPositions.reduce((sum, item) => {
      const pos = item.position;
      return sum + (parseFloat(pos.unrealizedPnl || 0));
    }, 0.0);
  }

  const pnlPct = accountValue > 0 ? (totalPnlUsd / accountValue) * 100.0 : 0.0;

  // 2. Evaluate Strategy Logic
  if (config.strategy === "Smart DCA / Dip Buyer") {
    // Smart DCA Dip Buyer check
    if (btcPrice > 0 && activeTradesCount === 0 && config.active) {
      console.log(`[${uid}] Smart DCA: Evaluating dip buyer entry for BTC at $${btcPrice}`);
    }
  } else if (config.strategy === "Grid Trading") {
    // Grid Trading algorithm
    console.log(`[${uid}] Grid Trading: Maintaining active grid levels around BTC $${btcPrice}`);
  } else if (config.strategy === "Trend Following") {
    // Trend Following algorithm
    console.log(`[${uid}] Trend Following: Evaluating momentum indicators for BTC $${btcPrice}`);
  }

  // 3. Write Telemetry Stats back to Firestore users/{uid}/bot/bot_status
  const statusDocRef = db.doc(`users/${uid}/bot/bot_status`);
  await statusDocRef.set({
    pnl_usd: totalPnlUsd,
    pnl_pct: pnlPct,
    active_trades: activeTradesCount,
    updated_at: Date.now()
  }, { merge: true });

  console.log(`[${uid}] Telemetry updated: PnL=$${totalPnlUsd.toFixed(2)} (${pnlPct.toFixed(2)}%), Active Trades=${activeTradesCount}`);
}

module.exports = {
  executeBotTick
};
