import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localSigning = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val previewBuild = providers.gradleProperty("narrioPreview").map(String::toBoolean).getOrElse(false)
val localBuild = providers.gradleProperty("narrioLocal").map(String::toBoolean).getOrElse(false)
check(!(previewBuild && localBuild)) { "Choose either a signed preview or a local testing app" }
val sourceVersion = runCatching {
    providers.exec { commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--abbrev=0") }
        .standardOutput.asText.get().trim().removePrefix("v")
}.getOrDefault("1.1.0")
val versionParts = sourceVersion.split('.').map(String::toInt)
val sourceVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
val signingValues = if (System.getenv("NARRIO_KEYSTORE_PATH") != null) Properties().apply {
    setProperty("storeFile", System.getenv("NARRIO_KEYSTORE_PATH"))
    setProperty("storePassword", System.getenv("NARRIO_KEYSTORE_PASSWORD") ?: "")
    setProperty("keyAlias", System.getenv("NARRIO_KEY_ALIAS") ?: "")
    setProperty("keyPassword", System.getenv("NARRIO_KEY_PASSWORD") ?: "")
} else localSigning
if (providers.gradleProperty("requireSigning").getOrElse("false").toBoolean()) {
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !signingValues.getProperty(it).isNullOrBlank()
    }) { "All signing credentials are required for a distributable APK" }
}

android {
    namespace = "app.narrio"
    compileSdk = 37
    defaultConfig {
        applicationId = when {
            localBuild -> "app.narrio.local"
            previewBuild -> "app.narrio.dev"
            else -> "app.narrio"
        }
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(sourceVersionCode)
        versionName = providers.gradleProperty("appVersionName").getOrElse("$sourceVersion-dev")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium uses java.time on API levels that predate it.
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (previewBuild) listOf("debug", "release").forEach {
        sourceSets.getByName(it).res.srcDir("src/preview/res")
    }
    if (localBuild) listOf("debug", "release").forEach {
        sourceSets.getByName(it).res.srcDir("src/local/res")
    }
    if (signingValues.isNotEmpty()) {
        signingConfigs.create("localRelease") {
            storeFile = rootProject.file(signingValues.getProperty("storeFile"))
            storePassword = signingValues.getProperty("storePassword")
            keyAlias = signingValues.getProperty("keyAlias")
            keyPassword = signingValues.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("localRelease")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed speech-recognition libraries keep the APK download smaller.
        jniLibs.useLegacyPackaging = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    // Reader fixtures (hostile and sample EPUBs) are shared by JVM and emulator tests.
    listOf("test", "androidTest").forEach { sourceSets.getByName(it).kotlin.directories += "src/sharedTest/java" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-inspector:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // BookTextParser offsets (follow-along anchors, reader cursors) depend on Element.text(); jsoup 1.23 changed its
    // separators, so keep 1.21.2 even though Readium requests 1.23. Readium only uses APIs present in 1.21.
    implementation("org.jsoup:jsoup") { version { strictly("1.21.2") } }
    implementation("io.coil-kt:coil-compose:2.7.0")
    // On-device speech recognition for narration sync; models download on first use.
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Ebook reader: EPUB parsing and the WebView navigator, hosted as a fragment inside Compose.
    implementation("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.window:window-testing:1.4.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
