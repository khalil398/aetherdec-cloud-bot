const axios = require("axios");
const { ethers } = require("ethers");
const msgpack = require("msgpack-lite");

const HYPERLIQUID_API_URL = process.env.HYPERLIQUID_API_URL || "https://api.hyperliquid.xyz";

/**
 * Fetches Hyperliquid L1 Clearinghouse state for a user.
 */
async function fetchUserClearinghouseState(userAddress) {
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "clearinghouseState",
      user: userAddress
    });
    return response.data;
  } catch (error) {
    console.error(`[Hyperliquid] Error fetching clearinghouseState for ${userAddress}:`, error.message);
    return null;
  }
}

/**
 * Fetches spot clearinghouse balances for a user.
 */
async function fetchSpotClearinghouseState(userAddress) {
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "spotClearinghouseState",
      user: userAddress
    });
    return response.data;
  } catch (error) {
    console.error(`[Hyperliquid] Error fetching spotClearinghouseState for ${userAddress}:`, error.message);
    return null;
  }
}

/**
 * Fetches latest mid market prices for all assets.
 */
async function fetchAllMids() {
  try {
    const response = await axios.post(`${HYPERLIQUID_API_URL}/info`, {
      type: "allMids"
    });
    return response.data;
  } catch (error) {
    console.error("[Hyperliquid] Error fetching allMids:", error.message);
    return {};
  }
}

/**
 * Places an L1 Order signed using the delegated Agent Key.
 */
async function placeAgentOrder({ agentPrivateKey, masterAddress, assetIndex, isBuy, limitPx, sz, orderType = { limit: { tif: "Gtc" } }, reduceOnly = false }) {
  try {
    const nonce = Date.now();
    const orderWire = {
      a: assetIndex,
      b: isBuy,
      p: limitPx.toString(),
      s: sz.toString(),
      r: reduceOnly,
      t: orderType
    };

    const action = {
      type: "order",
      orders: [orderWire],
      grouping: "na"
    };

    // Serialize action via msgpack
    const actionBytes = msgpack.encode(action);
    
    // Hash connectionId = actionBytes + nonceBytes + vaultMarker(0x00)
    const nonceBuffer = Buffer.alloc(8);
    nonceBuffer.writeBigUInt64BE(BigInt(nonce));
    const vaultMarker = Buffer.from([0x00]);
    const connectionIdBytes = Buffer.concat([actionBytes, nonceBuffer, vaultMarker]);
    const connectionId = ethers.keccak256(connectionIdBytes);

    // EIP-712 Agent digest computation
    const domainSeparator = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32", "uint256", "bytes32"],
        [
          ethers.id("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
          ethers.id("Exchange"),
          ethers.id("1"),
          1337,
          "0x0000000000000000000000000000000000000000"
        ]
      )
    );

    const agentStructHash = ethers.keccak256(
      ethers.AbiCoder.defaultAbiCoder().encode(
        ["bytes32", "bytes32", "bytes32"],
        [
          ethers.id("Agent(string source,bytes32 connectionId)"),
          ethers.id("a"),
          connectionId
        ]
      )
    );

    const digest = ethers.keccak256(
      Buffer.concat([
        Buffer.from("1901", "hex"),
        Buffer.from(domainSeparator.slice(2), "hex"),
        Buffer.from(agentStructHash.slice(2), "hex")
      ])
    );

    // Sign digest using Agent Private Key
    const wallet = new ethers.Wallet(agentPrivateKey);
    const signature = wallet.signingKey.sign(digest);

    const payload = {
      action,
      nonce,
      signature: {
        r: signature.r,
        s: signature.s,
        v: signature.v
      },
      vaultAddress: null
    };

    const response = await axios.post(`${HYPERLIQUID_API_URL}/exchange`, payload, {
      headers: { "Content-Type": "application/json" }
    });

    console.log(`[Hyperliquid] Agent order placed for asset ${assetIndex}:`, response.data);
    return response.data;
  } catch (error) {
    console.error("[Hyperliquid] Agent order placement failed:", error.response?.data || error.message);
    throw error;
  }
}

module.exports = {
  fetchUserClearinghouseState,
  fetchSpotClearinghouseState,
  fetchAllMids,
  placeAgentOrder
};
