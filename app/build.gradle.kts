plugins {
    id("com.android.application")
}

android {
    namespace = "com.snapsnake.comfyremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapsnake.comfyremote"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-graph-import-fallback"
    }
}
