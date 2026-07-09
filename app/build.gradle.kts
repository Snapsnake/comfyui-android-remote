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
        versionCode = 50
        versionName = "0.13.1-preview-fields"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
