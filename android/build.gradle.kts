plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

extra["beaconVersionCode"] = 17

extra["beaconVersionName"] = "1.3.0"
