plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.length() > 0 }
android {
    namespace = "com.example.duotranslate"
    compileSdk = 34
    defaultConfig { applicationId = "io.github.killercats1.duotranslate"; minSdk = 26; targetSdk = 34; versionCode = buildNumber; versionName = "1.$buildNumber"; buildConfigField("String", "GEMINI_API_KEY", "\"${System.getenv("GEMINI_API_KEY") ?: ""}\"") }
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "duotranslate"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes { release { if (keystore != null) signingConfig = signingConfigs.getByName("release") } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.mlkit:translate:17.0.3")
}
