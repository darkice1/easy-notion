package easy.notion.block

import easy.notion.util.isLikelyValidHttpUrl
import easy.notion.util.sanitizeUrl
import org.json.JSONArray
import org.json.JSONObject

internal fun buildRichSpan(
	text: String,
	bold: Boolean = false,
	italic: Boolean = false,
): JSONObject = JSONObject().apply {
	put("type", "text")
	put("text", JSONObject().put("content", text))
	put(
		"annotations",
		JSONObject().apply {
			put("bold", bold)
			put("italic", italic)
			put("strikethrough", false)
			put("underline", false)
			put("code", false)
			put("color", "default")
		},
	)
}

internal fun buildLinkedRichSpan(
	text: String,
	url: String,
	bold: Boolean = false,
	italic: Boolean = false,
): JSONObject {
	val sanitized = sanitizeUrl(url)
	if (!isLikelyValidHttpUrl(sanitized)) return buildRichSpan(text, bold, italic)

	val span = buildRichSpan(text, bold, italic)
	span.getJSONObject("text").put(
		"link",
		JSONObject().apply {
			put("type", "url")
			put("url", sanitized)
		},
	)
	return span
}

internal fun applyStyle(arr: JSONArray, addBold: Boolean = false, addItalic: Boolean = false): JSONArray {
	for (i in 0 until arr.length()) {
		val span = arr.getJSONObject(i)
		val ann = span.getJSONObject("annotations")
		if (addBold) ann.put("bold", true)
		if (addItalic) ann.put("italic", true)
	}
	return arr
}
