plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.aetherdex.app"
    compileSdk = 37


    defaultConfig {
        applicationId = "com.aetherdex.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.biometric:biometric:1.1.0")

    // Firebase BoM, Auth, Firestore
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Reown (WalletConnect v2) — Core relay + Sign protocol for dApp sessions.
    // Pulled from Maven Central; no extra repository needed.
    implementation(libs.reown.android.core)
    implementation(libs.reown.sign)
    implementation(libs.reown.appkit)
    implementation(libs.androidx.navigation.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // ZXing — real scannable QR code generation
    implementation("com.google.zxing:core:3.5.3")
    // Ed25519 — cryptographically correct Solana address derivation (SLIP-0010)
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // BouncyCastle — industry standard Keccak-256 implementation for Ethereum EVM addresses
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // SwipeRefreshLayout — Pull-to-Refresh for Wallet Overview
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // MsgPack for Hyperliquid action encoding
    implementation("org.msgpack:msgpack-core:0.9.6")
    
    // Web3j for EIP-712 signing
    implementation("org.web3j:core:4.10.0")
    implementation("org.web3j:crypto:4.9.8")
    implementation("org.web3j:utils:4.9.8")
    implementation("org.web3j:abi:4.9.8")
}

