plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.leitorqbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.leitorqbox"
        minSdk = 26
        targetSdk = 34
        // O CI passa -PversionCode=<número do build> para cada release ser uma atualização.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    // Chave fixa: todo APK sai com a mesma assinatura e instala por cima do anterior.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/leitor-qbox.p12")
            storeType = "pkcs12"
            storePassword = "leitorqbox"
            keyAlias = "leitorqbox"
            keyPassword = "leitorqbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
