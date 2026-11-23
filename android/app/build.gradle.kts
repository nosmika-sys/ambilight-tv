plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ambilight.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ambilight.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    // Chave fixa guardada como segredo no GitHub (KEYSTORE_B64 / KEYSTORE_PASS).
    // Todas as versoes saem com a mesma assinatura, senao o Android recusa atualizar.
    val ksPath = System.getenv("KEYSTORE_PATH")
    val ksPass = System.getenv("KEYSTORE_PASS")
    val temChave = ksPath != null && ksPass != null && file(ksPath).exists()
    signingConfigs {
        if (temChave) {
            create("fixa") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "ambilight"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (temChave) signingConfigs.getByName("fixa")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
