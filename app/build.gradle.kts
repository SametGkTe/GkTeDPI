plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sametgkte.gktedpi"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.sametgkte.gktedpi"
        minSdk = 21
        targetSdk = 34
        versionCode = 600
        versionName = "6.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
    }

    signingConfigs {
        val envKeystorePath = System.getenv("KEYSTORE_PATH")
        val bundledKeystore = rootProject.file("keystore/gktedpi-release.jks")
        if (!envKeystorePath.isNullOrBlank() && file(envKeystorePath).exists()) {
            create("release") {
                storeFile = file(envKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        } else if (bundledKeystore.exists()) {
            create("release") {
                storeFile = bundledKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "gktedpi123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "gktedpi"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "gktedpi123"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            isMinifyEnabled = false
            // Sign with release keystore (CI env or bundled keystore/gktedpi-release.jks) for seamless OTA updates
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-debug\"")
        }
    }

    splits {
        abi {
            isEnable = System.getenv("SPLIT_APK") == "true"
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.takisoft.preferencex:preferencex:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(project(":gktedpi-connect-sdk"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

val missingHevPrebuilts = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").any { abi ->
    !file("src/main/jniLibs/$abi/libhev-socks5-tunnel.so").exists()
}

if (missingHevPrebuilts) {
    tasks.register<Exec>("runNdkBuild") {
        group = "build"
        doFirst {
            val ndkDir = android.ndkDirectory
            executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "$ndkDir\\ndk-build.cmd"
            } else {
                "$ndkDir/ndk-build"
            }
        }
        setArgs(
            listOf(
                "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
                "NDK_LIBS_OUT=src/main/jniLibs",
                "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
                "NDK_APPLICATION_MK=src/main/jni/Application.mk"
            )
        )
    }

    tasks.preBuild {
        dependsOn("runNdkBuild")
    }
}
