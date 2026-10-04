# 🤖 AetherDex 24/7 Cloud Bot Engine

Autonomous, 24/7 background strategy execution backend for **AetherDex**. Runs continuously in the cloud even when the user's mobile app is closed or phone is turned off.

---

## 🏗️ Architecture & Features

1. **Non-Custodial Agent Security**: Uses Hyperliquid L1 native `approveAgent` EIP-712 credentials. The Agent Key has **zero withdrawal or transfer authority**.
2. **Real-Time Firebase Synchronization**: Listens directly to `users/{uid}/bot/bot_config` for toggle events and writes real-time telemetry stats (`pnl_usd`, `pnl_pct`, `active_trades`) to `users/{uid}/bot/bot_status`.
3. **Decoupled 24/7 Cloud Execution**: Runs on any Node.js 20+ host (Render, Railway, Fly.io, AWS, Heroku).

---

## 🛠️ Environment Variables

Copy `.env.example` to `.env` or configure variables in your cloud hosting provider dashboard:

| Variable | Description | Example / Format |
| :--- | :--- | :--- |
| `PORT` | HTTP Server port for health checks | `3000` |
| `HYPERLIQUID_API_URL` | Hyperliquid L1 API endpoint | `https://api.hyperliquid.xyz` |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | Firebase Admin SDK Service Account JSON string or Base64 string | `{"type":"service_account", ...}` |

---

## 🚀 Step-by-Step Deployment Roadmap

### Option 1: Deploy on Render.com (Recommended Free Tier)

1. **Push Repository**: Push the `aetherdex-cloud-bot` directory to GitHub/GitLab.
2. **Create Web Service**: Log into [Render.com](https://render.com) dashboard and click **New +** -> **Web Service**.
3. **Connect Repository**: Select your `aetherdex-cloud-bot` repository.
4. **Settings**:
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node index.js`
5. **Environment Variables**: Add `FIREBASE_SERVICE_ACCOUNT_KEY` with your Firebase service account JSON.
6. **Deploy**: Render automatically deploys and runs your 24/7 bot engine!

---

### Option 2: Deploy on Railway.app

1. Log into [Railway.app](https://railway.app).
2. Click **New Project** -> **Deploy from GitHub repo**.
3. Select `aetherdex-cloud-bot`.
4. Go to **Variables** tab and add `FIREBASE_SERVICE_ACCOUNT_KEY`.
5. Railway auto-detects `package.json` and launches your 24/7 worker.

---

## 🧪 Local Testing

```bash
cd aetherdex-cloud-bot
npm install
npm start
```

Health check:
```bash
curl http://localhost:3000/health
```
