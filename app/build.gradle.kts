plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.kuba.przypominacz"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.kuba.przypominacz"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
    }

    // Stały klucz w repozytorium = każda nowa wersja instaluje się na starą bez utraty danych
    signingConfigs {
        create("wspolny") {
            storeFile = file("przypominacz.jks")
            storePassword = "przypominacz"
            keyAlias = "przypominacz"
            keyPassword = "przypominacz"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wspolny")
        }
        debug {
            signingConfig = signingConfigs.getByName("wspolny")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
