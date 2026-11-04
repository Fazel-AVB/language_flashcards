package com.fazelavb.flashcards.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The unit tests run on the JVM, but the app runs on Android, whose regex engine
 * (ICU) rejects some Java-only syntax at runtime. The inline (?U)/(?u) flags
 * crashed the app on opening a workbook, so the sources must not use them.
 */
class RegexPortabilityTest {
    @Test fun noJavaOnlyInlineFlags() {
        val dir = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val javaOnly = Regex("""\(\?[a-zA-Z]*[Uu][a-zA-Z]*[):]""")
        val hits = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (javaOnly.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertTrue("Java-only regex flags (not supported on Android):\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
