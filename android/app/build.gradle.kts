plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agrichain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agrichain"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("test").resources.srcDir(rootProject.file("../contracts"))
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.android.gms:play-services-nearby:19.1.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath; android.jar only stubs it.
    testImplementation("org.json:json:20240303")
}
