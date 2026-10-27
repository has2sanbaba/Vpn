import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.greatfire.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.greatfire.envoy.browser"
        // Android 5.0 Lollipop (API 21) -> Android 15/16+ (API 35+)
        minSdk = 21
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0-greatfire"

        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Enable V1 (JAR) for Android 5.0-6.0 AND V2/V3 for Android 7.0-16+
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            enableV1Signing = true
            enableV2Signing = true
        }
        release {
            // Keep JNI classes for Cronet (GEN_JNI) and gomobile IEnvoyProxy intact
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Build Universal APK (all CPUs) + lightweight per-CPU APKs (ARMv7, ARM64, x86, x86_64)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Required so libgfproxy.so (FreeBrowser/Anti-DPI) and libsslocal.so (Shadowsocks)
            // are extracted to nativeLibraryDir with SELinux exec permission on Android 5..16
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libcronet*.so",
                "**/libgojni.so",
                "**/libsslocal.so",
                "**/libgfproxy.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Automatically fetch GreatFire AARs if not already present when building from Android Studio / CLI
val ensureGreatFireLibs by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/fetch-greatfire-libs.sh")
    onlyIf {
        !file("libs/IEnvoyProxy.aar").exists() ||
            !file("libs/cronet-108.0.5359.195.aar").exists()
    }
}

val ensureNativeGfProxy by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-gfproxy-android.sh")
    onlyIf {
        !file("src/main/jniLibs/arm64-v8a/libgfproxy.so").exists() ||
            !file("src/main/jniLibs/armeabi-v7a/libgfproxy.so").exists() ||
            !file("src/main/jniLibs/x86_64/libgfproxy.so").exists() ||
            !file("src/main/jniLibs/x86/libgfproxy.so").exists()
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(ensureGreatFireLibs, ensureNativeGfProxy)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // GreatFire Official Android Libraries:
    // 1. cronet-108.0.5359.195.aar (Patched Chromium Cronet + Envoy + Geneva + ECH + gfw-report/shadowsocks-rust)
    // 2. IEnvoyProxy.aar e3.5.0 (V2Ray, XRay, Hysteria2, Snowflake, Lyrebird Obfs4/Meek/WebTunnel, MASQUE, ECH, DoH)
    implementation(files("libs/cronet-108.0.5359.195.aar"))
    implementation(files("libs/IEnvoyProxy.aar"))

    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.20"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")

    // Networking & Cronet requirements
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
