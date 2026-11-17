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
    implementation("androidx.collection:collection:1.5.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // Match the upstream module's explicit dependency versions while retaining
    // this fork's newer annotation dependency.
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
}
