package dev.ledgerline.testsupport

import java.io.File

/** Reads environment values from the repository's `docker-compose.yml`, which is how the stack runs in practice. */
object ComposeFile {
    val file: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docker-compose.yml") }
            .firstOrNull { it.isFile }
            ?: error("docker-compose.yml not found above ${File("").absolutePath}")
    }

    /** The first value of environment key [key] (for example `POSTGRES_USER`) anywhere in the compose file. */
    fun env(key: String): String =
        Regex("""^\s*$key:\s*"?([^"\s#]+)"?\s*$""", RegexOption.MULTILINE)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?: error("$key not found in $file")
}
