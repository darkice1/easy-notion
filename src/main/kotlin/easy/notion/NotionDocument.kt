package easy.notion

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.HexFormat

/** Values and HTML from one read. Hosted file URLs are signed and may expire. */
class NotionDocument(val values: JSONObject, val hostedFiles: Set<String>) {
	/** Digest the exact content and selected inputs, excluding only temporary file signatures. */
	fun fingerprint(properties: List<String>): String {
		var content = values.getString("content")
		for (url in hostedFiles.sortedByDescending { it.length }) {
			content = content.replace(url, url.substringBefore('?').substringBefore('#'))
		}
		val input = JSONArray().put(values.optString("styleTag")).put(content)
		for (property in properties) {
			input.put(JSONArray().put(property).put(values.opt(property) ?: JSONObject.NULL))
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray()))
	}
}
