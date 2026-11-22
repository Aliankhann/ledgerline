package dev.ledgerline.testsupport

import java.io.File

/** Reads production source files for wiring checks. Tests only; never edits anything. */
object SourceText {
    /** The file at repo-relative [rel], found by walking up from the working directory. */
    fun read(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, rel).exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repo root not found for $rel" }, rel).readText()
    }

    /**
     * Kotlin [source] without `//` line comments and `/* */` block comments (nested, as Kotlin allows). String and
     * char literals are kept as they are, so a `//` inside a string does not start a comment. Raw strings are handled.
     */
    fun withoutComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    out.append(source, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && source[j] != c) j += if (source[j] == '\\') 2 else 1
                    val end = minOf(j + 1, n)
                    out.append(source, i, end)
                    i = end
                }
                c == '/' && next == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> {
                                depth++
                                i += 2
                            }
                            source.startsWith("*/", i) -> {
                                depth--
                                i += 2
                            }
                            else -> i++
                        }
                    }
                    out.append(' ')
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
