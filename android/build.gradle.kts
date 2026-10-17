plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

extra["beaconVersionCode"] = 16

extra["beaconVersionName"] = "1.2.5"
