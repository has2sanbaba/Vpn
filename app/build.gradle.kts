import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "acr.browser.lightning"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.has2sanbaba.drbrowser"
        // Android 5.0 Lollipop (API 21) -> Android 15/16+ (API 35+)
        minSdk = 21
        targetSdk = 35
        versionCode = 800
        versionName = "8.0.0"

        buildConfigField("boolean", "FULL_VERSION", "Boolean.parseBoolean(\"true\")")

        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
        named("test") {
            java.srcDirs("src/test/java", "src/test/kotlin")
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    // Build lightweight per-CPU APKs (ARMv7, ARM64, x86, x86_64) + Universal APK
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
            keepDebugSymbols += setOf("**/libgfproxy.so")
        }
        resources {
            excludes += setOf(
                ".readme",
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

kapt {
    correctErrorTypes = true
}

val ensureNativeGfProxy by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-gfproxy-android.sh")
    onlyIf {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        abis.any { abi -> !file("src/main/jniLibs/$abi/libgfproxy.so").exists() }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(ensureNativeGfProxy)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.20"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")

    // Dependency injection (Dagger 2.56.2 — supports Kotlin 2.1.20 metadata, zero reflection at runtime)
    implementation("com.google.dagger:dagger:2.56.2")
    kapt("com.google.dagger:dagger-compiler:2.56.2")
    implementation("javax.inject:javax.inject:1")
    compileOnly("javax.annotation:jsr250-api:1.0")

    // Reactive streams & HTML reader/homepage generation (Lightning-Browser core)
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("io.reactivex.rxjava3:rxkotlin:3.0.1")
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    implementation("org.jsoup:jsoup:1.18.3")

    // HTTP & Streaming client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
