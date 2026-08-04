package com.batterycast.quant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Static guards against Compose layout values that only fail at composition time.
 *
 * Some Compose modifiers validate their arguments with `require(...)` rather than at the type
 * level, so an illegal constant compiles cleanly and then throws on the first frame that composes
 * it. That is not a cosmetic defect: on a start destination it is an immediate launch crash, and
 * because these screens are only reachable with a live `ViewModel`, neither the compiler nor the
 * JVM unit tests exercise them.
 *
 * v2 shipped exactly that — `Modifier.padding(horizontal = (-20).dp)` on the target-chip row, used
 * to make the row bleed past its parent's inset. `PaddingElement` rejects negative values, so the
 * home screen crashed before drawing anything. The bleed is now expressed the other way round: the
 * container carries no horizontal padding and each item opts in via `gutterItem`.
 *
 * These tests read the production sources rather than composing them, in the same spirit as
 * [ProductionPurityTest]: the property being asserted is structural.
 */
class ComposeLayoutSafetyTest {

    private val projectDir: File = findProjectDir()
    private val mainSources: List<File> = File(projectDir, "src/main").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `no padding modifier is given a negative value`() {
        // Matches `padding(-8.dp)`, `padding(horizontal = (-20).dp)` and the named-argument forms,
        // which all throw IllegalArgumentException("Padding must be non-negative") when composed.
        val negativePadding = Regex("""\bpadding\s*\([^)]*(\(\s*-|=\s*-|\(\s*-)\s*\d""")

        val violations = scan { negativePadding.containsMatchIn(it) }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `no size, width or height modifier is given a negative value`() {
        // `SizeElement` requires non-negative values for the same reason.
        val negativeSize = Regex("""\b(size|width|height|requiredSize|requiredWidth|requiredHeight)\s*\([^)]*(\(\s*-|=\s*-)\s*\d""")

        assertThat(scan { negativeSize.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `spacedBy is never given a negative spacing inside a scrolling row`() {
        // `Arrangement.spacedBy` accepts negatives, but a negative gap in a LazyRow makes item
        // positions non-monotonic and the list throws while measuring.
        val negativeSpacing = Regex("""spacedBy\s*\(\s*\(?\s*-\s*\d""")

        assertThat(scan { negativeSpacing.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `the screen gutter is applied per item rather than as container padding`() {
        // The two screens that carry a full-bleed chip row must not reintroduce horizontal
        // contentPadding on their LazyColumn, or the chips clip at the card edge again.
        val bleedScreens = listOf(
            "feature/home/HomeScreen.kt",
            "feature/scenarios/ScenarioScreen.kt",
        )

        bleedScreens.forEach { path ->
            val source = File(projectDir, "src/main/java/com/batterycast/quant/$path")
            assertThat(source.exists()).isTrue()

            val text = source.readText()
            assertThat(text).contains("gutterItem")
            // The LazyColumn's contentPadding block must not set a horizontal inset.
            val contentPadding = Regex(
                """contentPadding\s*=\s*PaddingValues\(([^)]*)\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(text).map { it.groupValues[1] }.toList()

            val columnPadding = contentPadding.filter { it.contains("calculateBottomPadding") }
            assertThat(columnPadding).isNotEmpty()
            columnPadding.forEach { arguments ->
                assertThat(arguments).doesNotContain("start =")
                assertThat(arguments).doesNotContain("end =")
                assertThat(arguments).doesNotContain("horizontal =")
            }
        }
    }

    private fun scan(predicate: (String) -> Boolean): List<String> = mainSources.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, rawLine ->
            val line = stripComment(rawLine)
            if (line.isNotEmpty() && predicate(line)) {
                "${file.relativeTo(projectDir).invariantSeparatorsPath}:${index + 1}: $line"
            } else {
                null
            }
        }
    }

    /** Documentation describes the banned patterns, so prose must not trip the scan. */
    private fun stripComment(rawLine: String): String {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
        return trimmed.substringBefore("//").trim()
    }

    private fun findProjectDir(): File {
        var candidate = File("").absoluteFile
        repeat(4) {
            if (File(candidate, "src/main/AndroidManifest.xml").exists()) return candidate
            if (File(candidate, "app/src/main/AndroidManifest.xml").exists()) return File(candidate, "app")
            candidate = candidate.parentFile ?: return@repeat
        }
        error("Could not locate the app module directory from ${File("").absolutePath}")
    }
}
