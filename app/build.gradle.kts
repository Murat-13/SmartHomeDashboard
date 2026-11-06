import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Версия «мажор.минор.патч» (0.9.x до полной регрессии, затем 1.0.0). versionCode выводится из неё:
// 0.9.0 → 900, 0.9.1 → 901, 1.0.0 → 10000, поэтому растёт вместе с версией. Минор и патч — 0–99.
val appVersionName = "0.9.11"
val appVersionCode = appVersionName.split('.').map { it.toInt() }.let { parts ->
    require(parts.size == 3 && parts[1] in 0..99 && parts[2] in 0..99) {
        "versionName должен быть вида мажор.минор.патч, минор и патч 0–99: $appVersionName"
    }
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// Release-ключ владельца: keystore.properties в корне проекта (вне Git, см. docs/release-signing.md).
// Нет файла или ключа — release собирается неподписанным, debug подписывается обычным отладочным ключом.
val releaseSigning: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { propsFile ->
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val storePath = props.getProperty("storeFile")?.trim()?.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))
    when {
        required.any { props.getProperty(it).isNullOrBlank() } -> {
            logger.warn("keystore.properties: не заполнены поля ${required.filter { props.getProperty(it).isNullOrBlank() }}; release-подпись не настроена")
            null
        }
        !rootProject.file(storePath!!).isFile -> {
            logger.warn("keystore.properties: файл ключа не найден; release-подпись не настроена")
            null
        }
        else -> props.apply { setProperty("storeFile", rootProject.file(storePath).absolutePath) }
    }
}

android {
    namespace = "com.example.smarthomedashboard"
    // Android 15 (0.9.7): edge-to-edge принудительно — отступы системных полос задаёт SystemBarInsets.
    // 36 — для Media3 1.11 (видео домофона). Поведение приложения задаёт targetSdk.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.smarthomedashboard"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        // Решение владельца: при наличии ключа debug из Android Studio подписывается им же,
        // чтобы после переустановки «Run» ставил приложение поверх, без удаления данных.
        debug {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Ресурсы приложения в Robolectric: тесты вёрстки инфлейтят настоящие layout-файлы.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Ежедневные автокопии настроек (решение владельца 02.11.2026). 2.10.x требует compileSdk 35 (0.9.7).
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // Видео плитки «Домофон» (решение владельца 06.11.2026): RTSP напрямую с камеры, HLS через Home Assistant.
    // Аппаратное декодирование H.264/H.265; без media3-ui — отрисовка своя (TextureView, стиль Modern).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
