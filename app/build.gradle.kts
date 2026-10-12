plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.example.gpstempoplayer"
 compileSdk = 35
 defaultConfig { applicationId = "com.example.gpstempoplayer"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "1.2.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.10.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("com.google.android.material:material:1.12.0")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-session:1.5.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
}
