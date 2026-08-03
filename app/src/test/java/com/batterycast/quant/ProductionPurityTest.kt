package com.batterycast.quant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The build-time guarantee that the shipped app contains no invented data.
 *
 * This test reads the production sources themselves rather than exercising behaviour, because the
 * property being asserted is structural: it is not that the app *currently* returns real numbers,
 * it is that there is no code path in `src/main` capable of returning anything else.
 *
 * It runs on every `check` and every `assemble`, alongside the equivalent Gradle tasks
 * (`verifyNoSampleData`, `verifyProductionTelemetryBinding`, `verifyNoNetworkPermission`), so the
 * guarantee holds whether the build is driven from Gradle or from an IDE test run.
 */
class ProductionPurityTest {

    private val projectDir: File = findProjectDir()
    private val mainSourceDir = File(projectDir, "src/main")
    private val mainSources: List<File> = mainSourceDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `production sources are actually present`() {
        assertThat(mainSourceDir.exists()).isTrue()
        assertThat(mainSources.size).isGreaterThan(30)
    }

    @Test
    fun `no fake, demo, sample or stub type is declared in production code`() {
        val bannedDeclaration = Regex(
            """\b(class|object|interface)\s+\w*(Fake|Mock|Demo|Sample|Stub|Dummy|Seed)\w*""",
        )

        val violations = scan { line -> bannedDeclaration.containsMatchIn(line) }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `no sample-data generator function exists in production code`() {
        val bannedFunction = Regex(
            """\bfun\s+\w*(generateDemo|generateSample|seedSample|fakeObservation|dummyObservation)\w*""",
        )

        assertThat(scan { bannedFunction.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `no sample-data constant is declared in production code`() {
        // Mirrors the Gradle gate exactly, so a violation fails whether the build is driven from
        // Gradle or from an IDE test run.
        val bannedConstant = Regex("""\bval\s+\w*(DEMO_|SAMPLE_|FAKE_)\w*""")

        assertThat(scan { bannedConstant.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `randomness appears only where the future is being simulated`() {
        // Randomness is legitimate in the Monte Carlo simulator, which models uncertainty about
        // the future. It is never legitimate for producing an observation.
        val allowedPackages = listOf(
            "forecasting/sim",
            "forecasting/scenario",
            "forecasting/planner",
            "forecasting/uncertainty",
        )
        val randomUsage = Regex("""\b(kotlin\.random\.Random|java\.util\.Random|Math\.random)\b""")

        val violations = scan(
            skipFile = { file -> allowedPackages.any { relativePath(file).contains(it) } },
            predicate = { randomUsage.containsMatchIn(it) },
        )

        assertThat(violations).isEmpty()
    }

    @Test
    fun `the production graph binds telemetry to the real Android implementation`() {
        val diSources = mainSources.filter { relativePath(it).contains("/di/") }
        val bindings = diSources.flatMap { file ->
            file.readLines().mapNotNull { line ->
                Regex("""bind\w*\s*\(\s*\w*\s*:\s*(\w+)\s*\)\s*:\s*BatteryTelemetrySource""")
                    .find(line.substringBefore("//"))
                    ?.groupValues
                    ?.get(1)
            }
        }

        assertThat(bindings).isNotEmpty()
        assertThat(bindings).containsExactly("AndroidBatteryTelemetrySource")
    }

    @Test
    fun `only one production implementation of the telemetry interface exists`() {
        val implementations = mainSources.filter { file ->
            file.readText().contains(Regex(""":\s*BatteryTelemetrySource\s*\{"""))
        }.map { it.nameWithoutExtension }

        assertThat(implementations).containsExactly("AndroidBatteryTelemetrySource")
    }

    @Test
    fun `no production source references the test fixtures`() {
        val violations = scan { line ->
            line.contains("com.batterycast.quant.fixtures") ||
                line.contains("ObservationFixtures") ||
                line.contains("SnapshotFixtures")
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `the database is never prepopulated from an asset or a callback`() {
        // Room offers `createFromAsset`, `createFromFile` and `addCallback`, each of which could
        // put rows into the database that the device never produced.
        val violations = scan { line ->
            line.contains("createFromAsset") ||
                line.contains("createFromFile") ||
                line.contains(".addCallback(")
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `the manifest requests no network permission`() {
        val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

        val granted = Regex(
            """<uses-permission[^>]*android:name="android\.permission\.INTERNET"(?![^>]*tools:node="remove")""",
        ).containsMatchIn(manifest)

        assertThat(granted).isFalse()
        // The removal directive must stay, so a dependency cannot add the permission by merging.
        assertThat(manifest).contains("""android:name="android.permission.INTERNET" tools:node="remove"""")
    }

    @Test
    fun `no analytics, advertising or crash-reporting dependency is declared`() {
        val buildFile = File(projectDir, "build.gradle.kts").readText()
        val bannedDependencies = listOf(
            "firebase",
            "crashlytics",
            "com.google.android.gms:play-services-ads",
            "appsflyer",
            "amplitude",
            "mixpanel",
            "bugsnag",
            "sentry",
        )

        bannedDependencies.forEach { dependency ->
            assertThat(buildFile.lowercase()).doesNotContain(dependency)
        }
    }

    private fun scan(
        skipFile: (File) -> Boolean = { false },
        predicate: (String) -> Boolean,
    ): List<String> = mainSources.flatMap { file ->
        if (skipFile(file)) return@flatMap emptyList()
        file.readLines().mapIndexedNotNull { index, rawLine ->
            // Comments are prose about the rules, not code that breaks them.
            val line = rawLine.substringBefore("//").trim()
            if (line.isNotEmpty() && predicate(line)) {
                "${relativePath(file)}:${index + 1}: $line"
            } else {
                null
            }
        }
    }

    private fun relativePath(file: File): String =
        file.relativeTo(projectDir).invariantSeparatorsPath

    private fun findProjectDir(): File {
        // Gradle runs unit tests with the module directory as the working directory; IDE runners
        // sometimes use the repository root instead, so both are accepted.
        var candidate = File("").absoluteFile
        repeat(4) {
            if (File(candidate, "src/main/AndroidManifest.xml").exists()) return candidate
            if (File(candidate, "app/src/main/AndroidManifest.xml").exists()) return File(candidate, "app")
            candidate = candidate.parentFile ?: return@repeat
        }
        error("Could not locate the app module directory from ${File("").absolutePath}")
    }
}
