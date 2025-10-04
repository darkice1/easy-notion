package easy.notion.util

import java.net.URI

/** Replace internal whitespace with %20 and trim. */
internal fun sanitizeUrl(raw: String): String =
	raw.trim().replace(Regex("\\s+"), "%20")

/** Basic validator for external HTTP(S) URLs. */
internal fun isLikelyValidHttpUrl(url: String): Boolean = try {
	val u = URI(url)
	(u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
} catch (_: Exception) {
	false
}
