// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

/** Request the same desktop website that Chrome's "Desktop site" mode requests on Android.
 * This selects the HTML/catalog presentation; it does not claim a different DRM identity.
 * The installed browser's Chromium engine version is retained rather than hardcoded.
 * Reference: https://developer.chrome.com/blog/desktop-mode
 */
object NetflixBrowserContext {
    private val chromeToken = Regex("\\bChrome/[0-9]+(?:\\.[0-9]+){1,3}\\b")
    private val webKitToken = Regex("\\bAppleWebKit/[0-9]+(?:\\.[0-9]+)+\\b")
    private val safariToken = Regex("\\bSafari/[0-9]+(?:\\.[0-9]+)+\\b")

    fun desktopUserAgent(userAgent: String): String {
        if (!isMobile(userAgent)) return userAgent
        val chrome = chromeToken.find(userAgent)?.value ?: return userAgent
        val webKit = webKitToken.find(userAgent)?.value ?: return userAgent
        val safari = safariToken.find(userAgent)?.value ?: return userAgent
        return "Mozilla/5.0 (X11; Linux x86_64) $webKit (KHTML, like Gecko) $chrome $safari"
    }

    fun contextTag(userAgent: String): String = when {
        isMobile(userAgent) -> "mobile"
        userAgent.contains("(X11; Linux x86_64)") && chromeToken.containsMatchIn(userAgent) -> "desktop_site"
        else -> "other"
    }

    /** Low-entropy hints for native HTTP match the selected desktop-site context.
     * No hint values are manufactured for an unknown or still-mobile browser string. */
    fun lowEntropyClientHints(userAgent: String): Map<String, String> {
        if (contextTag(userAgent) != "desktop_site") return emptyMap()
        val major = chromeToken.find(userAgent)?.value?.substringAfter('/')?.substringBefore('.')
            ?.toIntOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return linkedMapOf(
            "Sec-CH-UA" to "\"Chromium\";v=\"$major\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Linux\"",
        )
    }

    private fun isMobile(userAgent: String): Boolean = userAgent.contains("Android", true) ||
        userAgent.contains("; wv", true) || userAgent.contains(" Mobile ", true)

    /** A changed browser context requires a fresh Netflix ESN/config; never pair the new UA
     * with an old mobile ESN. Credentials and the selected profile remain the user's own. */
    fun migratedSession(previous: SessionData): SessionData? {
        val desktop = desktopUserAgent(previous.userAgent)
        if (desktop == previous.userAgent) return null
        return previous.copy(userAgent=desktop, esn="", buildIdentifier="", clientVersion="", uiVersion="",
            browserVersion="", osName="", osVersion="")
    }
}
