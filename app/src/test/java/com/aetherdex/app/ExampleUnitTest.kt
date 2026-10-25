package com.aetherdex.app

import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger

class ExampleUnitTest {
    @Test
    fun testEvmDerivationStepByStep() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val expectedEvmAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

        val seed = Bip39Utils.mnemonicToSeed(mnemonic)
        println("Seed hex: " + seed.joinToString("") { "%02x".format(it) })

        val H = Int.MIN_VALUE
        // Master
        val hmac0 = MultiChainAddressDeriver.bip32DerivePublic(seed) // wait, path empty
        // Let's trace path step by step
        val k0 = MultiChainAddressDeriver.bip32DerivePublic(seed)
        println("Master Key: " + k0.joinToString("") { "%02x".format(it) })

        val k1 = MultiChainAddressDeriver.bip32DerivePublic(seed, 44 or H)
        println("m/44' Key: " + k1.joinToString("") { "%02x".format(it) })

        val k2 = MultiChainAddressDeriver.bip32DerivePublic(seed, 44 or H, 60 or H)
        println("m/44'/60' Key: " + k2.joinToString("") { "%02x".format(it) })

        val k3 = MultiChainAddressDeriver.bip32DerivePublic(seed, 44 or H, 60 or H, 0 or H)
        println("m/44'/60'/0' Key: " + k3.joinToString("") { "%02x".format(it) })

        val k4 = MultiChainAddressDeriver.bip32DerivePublic(seed, 44 or H, 60 or H, 0 or H, 0)
        println("m/44'/60'/0'/0 Key: " + k4.joinToString("") { "%02x".format(it) })

        val k5 = MultiChainAddressDeriver.bip32DerivePublic(seed, 44 or H, 60 or H, 0 or H, 0, 0)
        println("m/44'/60'/0'/0/0 Key: " + k5.joinToString("") { "%02x".format(it) })

        val pub64 = MultiChainAddressDeriver.uncompressedPubKey64Public(k5)
        println("Pub64 Hex: " + pub64.joinToString("") { "%02x".format(it) })

        val keccak = MultiChainAddressDeriver.Keccak256.hash(pub64)
        println("Keccak Hash: " + keccak.joinToString("") { "%02x".format(it) })

        val addrBytes = keccak.copyOfRange(12, 32)
        val derivedEvm = Bip39Utils.checksumEvmAddress(addrBytes.joinToString("") { "%02x".format(it) })

        println("Derived EVM Address: $derivedEvm")
        println("Expected EVM Address: $expectedEvmAddress")

        assertEquals(expectedEvmAddress.lowercase(), derivedEvm.lowercase())
    }
}