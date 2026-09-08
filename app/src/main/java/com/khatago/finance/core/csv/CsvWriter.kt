package com.khatago.finance.core.csv

/**
 * RFC 4180 CSV writer.
 *
 * Deliberately dependency-free and unit-tested: a corrupt export is the kind of failure a user
 * discovers only after they have already trusted the file, so quoting/escaping rules live here
 * once instead of in every screen. Output uses CRLF line endings and a UTF-8 BOM (added by the
 * writer's callers via [Bom]) so Excel on Windows opens Bangladeshi amounts and names correctly.
 */
class CsvWriter(
    private val separator: Char = ',',
) {
    private val builder = StringBuilder()
    private var hasRows = false

    fun row(values: List<String?>): CsvWriter {
        values.forEachIndexed { index, value ->
            if (index > 0) builder.append(separator)
            builder.append(escape(value ?: ""))
        }
        builder.append(CRLF)
        hasRows = true
        return this
    }

    fun row(vararg values: String?): CsvWriter = row(values.toList())

    fun header(vararg columns: String): CsvWriter = row(columns.toList())

    fun isEmpty(): Boolean = !hasRows

    fun build(): String = builder.toString()

    /**
     * Quote when the value contains a separator, quote, CR or LF, or when leading/trailing
     * whitespace would otherwise be lost. Embedded quotes double up, per the spec.
     */
    internal fun escape(value: String): String {
        val needsQuotes = value.any { it == separator || it == '"' || it == '\n' || it == '\r' } ||
            value != value.trim()
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    companion object {
        const val CRLF = "\r\n"

        /** Excel needs the BOM to detect UTF-8 for non-ASCII names such as `রহমান` or `৳`. */
        const val Bom = "﻿"

        fun build(columns: List<String>, rows: List<List<String?>>, withBom: Boolean = true): String {
            val writer = CsvWriter()
            writer.row(columns)
            rows.forEach { writer.row(it) }
            return (if (withBom) Bom else "") + writer.build()
        }

        /**
         * Reverses [escape] for round-trip tests and for reading back an exported CSV.
         * Handles quoted fields, doubled quotes and CRLF/LF endings.
         */
        fun parse(text: String, separator: Char = ','): List<List<String>> {
            val body = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
            val rows = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var field = StringBuilder()
            var inQuotes = false
            var index = 0
            while (index < body.length) {
                val char = body[index]
                when {
                    inQuotes && char == '"' && index + 1 < body.length && body[index + 1] == '"' -> {
                        field.append('"'); index++
                    }

                    char == '"' -> inQuotes = !inQuotes
                    !inQuotes && char == separator -> {
                        current.add(field.toString()); field = StringBuilder()
                    }

                    !inQuotes && (char == '\r' || char == '\n') -> {
                        if (char == '\r' && index + 1 < body.length && body[index + 1] == '\n') index++
                        current.add(field.toString()); field = StringBuilder()
                        if (current.any { it.isNotEmpty() }) rows.add(current.toList())
                        current = mutableListOf()
                    }

                    else -> field.append(char)
                }
                index++
            }
            if (field.isNotEmpty() || current.isNotEmpty()) {
                current.add(field.toString())
                if (current.any { it.isNotEmpty() }) rows.add(current.toList())
            }
            return rows
        }
    }
}
