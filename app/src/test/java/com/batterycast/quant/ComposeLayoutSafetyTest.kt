package com.batterycast.quant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Static guards against Compose layout values that only fail at composition time.
 *
 * Some Compose modifiers validate their arguments with `require(...)` rather than at the type
 * level, so an illegal constant compiles cleanly and then throws on the first frame that composes
 * it. On a start destination that is an immediate launch crash, and because these screens are only
 * reachable with a live `ViewModel`, neither the compiler nor the JVM unit tests exercise them.
 *
 * The specific trap is negative padding, which is tempting whenever a row needs to bleed past its
 * parent's inset: `Modifier.padding(horizontal = (-16).dp)` looks reasonable and throws
 * `IllegalArgumentException("Padding must be non-negative")`. The supported way round is the
 * opposite — the container carries no inset and each item opts in — which is what the screens with
 * full-bleed chip rows do.
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
    fun `production sources are actually present`() {
        assertThat(mainSources.size).isGreaterThan(30)
    }

    @Test
    fun `no padding modifier is given a negative value`() {
        // Matches `padding(-8.dp)`, `padding(horizontal = (-20).dp)` and the named-argument forms,
        // all of which throw IllegalArgumentException when composed.
        val negativePadding = Regex("""\bpadding\s*\([^)]*(\(\s*-|=\s*-)\s*\d""")

        assertThat(scan { negativePadding.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `no size, width or height modifier is given a negative value`() {
        // `SizeElement` requires non-negative values for the same reason.
        val negativeSize = Regex(
            """\b(size|width|height|requiredSize|requiredWidth|requiredHeight)\s*\([^)]*(\(\s*-|=\s*-)\s*\d""",
        )

        assertThat(scan { negativeSize.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `spacedBy is never given a negative spacing`() {
        // `Arrangement.spacedBy` accepts negatives, but a negative gap in a LazyRow makes item
        // positions non-monotonic and the list throws while measuring.
        val negativeSpacing = Regex("""spacedBy\s*\(\s*\(?\s*-\s*\d""")

        assertThat(scan { negativeSpacing.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `weight is never given a value of zero or less`() {
        // `Modifier.weight` requires a strictly positive value, and the fractional bars in the UI
        // compute their weights from live data — so the clamps that keep those weights positive are
        // load-bearing, not defensive.
        val nonPositiveWeight = Regex("""\bweight\s*\(\s*(0f|0\.0f|0)\s*[,)]""")

        assertThat(scan { nonPositiveWeight.containsMatchIn(it) }).isEmpty()
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
