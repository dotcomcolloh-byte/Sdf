plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }

android { namespace = "com.vidtubehub.video.app"; compileSdk = 35; buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "com.vidtubehub.video.app"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "1.2.0"
        // Deployed backend. Override: ./gradlew assembleRelease -PapiBaseUrl=https://api.yourdomain.com
        buildConfigField("String", "API_BASE_URL", "\"${(project.findProperty("apiBaseUrl") as String?) ?: "https://api.example.com"}\"")
        manifestPlaceholders["cleartext"] = "false"
        // WEB OAuth client id (same one you put in backend GOOGLE_CLIENT_IDS). ./gradlew ... -PgoogleWebClientId=xxxx.apps.googleusercontent.com
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${(project.findProperty("googleWebClientId") as String?) ?: ""}\"")
    }
    buildTypes { debug { manifestPlaceholders["cleartext"] = "true"; if (project.hasProperty("apiBaseUrl").not()) buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"") } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3"); implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.ktor:ktor-client-android:3.0.3"); implementation("io.ktor:ktor-client-content-negotiation:3.0.3"); implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3"); implementation("androidx.media3:media3-exoplayer-hls:$media3"); implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3"); implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.credentials:credentials:1.3.0"); implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
