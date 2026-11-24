package dev.ledgerline.testsupport

import java.io.File

/** Reads the UI sources (`.ts` and `.tsx` under `ui/src`) for wiring checks. Tests only; never edits anything. */
object UiSource {
    private fun srcDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "ui/src").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "repo root not found for ui/src" }, "ui/src")
    }

    /** Every `.ts` and `.tsx` file under `ui/src`, concatenated in path order (a component may move between files). */
    fun all(): String =
        srcDir().walkTopDown()
            .filter { it.isFile && (it.extension == "ts" || it.extension == "tsx") }
            .sortedBy { it.path }
            .joinToString("\n") { it.readText() }
}
