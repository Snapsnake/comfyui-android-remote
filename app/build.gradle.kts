plugins {
    id("com.android.application")
}

android {
    namespace = "com.snapsnake.comfyremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapsnake.comfyremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "0.8.6-controls-first"
    }
}
