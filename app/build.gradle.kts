import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

val signingFile = rootProject.file("key.properties")
val signingProperties = Properties().apply {
    if (signingFile.exists()) signingFile.reader(Charsets.UTF_8).use(::load)
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigning = signingKeys.all { !signingProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.tiji.studyclock"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "com.tiji.studyclock"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.15.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions { unitTests.isReturnDefaultValues = false }
}

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

val checkArchitecture by tasks.registering {
    group = "verification"
    doLast {
        fileTree("src/main/java").matching { include("**/*.kt") }.forEach { source ->
            val path = source.invariantSeparatorsPath
            val text = source.readText()
            if (listOf("/domain/stats/", "/domain/diagnose/", "/domain/prescribe/").any(path::contains)) {
                check(!Regex("(?m)^import\\s+androidx?\\.").containsMatchIn(text)) {
                    "Domain code must not depend on Android: $source"
                }
            }
            if (path.contains("/ui/")) {
                check(!Regex("(?m)^import\\s+com\\.tiji\\.studyclock\\.data\\.db").containsMatchIn(text)) {
                    "UI must not access database types: $source"
                }
            }
        }
    }
}
val checkDesignContracts by tasks.registering {
    group = "verification"
    doLast {
        val metricSource = file("src/main/java/com/tiji/studyclock/domain/stats/Metric.kt").readText()
        val implementedIds = Regex("\\b[A-GPRX][1-6]\\b").findAll(metricSource.substringAfter("enum class MetricId").substringBefore("object Thresholds"))
            .map { it.value }.toSet()
        // v0.11：登记表 46 项（A–F 26 + G/R/X 15 + P1–P5），与设计书 §4.3 逐项对齐。
        // 设计书属于不随仓库分发的内部文档，可能不存在；此时退化为「只校验代码侧仍是 46 项」，
        // 并明确打印跳过原因，避免把「没找到文档」静默当成「比对通过」。
        val design = listOf(rootProject.file("软件设计书.md"), rootProject.file("docs/软件设计书.md"))
            .firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
        if (design == null) {
            logger.lifecycle("checkDesignContracts: 未找到《软件设计书》，跳过登记表逐项比对；仅校验代码侧指标数。")
            check(implementedIds.size == 46) {
                "Metric register in code must stay at 46 ids, found ${implementedIds.size}."
            }
        } else {
            val documentedIds = Regex("(?m)^\\| ([A-GPRX][1-6]) \\|").findAll(design)
                .map { it.groupValues[1] }.toSet()
            check(documentedIds.size == 46 && documentedIds == implementedIds) {
                "Metric register differs from the design contract: documented=${documentedIds.size}, code=${implementedIds.size}."
            }
        }
        // 质量闸 F/G 的静态口径检查：除 SessionScopes 外，统计/诊断/处方层不得直接判断 mode/kind。
        fileTree("src/main/java/com/tiji/studyclock/domain").matching { include("**/*.kt") }.forEach { source ->
            if (source.name == "SessionScopes.kt" || source.name == "RecordMode.kt") return@forEach
            val text = source.readText()
            check(!Regex("\\.mode\\s*==|\\.kind\\s*==").containsMatchIn(text)) {
                "Mode/kind filtering must go through SessionScopes: ${source.name}"
            }
        }
        val csvHeader = file("src/main/java/com/tiji/studyclock/data/export/CsvWriter.kt").readText()
        check(Regex("\"记录模式\".*?\"得分率\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(csvHeader)) {
            "CSV 20-column header must keep columns 1-14 and append the 6 v5 columns."
        }
        val seed = file("src/main/java/com/tiji/studyclock/data/seed/CategorySeed.kt").readText()
        // 契约只约束考公的 43 条基线；其他考试的预置骨架单独维护，不参与该契约。
        val baseline = seed.substringAfter("val categories: List<Category>").substringBefore("val extraSubjects")
        val names = Regex("Category\\(\\d+,\\s*\\d+,\\s*\"([^\"]+)\"").findAll(baseline).map { it.groupValues[1] }.toList()
        check(names.size == 43) { "Preset category baseline must stay at 43, found ${names.size}." }
        if (design == null) {
            logger.lifecycle("checkDesignContracts: 未找到《软件设计书》，跳过 43 条预置类别与文档的逐项比对；仅校验条数。")
        } else {
            val catalog = design.substringAfter("### 7.2 预置分类").substringBefore("## 8.")
            check(names.all { catalog.contains(it) }) { "Preset categories differ from the design contract." }
        }
    }
}
tasks.named("preBuild") { dependsOn(checkArchitecture, checkDesignContracts) }

tasks.register("verifyReleaseSigning") {
    doLast { check(hasReleaseSigning) { "Configure key.properties with your release signing key before publishing." } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
