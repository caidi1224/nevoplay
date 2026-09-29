package com.shilapi.xcertplay

/**
 * Filters what reaches the session log, because that file leaves the device.
 *
 * The log already contained a Wi-Fi passphrase in clear text (the iAP2 `0x5703` hand-off message
 * carries it), together with the protocol dumps around it. Sessions are logged continuously from
 * protocol, wireless and media code, so redaction has to happen on the way to storage - doing it at
 * export time would leave the plaintext sitting in `Download/xcertplay/` in the meantime.
 *
 * Two different treatments:
 *  - **Dropped entirely** - lines mentioning a passphrase, password, token or key material. Their
 *    surrounding context is not worth the secret, and a partially masked secret is still a secret
 *    leak waiting for a cleverer regex.
 *  - **Masked in place** - SSID values, MAC addresses and long hex runs (protocol dumps). The line
 *    keeps its shape, which is what makes the log useful for timing analysis.
 *
 * IPv4/IPv6 literals are deliberately **not** masked: on this device they are private P2P addresses,
 * and a pattern broad enough to catch them also catches version strings and timestamps.
 */
internal object DiagnosticRedactor {
    private val SECRET = Regex(
        "(?i)(passphrase|password|psk|secret|token|private key|BEGIN [A-Z ]*PRIVATE KEY" +
            "|pairing record|identity\\.pk8)",
    )
    private val SSID_VALUE = Regex("(?i)(ssid\\s*[=:]\\s*)(\"[^\"]*\"|[^,\\s}\"]+)")
    private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")
    private val LONG_HEX = Regex("\\b[0-9A-Fa-f]{32,}\\b")

    /** Returns the line as it should be stored, or null when it must not be stored at all. */
    fun redact(line: String): String? {
        if (SECRET.containsMatchIn(line)) return null
        var masked = SSID_VALUE.replace(line) { match ->
            match.groupValues[1] + "[ssid]"
        }
        masked = MAC.replace(masked, "[mac]")
        masked = LONG_HEX.replace(masked, "[hex]")
        return if (masked.length > MAX_LINE_CHARS) masked.take(MAX_LINE_CHARS) + "…" else masked
    }

    /** One log line is capped so a runaway dump cannot dominate the file. */
    const val MAX_LINE_CHARS = 700
}
