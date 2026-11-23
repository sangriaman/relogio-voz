plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pt.sangriaman.relogiovoz"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.sangriaman.relogiovoz"
        // O relógio corre LineageOS 20 (Android 13 = API 33)
        minSdk = 33
        targetSdk = 33
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            // O Mi Smart Clock (MT8167) só corre 32 bits
            abiFilters += listOf("armeabi-v7a")
        }
    }

    signingConfigs {
        // Chave fixa: assim cada nova versão instala por cima da anterior
        create("fixa") {
            storeFile = file("relogio.keystore")
            storePassword = "relogio"
            keyAlias = "relogio"
            keyPassword = "relogio"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixa")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Reconhecimento de voz offline
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Rádios e áudio do YouTube
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Pesquisa e extração de áudio do YouTube
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
}
