package io.github.beacon.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the geometry scale in [io.github.beacon.app.ui.components.BeaconUiMetrics] from eroding.
 *
 * Spacing inside a row is the one place a literal always looks harmless, so it is the first place
 * the scale decays back into per-call-site numbers. The check is textual on purpose: a real lint
 * rule would need its own Gradle module, and this runs in the existing unit test job.
 */
class UiMetricsDisciplineTest {
    @Test
    fun spacedByUsesTheSharedScale() {
        val offenders =
            kotlinSources().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> SPACED_BY_LITERAL.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
        assertTrue(
            "Use BeaconUiMetrics.GapN instead of a dp literal:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun kotlinSources(): List<File> {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val sources = File(directory, "src/main/java")
            if (sources.isDirectory) return sources.walkTopDown().filter { it.extension == "kt" }.toList()
            directory = directory.parentFile
        }
        throw AssertionError("could not locate src/main/java from ${File("").absolutePath}")
    }

    private companion object {
        val SPACED_BY_LITERAL = Regex("""spacedBy\(\s*\d+(?:\.\d+)?\.dp""")
    }
}
