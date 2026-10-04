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

  const handleConfigChange = (change, db) => {
    const docRef = change.doc.ref;
    const pathSegments = docRef.path.split("/");
    // Path format: users/{uid}/bot/bot_config or users/{uid}/bot_config/config
    const uid = pathSegments[1];
    const config = change.doc.data();
    if (!uid || !config) return;

    console.log(`🔔 Bot config update for UID [${uid}]: active=${config.active}, strategy='${config.strategy}'`);

    if (config.active) {
      startWorker(uid, db, config);
    } else {
      stopWorker(uid);
    }
  };

  // Primary listener on subcollection "bot" (document "bot_config")
  db.collectionGroup("bot").onSnapshot((snapshot) => {
    snapshot.docChanges().forEach((change) => {
      if (change.doc.id === "bot_config") {
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


async function startWorker(uid, db, config) {
  stopWorker(uid);

  // Fetch restricted Agent Key secret from users/{uid}/bot/bot_secret
  const secretDoc = await db.doc(`users/${uid}/bot/bot_secret`).get();
  if (!secretDoc.exists) {
    console.warn(`[${uid}] No bot_secret document found in Firestore. Skipping worker start.`);
    return;
  }

  const botSecret = secretDoc.data();
  console.log(`🚀 Starting 24/7 worker engine for UID [${uid}] (Master: ${botSecret.masterAddress})`);

  // Initial immediate tick
  try {
    await executeBotTick(uid, db, config, botSecret);
  } catch (e) {
    console.error(`[${uid}] Initial tick error:`, e.message);
  }

  // 10-second recurring execution loop
  const intervalId = setInterval(async () => {
    try {
      await executeBotTick(uid, db, config, botSecret);
    } catch (e) {
      console.error(`[${uid}] Tick execution error:`, e.message);
    }
  }, 10000);

  activeEngines.set(uid, { intervalId, config, botSecret });
}

function stopWorker(uid) {
  if (activeEngines.has(uid)) {
    const worker = activeEngines.get(uid);
    clearInterval(worker.intervalId);
    activeEngines.delete(uid);
    console.log(`⏸️ Worker engine paused for UID [${uid}]`);
  }
}

app.listen(PORT, () => {
  console.log(`🌐 AetherDex Cloud Bot HTTP Server listening on port ${PORT}`);
});
