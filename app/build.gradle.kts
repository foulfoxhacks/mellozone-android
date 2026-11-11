import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Upload-key details live in keystore.properties (not in version control).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// Optional: move build output to a short path. Needed on Windows when the
// project sits in a very deep folder (260-character path limit).
val buildRoot: String? = System.getenv("MZ_BUILD_ROOT")
if (buildRoot != null) {
    layout.buildDirectory.set(file("$buildRoot/gradle/app"))
}

val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "com.foulfoxhacks.mellozone"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.foulfoxhacks.mellozone"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += rustAbis
            // Ship the native symbol table so Play can symbolicate crashes.
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    bundle {
        // One listing language; keep every string in the base split.
        language.enableSplit = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Rust engine: built with cargo-ndk for every shipped ABI before each build.
// ---------------------------------------------------------------------------
val rustDir = rootProject.file("rust")
val jniLibsDir = file("src/main/jniLibs")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Builds libmellozone_core.so for all Android ABIs."
    workingDir = rustDir

    val home = System.getProperty("user.home")
    val cargoBin = File(home, ".cargo/bin")
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val cargo = File(cargoBin, if (isWindows) "cargo.exe" else "cargo")
        .takeIf { it.isFile }?.absolutePath ?: "cargo"

    val args = mutableListOf(cargo, "ndk")
    rustAbis.forEach { args += listOf("-t", it) }
    args += listOf("--platform", "26", "-o", jniLibsDir.absolutePath, "build", "--release", "--lib")
    commandLine(args)

    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    environment("PATH", cargoBin.absolutePath + File.pathSeparator + System.getenv("PATH"))
    // 16 KB memory pages (required by Google Play for Android 15+ devices).
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    if (buildRoot != null) environment("CARGO_TARGET_DIR", "$buildRoot/cargo")

    inputs.dir(File(rustDir, "src"))
    inputs.files(File(rustDir, "Cargo.toml"), File(rustDir, "Cargo.lock"), File(rustDir, "build.rs"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
