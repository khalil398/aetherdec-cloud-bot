import urllib.request
import json

url = "https://api.hyperliquid.xyz/info"
body = json.dumps({"type": "metaAndAssetCtxs"}).encode("utf-8")
req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})

with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))
    universe = data[0]["universe"]
    ctxs = data[1]
    print(f"Total perps: {len(universe)}")
    for i, u in enumerate(universe[:30]):
        name = u.get("name")
        sz_dec = u.get("szDecimals")
        max_lev = u.get("maxLeverage")
        mark_px = ctxs[i].get("markPx") if i < len(ctxs) else "N/A"
        print(f"Index {i:2d}: {name:<10} | szDecimals: {sz_dec} | maxLev: {max_lev} | price: ${mark_px}")

# Check allMids
body_mids = json.dumps({"type": "allMids"}).encode("utf-8")
req_mids = urllib.request.Request(url, data=body_mids, headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req_mids) as resp:
    mids = json.loads(resp.read().decode("utf-8"))
    print("\nTotal items in allMids:", len(mids))
    sample_keys = ["BTC", "ETH", "SOL", "XRP", "GOLD", "SILVER", "NVDA", "TSLA", "AAPL", "BRENTOIL"]
    for k in sample_keys:
        print(f"allMids['{k}']: {mids.get(k, 'Not found')}")
