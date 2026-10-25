import urllib.request
import json

url = "https://api.hyperliquid.xyz/info"
body = json.dumps({"type": "meta"}).encode("utf-8")
req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})

with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))
    universe = data["universe"]
    print(f"Total perps in Hyperliquid universe: {len(universe)}")
    for i, u in enumerate(universe):
        print(f"{i:3d}: {u['name']:<12} | szDecimals: {u['szDecimals']} | maxLeverage: {u['maxLeverage']}")
