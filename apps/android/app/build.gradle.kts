import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Firebase is optional: with no app/google-services.json (or the old placeholder) the app builds
// and runs without push. Drop the real file in and the google-services plugin turns on.
val googleServicesFile = file("google-services.json")
val pushConfigured = googleServicesFile.exists() && !googleServicesFile.readText().contains("hypurr-placeholder")
if (pushConfigured) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.ragul84.hypurr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ragul84.hypurr"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates since Aug 31, 2026.
        targetSdk = 36
        // Same version as apps/project.yml MARKETING_VERSION and host/Cargo.toml.
        versionName = "2.6.3"
        versionCode = 20603
        // The push relay (relay/, deployed at push.mindgains.ai): host -> relay -> FCM.
        // Override without editing: ./gradlew assembleRelease -Phypurr.relayUrl=https://relay.example.com
        val relayUrl = providers.gradleProperty("hypurr.relayUrl").getOrElse("https://push.mindgains.ai")
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        // Push is on only when a real Firebase config is present (see docs/setup/push.md).
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
        // RevenueCat public Android SDK key (goog_…) for buying credits via Google Play. Empty: the
        // Buy buttons stay disabled. -Phypurr.revenuecatKey=goog_… or env REVENUECAT_ANDROID_KEY.
        val rcKey = providers.gradleProperty("hypurr.revenuecatKey")
            .orElse(providers.environmentVariable("REVENUECAT_ANDROID_KEY")).getOrElse("")
        buildConfigField("String", "REVENUECAT_KEY", "\"$rcKey\"")
    }

    // Upload-key signing for Play. Nothing secret is committed: values come from env vars
    // (CI: GitHub secrets) or from a git-ignored keystore.properties. Without them the release
    // build is simply unsigned.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: ksProps.getProperty(prop)?.takeIf { it.isNotBlank() }
    val uploadStoreFile = signingValue("HYPURR_UPLOAD_STORE_FILE", "storeFile")
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = signingValue("HYPURR_UPLOAD_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("HYPURR_UPLOAD_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("HYPURR_UPLOAD_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (uploadStoreFile != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.output.dir", rootProject.file("screenshots").path)
                // The protocol vectors are shared with the host and iOS tests.
                it.systemProperty("hypurr.vectors", rootProject.file("../../docs/reference/fixtures/remote-relay-vectors.json").path)
                it.systemProperty("hypurr.taskWire", rootProject.file("../../docs/reference/fixtures/task-wire.json").path)
                it.systemProperty("hypurr.workWire", rootProject.file("../../docs/reference/fixtures/work-wire.json").path)
                it.systemProperty("hypurr.adminWire", rootProject.file("../../docs/reference/fixtures/admin-wire.json").path)
                it.systemProperty("hypurr.chatWire", rootProject.file("../../docs/reference/fixtures/chat-wire.json").path)
                listOf("HYPURR_E2E_LINK", "HYPURR_E2E_BOT", "HYPURR_E2E_PROJECT", "HYPURR_E2E_AGENT").forEach { key ->
                    System.getenv(key)?.let { value -> it.environment(key, value) }
                }
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}")
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots"))
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.zxing.embedded)
    // On-device OCR for shared screenshots in Fix from a log (Play services model, nothing leaves the phone).
    implementation(libs.mlkit.text)
    // Remote screen: WebRTC (Google's libwebrtc, packaged by Stream), receive-only video + data channels.
    implementation(libs.webrtc)
    implementation(libs.revenuecat)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
}
