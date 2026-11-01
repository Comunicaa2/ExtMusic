import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Credenciales OAuth (Mejora 15) leídas de local.properties (fuera de git).
val credenciales = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Room exporta el esquema de cada versión de la BD aquí (versionarlo en git).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.auramusic.app"
    // compileSdk 37: lo exigen Compose 1.12 y Backdrop (liquid glass). targetSdk se
    // queda en 34 para no cambiar el comportamiento de la app en el teléfono.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.auramusic.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.3.0"

        // (M2) Se eliminaron YT_CLIENT_ID / YT_CLIENT_SECRET: eran del login de TV
        // antiguo, ningún .kt los usa y quedaban expuestos en el APK. El login nativo
        // (Authorization API) no usa secreto de cliente.
    }

    // Firma de release. Las credenciales viven en local.properties (gitignored) y
    // el .jks está FUERA del repo. Si faltan, el build de release queda sin firmar.
    signingConfigs {
        create("release") {
            val rutaKeystore = credenciales.getProperty("RELEASE_STORE_FILE")
            if (rutaKeystore != null && file(rutaKeystore).exists()) {
                storeFile = file(rutaKeystore)
                storePassword = credenciales.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = credenciales.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = credenciales.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true        // R8/ProGuard activo
            isShrinkResources = true      // quita recursos sin usar (no se usa getIdentifier; ver fase O)
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor usa APIs de Java que solo existen en runtime desde
        // API 33 (p. ej. URLDecoder.decode(String, Charset)). Sin esto, la app
        // crashea con NoSuchMethodError en Android < 13 (API 29/30) al resolver
        // el stream. El desugaring reescribe esas llamadas para minSdk 26+.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true   // expone BuildConfig.VERSION_NAME para "Acerca de"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // F-Droid: sin el bloque cifrado de dependencias que añade Google al APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Fix 3: clases de tamaño de ventana para el layout adaptativo (NavigationRail
    // en tablets/pantallas anchas). La versión la gestiona el compose-bom.
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.core:core-ktx:1.19.1")
    // Splash screen de AndroidX: evita el flash blanco al arrancar (Fase 2).
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Media3 completo — FLAC, ALAC, WAV, Opus, HiRes
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-extractor:1.11.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Ktor
    implementation("io.ktor:ktor-client-android:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-client-logging:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Liquid glass (desenfoque + refracción tipo lupa en los bordes) para el mini
    // reproductor y la barra inferior. Kyant0/AndroidLiquidGlass, Apache-2.0; es la
    // misma librería que usa SimpMusic. La refracción necesita Android 13 (API 33).
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // WorkManager + Hilt
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")
    // Escritura en la carpeta de descargas elegida por SAF (Bug 4).
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Arrastrar para reordenar la cola (Apache-2.0, solo Compose).
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // 5.5.1: bloquea los certificados intermedios comprometidos de eMudhra.
    // Ktor 3 ya pedía 5.x, así que la 4.12.0 que había aquí nunca se usaba.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Arreglos de seguridad en dependencias transitivas (no se usan directamente).
    constraints {
        implementation("org.jsoup:jsoup:1.23.2") {
            because("CVE-2026-75140: memoria del parser XML (la trae NewPipeExtractor 0.26.5 en 1.22.2)")
        }
        implementation("com.google.guava:guava:33.7.2-android") {
            because("CVE-2026-102554: deserialización (la trae Media3 1.11.1 en 33.3.1)")
        }
    }

    // NewPipe Extractor — descifra YouTube directamente en el dispositivo.
    // Fase 3: subido a v0.26.3 (motor moderno con refactor de clientes y soporte
    // de poToken, PR #1272). Es el mismo motor de extracción del ecosistema
    // NewPipe/BravePipe en el que se apoya SimpMusic para el stream real.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core library desugaring: necesario para que NewPipeExtractor funcione en
    // Android < 13 (API 29/30). DEBE ser el artefacto "_nio" (superset): es el
    // único que reescribe java.net.URLDecoder.decode(String, Charset) — la API 33+
    // que usa NewPipe y que hacía crashear con NoSuchMethodError. El artefacto
    // estándar (sin _nio) NO cubre URLDecoder. Requisito oficial de NewPipeExtractor.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
