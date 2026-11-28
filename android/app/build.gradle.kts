plugins {
    id("com.android.application")
}
android {
    namespace = "app.ezcall.android"
    compileSdk = 36
    sourceSets["main"].kotlin.srcDir("../shared/src/main/java")
    sourceSets["main"].res.srcDir("../shared/src/main/res")

    buildFeatures { aidl = true }

    bundle {
        language { enableSplit = false }
    }

    defaultConfig {
        applicationId = "app.ezcall.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 24
        versionName = "0.1.23"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.10.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15to18:1.87") {
            because("libadb-android pulls 1.81, which has published advisories")
        }
    }
}
