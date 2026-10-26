require("dotenv").config();
const express = require("express");
const admin = require("firebase-admin");
const { executeBotTick } = require("./strategies/botEngine");

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());

// Express Health Check Ping for Render / Railway Uptime Monitors
app.get("/", (req, res) => {
  res.json({ status: "ok", service: "AetherDex 24/7 Cloud Bot Engine", uptimeSeconds: process.uptime() });
});

app.get("/health", (req, res) => {
  res.status(200).send("OK");
});

// Initialize Firebase Admin SDK
let serviceAccount;
try {
  const serviceAccountRaw = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!serviceAccountRaw) {
    console.warn("⚠️ Warning: FIREBASE_SERVICE_ACCOUNT_KEY environment variable not set. Running in dry-run mode.");
  } else {
    serviceAccount = serviceAccountRaw.startsWith("{") 
      ? JSON.parse(serviceAccountRaw) 
      : JSON.parse(Buffer.from(serviceAccountRaw, "base64").toString("utf-8"));
    
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    console.log("✅ Firebase Admin SDK initialized successfully.");
  }
} catch (err) {
  console.error("❌ Failed to parse FIREBASE_SERVICE_ACCOUNT_KEY:", err.message);
}

const activeEngines = new Map();

if (admin.apps.length > 0) {
  const db = admin.firestore();

  const handleConfigChange = async (change, db) => {
    const docRef = change.doc.ref;
    const pathSegments = docRef.path.split("/");
    // Path format: users/{uid}/bot/bot_config or users/{uid}/bot_config/config
    const uid = pathSegments[1];
    const config = change.doc.data();
    if (!uid || !config) return;

    // Direct wallet address binding: extract from config or EVM UID
    let walletAddress = config.masterAddress || config.wallet_address || config.walletAddress || (uid.startsWith("0x") ? uid : null);

    // Deep lookup fallback 1: Check users/{uid}/bot/bot_secret
    if (!walletAddress) {
      try {
        const secretDoc = await db.doc(`users/${uid}/bot/bot_secret`).get();
        if (secretDoc.exists) {
          const sData = secretDoc.data();
          walletAddress = sData.masterAddress || sData.wallet_address || sData.walletAddress;
        }
      } catch (e) {}
    }

    // Deep lookup fallback 2: Check root user document users/{uid}
    if (!walletAddress) {
      try {
        const userDoc = await db.doc(`users/${uid}`).get();
        if (userDoc.exists) {
          const uData = userDoc.data();
          walletAddress = uData.connectedWalletAddress || uData.wallet_address || uData.masterAddress;
        }
      } catch (e) {}
    }

    if (walletAddress) {
      config.masterAddress = walletAddress;
      config.wallet_address = walletAddress;
      config.walletAddress = walletAddress;
    }

    const isSpotActive = Boolean(
      config.is_spot_bot_active ||
      config.spot_active ||
      config.spot_config?.active ||
      (config.market_type === "SPOT" && config.active)
    );

    const isFuturesActive = Boolean(
      config.is_futures_bot_active ||
      config.futures_active ||
      config.futures_config?.active ||
      ((config.market_type === "PERP" || config.market_type === "FUTURES") && config.active) ||
      (config.active && (
        String(config.strategy || "").toLowerCase().includes("dual-ribbon") ||
        String(config.futures_strategy || "").toLowerCase().includes("dual-ribbon") ||
        (Array.isArray(config.strategies) && config.strategies.some(s => String(s).toLowerCase().includes("dual-ribbon"))) ||
        (Array.isArray(config.futures_strategies) && config.futures_strategies.some(s => String(s).toLowerCase().includes("dual-ribbon")))
      ))
    );

    const isAnyActive = isSpotActive || isFuturesActive || config.active === true;

    console.log(`🔔 Bot config update for UID [${uid}] (Wallet: ${walletAddress || "N/A"}): spotActive=${isSpotActive}, futuresActive=${isFuturesActive}, anyActive=${isAnyActive}`);

    // Map to a consolidated worker key (prefer lowercase wallet address)
    const workerKey = walletAddress ? walletAddress.toLowerCase() : uid;

    if (isAnyActive) {
      if (activeEngines.has(workerKey)) {
        // Worker is ALREADY running: update config in place without restarting intervals!
        const existing = activeEngines.get(workerKey);
        existing.config = config;
        return;
      }
      startWorker(workerKey, db, config, uid, walletAddress);
    } else {
      stopWorker(workerKey);
    }
  };

  // Primary listener on subcollection "bot" (document "bot_config" & "futures_config")
  db.collectionGroup("bot").onSnapshot((snapshot) => {
    snapshot.docChanges().forEach((change) => {
      if (change.doc.id === "bot_config" || change.doc.id === "futures_config") {
        handleConfigChange(change, db);
      }
    });
  }, (error) => {
    console.error("❌ Firestore 'bot' CollectionGroup Error:", error.message);
  });

  // Fallback listener on subcollection "bot_config"
  db.collectionGroup("bot_config").onSnapshot((snapshot) => {
    snapshot.docChanges().forEach((change) => {
      handleConfigChange(change, db);
    });
  }, (error) => {
    console.error("❌ Firestore 'bot_config' CollectionGroup Error:", error.message);
  });
}

const pendingWorkerStarts = new Set();

async function startWorker(workerKey, db, config, uid, walletAddress) {
  if (pendingWorkerStarts.has(workerKey)) return;
  pendingWorkerStarts.add(workerKey);

  try {
    stopWorker(workerKey);

    // Multi-path lookup for restricted Agent Key secret:
    // 1. users/${walletAddress}/bot/bot_secret
    // 2. users/${walletAddress.toLowerCase()}/bot/bot_secret
    // 3. users/${uid}/bot/bot_secret
    let botSecret = null;
    const lookupIds = Array.from(new Set([
      walletAddress,
      walletAddress ? walletAddress.toLowerCase() : null,
      uid,
      config.masterAddress,
      config.wallet_address
    ])).filter(Boolean);

    for (const id of lookupIds) {
      try {
        const secretRef = db.doc(`users/${id}/bot/bot_secret`);
        const secretDoc = await secretRef.get();
        if (secretDoc.exists) {
          botSecret = secretDoc.data();
          console.log(`[${workerKey}] ✅ Found bot_secret under users/${id}/bot/bot_secret`);
          break;
        }
      } catch (e) {
        console.warn(`[${workerKey}] Error checking secret for ${id}:`, e.message);
      }
    }

    // Fallback: Check root user documents for bot_secret field
    if (!botSecret) {
      for (const id of lookupIds) {
        try {
          const userDoc = await db.doc(`users/${id}`).get();
          if (userDoc.exists && userDoc.data()?.bot_secret) {
            botSecret = userDoc.data().bot_secret;
            console.log(`[${workerKey}] ✅ Found bot_secret in root user doc users/${id}`);
            break;
          }
        } catch (e) {}
      }
    }

    // If botSecret is not found yet, initialize with walletAddress for continuous clearinghouse/market scanning
    if (!botSecret) {
      if (walletAddress) {
        console.warn(`[${workerKey}] No bot_secret document found yet in Firestore. Initializing worker with walletAddress [${walletAddress}] for continuous clearinghouse/market scanning.`);
        botSecret = { masterAddress: walletAddress };
      } else {
        console.warn(`[${workerKey}] No bot_secret document or wallet address found in Firestore. Skipping worker start.`);
        return;
      }
    }

    if (!botSecret.masterAddress && walletAddress) {
      botSecret.masterAddress = walletAddress;
    }

    console.log(`🚀 Starting 24/7 worker engine for [${workerKey}] (Master Wallet: ${botSecret.masterAddress})`);

    // Initial immediate tick
    try {
      await executeBotTick(workerKey, db, config, botSecret);
    } catch (e) {
      console.error(`[${workerKey}] Initial tick error:`, e.message);
    }

    // 10-second recurring execution loop (strictly prevents overlapping ticks)
    let isTickRunning = false;
    const intervalId = setInterval(async () => {
      if (isTickRunning) return; // Prevent overlapping execution
      isTickRunning = true;
      try {
        const currentWorker = activeEngines.get(workerKey);
        if (!currentWorker) return;
        await executeBotTick(workerKey, db, currentWorker.config, currentWorker.botSecret);
      } catch (e) {
        console.error(`[${workerKey}] Tick execution error:`, e.message);
      } finally {
        isTickRunning = false;
        if (global.gc) {
          try { global.gc(); } catch (e) {}
        }
      }
    }, 10000);

    activeEngines.set(workerKey, { intervalId, config, botSecret });
  } finally {
    pendingWorkerStarts.delete(workerKey);
  }
}

function stopWorker(workerKey) {
  pendingWorkerStarts.delete(workerKey);
  if (activeEngines.has(workerKey)) {
    const worker = activeEngines.get(workerKey);
    if (worker.intervalId) {
      clearInterval(worker.intervalId);
    }
    activeEngines.delete(workerKey);
    console.log(`⏸️ Worker engine paused for [${workerKey}]`);
  }
}

app.listen(PORT, () => {
  console.log(`🌐 AetherDex Cloud Bot HTTP Server listening on port ${PORT}`);
});
