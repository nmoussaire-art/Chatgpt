import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing is configured entirely outside version control.
 *
 * Create `keystore.properties` in the repository root (it is git-ignored) or export the
 * equivalent environment variables. When neither is present the release build simply stays
 * unsigned; no fallback debug key is ever silently substituted for a release artefact.
 * See `docs/RELEASE.md`.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "BATTERYCAST_STORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.batterycast.quant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.batterycast.quant"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.1"

        testInstrumentationRunner = "com.batterycast.quant.BatteryCastTestRunner"

        // Room schema export makes migrations reviewable and lets Room's migration
        // test harness verify them.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "BATTERYCAST_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "BATTERYCAST_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "BATTERYCAST_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/* ------------------------------------------------------------------------------------------ */
/*  Production-purity gates                                                                     */
/*                                                                                              */
/*  These tasks are wired into `check` and into every assemble task, so a build cannot produce  */
/*  an APK that packages sample data, fake telemetry, or an unexpected network permission.      */
/* ------------------------------------------------------------------------------------------ */

/**
 * Fails if production sources declare any fake / demo / sample data provider.
 *
 * The forecasting engine is only meaningful if every number on screen came from the device, so
 * this is enforced mechanically rather than by convention.
 */
val verifyNoSampleData by tasks.registering {
    group = "verification"
    description = "Fails if src/main contains fake, demo, sample or randomly generated data providers."

    val mainSources = fileTree("src/main") { include("**/*.kt", "**/*.java") }
    inputs.files(mainSources)
    val reportFile = layout.buildDirectory.file("reports/production-purity/no-sample-data.txt")
    outputs.file(reportFile)

    doLast {
        // Declaration-shaped patterns only: a comment that says "no fake data" must not trip the gate.
        val bannedDeclarations = listOf(
            Regex("""\b(class|object|interface)\s+\w*(Fake|Mock|Demo|Sample|Stub|Dummy|Seed)\w*"""),
            Regex("""\bfun\s+\w*(generateDemo|generateSample|seedSample|fakeObservation|dummyObservation)\w*"""),
            Regex("""\bval\s+\w*(DEMO_|SAMPLE_|FAKE_)\w*"""),
        )
        // Randomness in production is legitimate *only* inside the Monte Carlo simulator, which
        // simulates the future rather than inventing observations.
        val randomnessAllowlist = setOf(
            "forecasting/sim",
            "forecasting/scenario",
            "forecasting/planner",
            "forecasting/uncertainty",
        )
        val randomUsage = Regex("""\b(kotlin\.random\.Random|java\.util\.Random|Math\.random)\b""")

        val violations = mutableListOf<String>()
        mainSources.forEach { file ->
            val relative = file.relativeTo(projectDir).invariantSeparatorsPath
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore("//").trim()
                if (line.isEmpty()) return@forEachIndexed
                bannedDeclarations.forEach { pattern ->
                    if (pattern.containsMatchIn(line)) {
                        violations += "$relative:${index + 1}: banned declaration -> $line"
                    }
                }
                if (randomUsage.containsMatchIn(line) && randomnessAllowlist.none { relative.contains(it) }) {
                    violations += "$relative:${index + 1}: randomness outside the simulation package -> $line"
                }
            }
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        if (violations.isEmpty()) {
            report.writeText("PASS: no sample-data or fake-telemetry declarations in src/main\n")
        } else {
            report.writeText(violations.joinToString("\n"))
            throw GradleException(
                "Production purity check failed. src/main must never contain sample or fake data:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}

/**
 * Fails if the production Hilt graph binds [BatteryTelemetrySource] to anything other than the
 * real Android implementation.
 */
val verifyProductionTelemetryBinding by tasks.registering {
    group = "verification"
    description = "Fails if production dependency injection binds a non-Android telemetry source."

    val diSources = fileTree("src/main") { include("**/di/**/*.kt") }
    inputs.files(diSources)
    val reportFile = layout.buildDirectory.file("reports/production-purity/telemetry-binding.txt")
    outputs.file(reportFile)

    doLast {
        val bindPattern = Regex("""bind\w*\s*\(\s*\w*\s*:\s*(\w+)\s*\)\s*:\s*BatteryTelemetrySource""")
        val allowedImplementations = setOf("AndroidBatteryTelemetrySource")
        val violations = mutableListOf<String>()
        var bindingsFound = 0

        diSources.forEach { file ->
            val relative = file.relativeTo(projectDir).invariantSeparatorsPath
            file.readLines().forEachIndexed { index, rawLine ->
                val match = bindPattern.find(rawLine.substringBefore("//")) ?: return@forEachIndexed
                bindingsFound++
                val implementation = match.groupValues[1]
                if (implementation !in allowedImplementations) {
                    violations += "$relative:${index + 1}: telemetry bound to '$implementation'"
                }
            }
        }

        if (bindingsFound == 0) {
            violations += "no BatteryTelemetrySource binding found in production dependency injection"
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        if (violations.isEmpty()) {
            report.writeText("PASS: BatteryTelemetrySource bound to AndroidBatteryTelemetrySource ($bindingsFound binding(s))\n")
        } else {
            report.writeText(violations.joinToString("\n"))
            throw GradleException("Production telemetry binding check failed:\n" + violations.joinToString("\n"))
        }
    }
}

/** Fails if the merged manifest grants a network permission the app does not need. */
val verifyNoNetworkPermission by tasks.registering {
    group = "verification"
    description = "Fails if the app manifest declares INTERNET or other network transmission permissions."

    val manifest = file("src/main/AndroidManifest.xml")
    inputs.file(manifest)
    val reportFile = layout.buildDirectory.file("reports/production-purity/no-network-permission.txt")
    outputs.file(reportFile)

    doLast {
        val text = manifest.readText()
        val granted = Regex("""<uses-permission[^>]*android:name="android\.permission\.(INTERNET)"(?![^>]*tools:node="remove")""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        if (granted.isEmpty()) {
            report.writeText("PASS: no network transmission permission requested\n")
        } else {
            report.writeText("FAIL: ${granted.joinToString()}")
            throw GradleException("BatteryCast Quant must stay offline but requests: ${granted.joinToString()}")
        }
    }
}

val verifyProductionPurity by tasks.registering {
    group = "verification"
    description = "Runs every production-data purity gate."
    dependsOn(verifyNoSampleData, verifyProductionTelemetryBinding, verifyNoNetworkPermission)
}

tasks.named("check") { dependsOn(verifyProductionPurity) }
tasks.matching { it.name.startsWith("assemble") }.configureEach { dependsOn(verifyProductionPurity) }
