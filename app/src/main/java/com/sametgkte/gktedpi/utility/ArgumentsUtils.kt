package com.sametgkte.gktedpi.utility

fun shellSplit(string: CharSequence): List<String> {
    val tokens: MutableList<String> = ArrayList()
    var escaping = false
    var quoteChar = ' '
    var quoting = false
    var lastCloseQuoteIndex = Int.MIN_VALUE
    var current = StringBuilder()

    for (i in string.indices) {
        val c = string[i]

        if (escaping) {
            current.append(c)
            escaping = false
        } else if (c == '\\' && !(quoting && quoteChar == '\'')) {
            escaping = true
        } else if (quoting && c == quoteChar) {
            quoting = false
            lastCloseQuoteIndex = i
        } else if (!quoting && (c == '\'' || c == '"')) {
            quoting = true
            quoteChar = c
        } else if (!quoting && Character.isWhitespace(c)) {
            if (current.isNotEmpty() || lastCloseQuoteIndex == i - 1) {
                tokens.add(current.toString())
                current = StringBuilder()
            }
        } else {
            current.append(c)
        }
    }

    if (current.isNotEmpty() || lastCloseQuoteIndex == string.length - 1) {
        tokens.add(current.toString())
    }

    return tokens
}

fun shellQuoteJoin(args: List<String>): String =
    args.joinToString(" ") { token ->
        if (token.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            "'${token.replace("'", "'\\''")}'"
        } else {
            token
        }
    }

fun isValidPortFilter(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true
    val parts = trimmed.split("-")
    if (parts.size > 2) return false
    val p1 = parts[0].toIntOrNull() ?: return false
    if (p1 !in 1..65535) return false
    if (parts.size == 2) {
        val p2 = parts[1].toIntOrNull() ?: return false
        if (p2 !in p1..65535) return false
    }
    return true
}
