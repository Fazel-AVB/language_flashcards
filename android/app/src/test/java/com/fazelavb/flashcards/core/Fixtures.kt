package com.fazelavb.flashcards.core

import java.time.Instant
import java.time.ZoneOffset

val NOW: Instant = Instant.parse("2026-10-03T09:00:00Z")
val UTC: ZoneOffset = ZoneOffset.UTC

fun resource(name: String): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(name)) { "missing $name" }.readBytes()

fun germanBytes() = resource("vocab_de.xlsx")
fun persianBytes() = resource("vocab_en_fa.xlsx")
fun germanCards() = Workbook.load(germanBytes()).cards
fun List<Card>.byTerm(term: String) = first { it.term == term }

fun unfuzzed() = Srs.makeScheduler(fuzz = false)
