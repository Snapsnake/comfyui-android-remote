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
        versionCode = 49
        versionName = "0.13.0-workstation"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
