package com.lightningstudio.watchrss.phone.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SQLiteCompatibilityTest {
    @Test fun productionDoesNotRequireSqliteJsonExtensions() {
        val source = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Missing app/src/main")
        val function = Regex("""\bjson_(extract|type|each|valid|array|object|tree)\s*\(""", RegexOption.IGNORE_CASE)
        val matches = source.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "sql") }
            .flatMap { file ->
                val content = file.readText()
                function.findAll(content).map { match ->
                    val line = content.take(match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(source)}:$line: ${match.value.trim()}"
                }
            }.toList()
        assertTrue(matches.joinToString("\n"), matches.isEmpty())
    }
}
