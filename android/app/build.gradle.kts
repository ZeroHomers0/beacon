plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production signing is opt-in. A partial configuration fails without printing secrets.
val releaseSigning =
    listOf(
            "BEACON_RELEASE_KEYSTORE_FILE",
            "BEACON_RELEASE_KEY_ALIAS",
            "BEACON_RELEASE_STORE_PASSWORD",
            "BEACON_RELEASE_KEY_PASSWORD",
        )
        .associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigning.values.any { !it.isNullOrBlank() }

// CI builds one APK per matrix entry; local builds remain universal by default.
val beaconAbi = providers.gradleProperty("beaconAbi").getOrElse("universal")
require(beaconAbi in setOf("universal", "armeabi-v7a", "arm64-v8a")) {
    "beaconAbi must be universal, armeabi-v7a or arm64-v8a"
}

if (hasReleaseSigning)
    require(releaseSigning.values.all { !it.isNullOrBlank() }) {
        "All four BEACON_RELEASE signing variables are required"
    }

android {
    namespace = "io.github.beacon.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.beacon.app"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = rootProject.extra["beaconVersionCode"] as Int
        versionName = rootProject.extra["beaconVersionName"] as String
        if (beaconAbi != "universal") ndk.abiFilters.add(beaconAbi)
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "r8-release-report.pro",
            )
        }
    }
    if (hasReleaseSigning) {
        val production =
            signingConfigs.create("production") {
                storeFile = file(releaseSigning.getValue("BEACON_RELEASE_KEYSTORE_FILE")!!)
                keyAlias = releaseSigning.getValue("BEACON_RELEASE_KEY_ALIAS")
                storePassword = releaseSigning.getValue("BEACON_RELEASE_STORE_PASSWORD")
                keyPassword = releaseSigning.getValue("BEACON_RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        buildTypes.getByName("release").signingConfig = production
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Every AndroidX AAR ships a byte-identical Apache-2.0 LICENSE.txt under
    // META-INF/androidx/<group>/<artifact>/. Shipping six copies only bloats the APK, so the
    // duplicates are dropped and the androidx.annotation copy below keeps the text distributed.
    packaging {
        resources {
            excludes +=
                listOf(
                    "META-INF/androidx/collection/collection/LICENSE.txt",
                    "META-INF/androidx/collection/collection-ktx/LICENSE.txt",
                    "META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt",
                    "META-INF/androidx/lifecycle/lifecycle-common-java8/LICENSE.txt",
                    "META-INF/androidx/room/room-common/LICENSE.txt",
                )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    // MIUIX WindowDialog requires ComponentActivity's NavigationEventDispatcherOwner (1.12+).
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    // Fixed official version: Kotlin 2.3.10; its Android Compose dependencies resolve together.
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // API 37 removed the InputManager.getInstance reflection used by older Espresso.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
