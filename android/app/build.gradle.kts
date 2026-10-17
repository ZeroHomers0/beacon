plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    // Declares the read-only collection interfaces as stable so the Compose compiler can skip
    // screens whose only "unstable" member is a List that did not change. See
    // app/compose-stability.conf for what the file asserts.
    composeCompiler {
        stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
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
    implementation(platform(libs.androidx.compose.bom))
    // MIUIX WindowDialog requires ComponentActivity's NavigationEventDispatcherOwner (1.12+).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Version comes from the compose BOM above.
    implementation(libs.androidx.compose.material3)
    // Fixed official version: Kotlin 2.3.10; its Android Compose dependencies resolve together.
    implementation(libs.miuix.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // API 37 removed the InputManager.getInstance reflection used by older Espresso.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
