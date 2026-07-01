package com.smartsystem.autoclicker

/**
 * Utility for extracting the sso_key value from raw cookie strings.
 *
 * Input example:
 *   "_ga=GA1.1...; sso_key=e0556341dfa9a00374beb0305cf647b1b4eefb2dd72c751089cb09a582b39642; ac_session=..."
 *
 * Output: "e0556341dfa9a00374beb0305cf647b1b4eefb2dd72c751089cb09a582b39642"
 */
object SsoKeyHelper {

    private val SSO_KEY_REGEX = Regex("""sso_key=([a-fA-F0-9]{32,})""")

    /**
     * Returns the raw sso_key VALUE (no prefix) if found in [text], else null.
     */
    fun extractSsoKey(text: String): String? =
        SSO_KEY_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /**
     * Returns true if [text] contains a sso_key cookie entry.
     */
    fun containsSsoKey(text: String): Boolean =
        SSO_KEY_REGEX.containsMatchIn(text)

    /**
     * Masks the key for safe display: shows first 8 + last 4 chars, rest as ●
     */
    fun mask(key: String): String {
        if (key.length <= 12) return "●".repeat(key.length)
        return key.take(8) + "●".repeat(key.length - 12) + key.takeLast(4)
    }
}
