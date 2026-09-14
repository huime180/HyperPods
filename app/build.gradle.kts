plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.chenyc.hyperpods"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chenyc.hyperpods"
        minSdk = 35
        targetSdk = 36
        versionCode = 16
        versionName = "2.1.0"
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("releaseFast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

/**
 * module.prop 与 Gradle 版本号的一致性守卫。
 *
 * module.prop 的 version / versionCode 是 LSPosed 与系统「应用信息」显示给用户的版本，
 * Gradle 的 versionName / versionCode 则决定 APK 文件名与包信息 —— 两处是各自独立的字面量，
 * 之前漂移过（app 已经是 2.1.0 / 16，模块里还写着 1.0.0 / 1，LSPosed 里看到的版本是错的）。
 * 这里在每次构建前断言一致，不一致直接失败，免得再出现「装的到底是哪个版本」。
 */
val modulePropFile = layout.projectDirectory.file("src/main/resources/META-INF/xposed/module.prop")
val expectedVersionName = android.defaultConfig.versionName
val expectedVersionCode = android.defaultConfig.versionCode
tasks.register("verifyModuleProp") {
    group = "verification"
    description = "断言 Xposed module.prop 的 version / versionCode 与 Gradle 配置一致"
    inputs.file(modulePropFile)
    doLast {
        val text = modulePropFile.asFile.readText()
        fun value(key: String): String? =
            Regex("(?m)^" + key + "=(.*)$").find(text)?.groupValues?.get(1)?.trim()
        val actualName = value("version")
        val actualCode = value("versionCode")
        check(actualName == expectedVersionName) {
            "module.prop version=$actualName 与 versionName=$expectedVersionName 不一致，请同步后再构建"
        }
        check(actualCode == expectedVersionCode?.toString()) {
            "module.prop versionCode=$actualCode 与 versionCode=$expectedVersionCode 不一致，请同步后再构建"
        }
        logger.lifecycle("verifyModuleProp: module.prop $actualName ($actualCode) 与 Gradle 一致")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("verifyModuleProp") }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion)
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)
    implementation(libs.libxposedService)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // MIUIX
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)

    // Navigation3
    implementation(libs.navigation3.runtime)

    // HyperOS Focus Island API
    implementation(libs.focus.api)

    testImplementation(libs.junit)
}
