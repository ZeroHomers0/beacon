package io.github.beacon.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the layer direction that the package layout already implies.
 *
 * `workers/tools/architecture.mjs` does the same job for the TypeScript service; this is the Kotlin
 * equivalent, kept as a unit test so it runs in the same job as everything else and needs no new
 * Gradle plugin. The rules are what the code actually obeys today — the point is to keep a future
 * edit from quietly inverting a dependency, not to describe an ideal.
 */
class ArchitectureLayeringTest {
    @Test
    fun domainStaysFreeOfFrameworkAndOuterLayers() {
        val offenders =
            importsFrom(DOMAIN, forbidden = setOf("android.", "io.github.beacon.app.data.", "io.github.beacon.app.runtime.", "io.github.beacon.app.ipc.", "io.github.beacon.app.ui.", "io.github.beacon.app.feature."))
        assertTrue(
            "domain/ must not depend on the framework or on outer layers:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun dataDoesNotDependOnUi() {
        val offenders =
            importsFrom(DATA, forbidden = setOf("io.github.beacon.app.ui.", "io.github.beacon.app.feature."))
        assertTrue("data/ must not depend on ui/ or feature/:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun runtimeDoesNotDependOnUi() {
        val offenders =
            importsFrom(RUNTIME, forbidden = setOf("io.github.beacon.app.ui.", "io.github.beacon.app.feature."))
        assertTrue(
            "runtime/ must not depend on ui/ or feature/:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun ipcDoesNotDependOnUi() {
        val offenders =
            importsFrom(IPC, forbidden = setOf("io.github.beacon.app.ui.", "io.github.beacon.app.feature."))
        assertTrue("ipc/ must not depend on ui/ or feature/:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * Guards the guards: a scan that silently finds nothing would make every rule above pass.
     */
    @Test
    fun scanSeesTheSources() {
        val scanned =
            listOf(DOMAIN, DATA, RUNTIME, IPC)
                .sumOf { packageName ->
                    File(sourceRoot(), packageName)
                        .walkTopDown()
                        .count { it.isFile && it.extension == "kt" }
                }
        assertTrue("expected to scan the app sources, found $scanned files", scanned > 100)
    }

    private fun importsFrom(packageName: String, forbidden: Set<String>): List<String> {
        val directory = File(sourceRoot(), packageName.replace('.', '/'))
        assertTrue("${directory.path} is missing", directory.isDirectory)
        return directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val imported = IMPORT.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                    val reason = forbidden.firstOrNull { imported.startsWith(it) } ?: return@mapNotNull null
                    "${file.relativeTo(sourceRoot()).path}:${index + 1}: $imported ($reason)"
                }
            }
            .toList()
    }

    private fun sourceRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "src/main/java/io/github/beacon/app")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("could not locate src/main/java from ${File("").absolutePath}")
    }

    private companion object {
        const val DOMAIN = "domain"
        const val DATA = "data"
        const val RUNTIME = "runtime"
        const val IPC = "ipc"
        val IMPORT = Regex("""^import\s+([\w.]+)""")
    }
}
