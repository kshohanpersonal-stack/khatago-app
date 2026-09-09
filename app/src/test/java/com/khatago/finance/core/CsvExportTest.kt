package com.khatago.finance.core

import com.khatago.finance.core.csv.CsvWriter
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV quoting and number formatting.
 *
 * An export is only useful if a spreadsheet reads it back as the same values that are on screen, and
 * the two ways that breaks are (a) a comma inside a name splitting a row and (b) a "৳1,234.50" cell
 * being read as text. Both are asserted here rather than eyeballed in Excel, and Bengali digits plus
 * the BOM are included because this file is meant for Bangladeshi users first.
 */
class CsvExportTest {

    @Test
    fun `a comma inside a value is quoted, not split`() {
        val row = CsvWriter().row("Rahman Store, Main Road", "1000.00").build()
        assertEquals("\"Rahman Store, Main Road\",1000.00${CsvWriter.CRLF}", row)
    }

    @Test
    fun `embedded quotes double up and the field is quoted`() {
        val escaped = CsvWriter().escape("Rahman's \"Big\" Store")
        assertEquals("\"Rahman's \"\"Big\"\" Store\"", escaped)
    }

    @Test
    fun `newlines inside a note survive as a quoted field`() {
        val value = "line one\nline two"
        val escaped = CsvWriter().escape(value)
        assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""))
        // `escape` already doubled any embedded quote and wrapped the field, so its output *is* the CSV
        // text: escaping it a second time would test the reader against a file no writer produces.
        val line = "a,b" + CsvWriter.CRLF + escaped + ",z" + CsvWriter.CRLF
        assertEquals(value, CsvWriter.parse(line)[1][0])
    }

    @Test
    fun `values with leading or trailing spaces are quoted so Excel keeps them`() {
        assertEquals("\" spaced \"", CsvWriter().escape(" spaced "))
    }

    @Test
    fun `plain values are left bare`() {
        assertEquals("Cash", CsvWriter().escape("Cash"))
        assertEquals("", CsvWriter().escape(""))
    }

    @Test
    fun `null cells become empty rather than the string null`() {
        val row = CsvWriter().row("A", null, "C").build()
        assertEquals("A,,C${CsvWriter.CRLF}", row)
    }

    @Test
    fun `lines end with CRLF so Excel on Windows reads one row per line`() {
        val text = CsvWriter.build(listOf("a", "b"), listOf(listOf("1", "2")), withBom = false)
        assertEquals("a,b${CsvWriter.CRLF}1,2${CsvWriter.CRLF}", text)
    }

    @Test
    fun `the bom is written once at the very start when requested`() {
        val text = CsvWriter.build(listOf("a"), emptyList(), withBom = true)
        assertEquals('﻿', text.first())
        assertFalse(text.drop(1).contains('﻿'))
    }

    @Test
    fun `write then read is lossless for the messy fields a ledger actually contains`() {
        val columns = listOf("Shop", "Item", "Total", "Note")
        val rows = listOf(
            listOf("Rahman Store, Main Rd", "Rice 5kg \"premium\"", "1234.50", "paid 500, rest later"),
            listOf("Karim Bhaban", "Oil\n2 boxes", "980.00", null),
            listOf("লোকাল স্টোর", "ডাল", "45.25", "বাকি"),
        )
        val text = CsvWriter.build(columns, rows)
        val parsed = CsvWriter.parse(text)

        assertEquals(4, parsed.size)
        assertEquals(columns, parsed[0])
        assertEquals("Rahman Store, Main Rd", parsed[1][0])
        assertEquals("Rice 5kg \"premium\"", parsed[1][1])
        assertEquals("1234.50", parsed[1][2])
        assertEquals("paid 500, rest later", parsed[1][3])
        assertEquals("Oil\n2 boxes", parsed[2][1])
        assertEquals("", parsed[2][3])
        assertEquals("লোকাল স্টোর", parsed[3][0])
        assertEquals("বাকি", parsed[3][3])
    }

    @Test
    fun `an empty export has a header only and is still valid csv`() {
        val writer = CsvWriter()
        assertTrue(writer.isEmpty())
        writer.header("Amount")
        assertFalse(writer.isEmpty())
        assertEquals(listOf(listOf("Amount")), CsvWriter.parse(writer.build()))
    }

    @Test
    fun `amount cells are plain major-unit numbers so a spreadsheet sums them`() {
        val bdt = CurrencySpec.BDT
        val cells = listOf(0L, 5L, 1_000L, 123_450L, 9_876_543_210L).map { MoneyFormat.toCsvNumber(it, bdt) }
        assertEquals(listOf("0.00", "0.05", "10.00", "1234.50", "98765432.10"), cells)
        // The column must be parseable as a number by anything that reads CSV.
        cells.forEach { cell ->
            assertTrue("'$cell' is not a plain decimal", cell.all { it.isDigit() || it == '.' })
        }
        // 0.00 + 0.05 + 10.00 + 1,234.50 + 98,765,432.10 in minor units — i.e. the exported column
        // still adds up to exactly what the ledger holds.
        assertEquals(0L + 5L + 1_000L + 123_450L + 9_876_543_210L, cells.sumOf { (it.toDouble() * 100).toLong() })
    }
}
