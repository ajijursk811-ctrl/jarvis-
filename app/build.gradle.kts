import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shilex.android"
    compileSdk = 35

    defaultConfig {
applicationId = "app.masuka.ai"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Phones only (ONNX Runtime ships native code per CPU type).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The release key never enters the repo: scripts/android_release.py passes its path and
    // password (from the macOS Keychain) as environment variables. Without them the release
    // build is unsigned. Android installs an update only if it is signed with this same key.
    val releaseKeystore = System.getenv("SHILEX_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SHILEX_KEYSTORE_PASSWORD")
                keyAlias = "jarvis"
                keyPassword = System.getenv("SHILEX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
    // SpeexDSP's echo canceller (BSD), built from src/main/cpp.
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Android calls such as Log do nothing in local unit tests instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // On-device wake word (openWakeWord ONNX models), MIT licence.
    implementation(libs.onnxruntime.android)
    // Offline speech for local commands (Vosk, Apache 2.0); the model comes from fetchVoskModel.
    implementation(libs.vosk.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Android's own org.json is a stub in local unit tests.
    testImplementation(libs.org.json)
}

// Vosk's small Indian-English model (Apache 2.0, 36 MB zip): too big for git, so it is
// downloaded once at build time and checked against a pinned SHA-256.
val voskModel = "vosk-model-small-en-in-0.4"
val voskSha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7"
val voskAssets = layout.buildDirectory.dir("generated/vosk-assets")

val fetchVoskModel by tasks.registering {
    val out = voskAssets.get().asFile
    outputs.dir(out)
    doLast {
        if (out.resolve("vosk/am/final.mdl").exists()) return@doLast
        val zip = temporaryDir.resolve("$voskModel.zip")
        uri("https://alphacephei.com/vosk/models/$voskModel.zip").toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == voskSha256) { "Vosk model download doesn't match its pinned hash ($digest)" }
        project.copy {
            from(zipTree(zip))
            into(out)
            exclude("**/README")
            eachFile { path = path.replaceFirst(voskModel, "vosk") }
            includeEmptyDirs = false
        }
    }
}

android.sourceSets["main"].assets.srcDir(voskAssets)
tasks.named("preBuild") { dependsOn(fetchVoskModel) }
