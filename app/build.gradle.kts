import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is optional so the project builds for anyone who clones it.
// CI supplies it through environment variables fed from GitHub Secrets; a local
// developer may supply keystore.properties. With neither present the release
// variant still assembles, but unsigned — and an unsigned APK installs nowhere,
// which is why the publish workflow refuses to run without the secrets.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile != null &&
    file(releaseStoreFile).exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.harithkavish.keyboard"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.harithkavish.keyboard"
        // API 21 is the floor for the drawing this keyboard does. Nothing in the
        // app needs anything newer; the two API-level guards in the source are
        // for optional polish, not for function.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // No BuildConfig, no resource values, no shader compilation: every build
    // feature costs APK bytes and none of them are used here.
    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
        renderScript = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The symbol pages carry literal non-ASCII characters. Left to the
        // platform default this compiles differently on a Windows workstation
        // than on the Linux runner, and the difference only shows up as mangled
        // key labels in the shipped APK.
        encoding = "UTF-8"
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }
}

// Nothing ships. The app compiles against the platform SDK alone, so the APK
// carries no library code at all; JUnit is a test-only dependency and never
// reaches a build output.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Prints the size of each APK the build produced. The whole design goal of this
// app is that this number stays small, so it is worth seeing on every build
// rather than discovering a regression at publish time.
tasks.register("reportApkSize") {
    description = "Reports the size of every APK under app/build/outputs/apk."
    group = "verification"
    val outputsDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        val dir = outputsDir.get().asFile
        if (!dir.exists()) {
            println("No APKs found under $dir — run an assemble task first.")
            return@doLast
        }
        val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) {
            println("No APKs found under $dir — run an assemble task first.")
            return@doLast
        }
        apks.sortedBy { it.name }.forEach { apk ->
            println("%-40s %,10d bytes  (%.1f KB)".format(apk.name, apk.length(), apk.length() / 1024.0))
        }
    }
}
