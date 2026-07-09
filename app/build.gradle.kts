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
        versionCode = 47
        versionName = "0.12.1-cache-real-templates"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
