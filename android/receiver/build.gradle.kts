plugins {
    id("com.android.application")
}

android {
    namespace = "app.ezcall.receiver"
    compileSdk = 36
    sourceSets["main"].kotlin.srcDir("../shared/src/main/java")
    sourceSets["main"].res.srcDir("../shared/src/main/res")

    bundle {
        language { enableSplit = false }
    }

    defaultConfig {
        applicationId = "app.ezcall.receiver"
        minSdk = 31
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
