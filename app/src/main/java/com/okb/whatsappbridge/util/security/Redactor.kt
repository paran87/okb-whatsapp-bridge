package com.okb.whatsappbridge.util.security

/** Last line of defence: masks anything that looks like a credential before it is logged or shown. */
object Redactor {
    private val patterns = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+") to "$1***",
        Regex("(?i)((?:token|password|secret|apikey|api_key|authorization)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]+") to "$1***",
    )

    fun redact(input: String): String = patterns.fold(input) { acc, (regex, replacement) ->
        regex.replace(acc, replacement)
    }

    /** "abcd…wxyz" style preview for UI confirmation; never reveals more than 4 characters. */
    fun mask(secret: String?): String = when {
        secret.isNullOrEmpty() -> "Not set"
        secret.length <= 8 -> "••••••••"
        else -> "••••••••" + secret.takeLast(4)
    }
}
