plugins {
    id("com.android.library")
}

android {
    namespace = "com.qmdeve.liquidglass"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
}
