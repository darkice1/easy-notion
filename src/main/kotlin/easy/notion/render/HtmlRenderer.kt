package easy.notion.render

import org.json.JSONArray
import org.json.JSONObject

internal fun renderRichText(richArray: JSONArray): String {
	val sb = StringBuilder()

	for (i in 0 until richArray.length()) {
		val obj = richArray.getJSONObject(i)
		val type = obj.getString("type")
		val (rawContent, linkJson) = when (type) {
			"text" -> {
				val textObj = obj.getJSONObject("text")
				textObj.getString("content") to textObj.optJSONObject("link")
			}

			else -> obj.getString("plain_text") to null
		}

		val imgRegex = Regex("""!\[([^]]*)]\s*\(([^)]+)\)""")
		val imgMatch = imgRegex.matchEntire(rawContent.trim())
		if (imgMatch != null) {
			val alt = imgMatch.groupValues[1]
			val url = imgMatch.groupValues[2]
			sb.append("<img src=\"").append(url)
				.append("\" alt=\"")
				.append(alt.replace("\"", "&quot;"))
				.append("\" decoding=\"async\"/>")
			continue
		}

		val ann = obj.getJSONObject("annotations")
		var formatted = rawContent

		if (ann.optBoolean("code")) formatted = "<code>$formatted</code>"
		if (ann.optBoolean("bold")) formatted = "<strong>$formatted</strong>"
		if (ann.optBoolean("italic")) formatted = "<em>$formatted</em>"
		if (ann.optBoolean("underline")) formatted = "<u>$formatted</u>"
		if (ann.optBoolean("strikethrough")) formatted = "<del>$formatted</del>"

		val color = ann.optString("color", "default")
		if (color != "default") {
			formatted = if (color.endsWith("_background")) {
				val bg = color.removeSuffix("_background")
				"<span style=\"background-color:$bg;\">$formatted</span>"
			} else {
				"<span style=\"color:$color;\">$formatted</span>"
			}
		}

		if (linkJson != null && linkJson.has("url")) {
			val url = linkJson.getString("url")
			sb.append("<a href=\"").append(url).append("\" target=\"_blank\">")
				.append(formatted).append("</a>")
		} else {
			sb.append(formatted)
		}
	}

	return sb.toString()
}

internal fun renderHeadingHtml(level: String, contentArr: JSONArray): String {
	val content = renderRichText(contentArr)
	return "<h$level>$content</h$level>"
}

internal fun renderListItemHtml(contentArr: JSONArray): String {
	val content = renderRichText(contentArr)
	return "<li>$content</li>"
}

internal fun renderGenericBlockHtml(block: JSONObject): String {
	val type = block.getString("type")
	val data = block.optJSONObject(type) ?: return ""
	val rich = data.optJSONArray("rich_text") ?: return ""
	val content = renderRichText(rich)
	return "<div class=\"notion-$type\">$content</div>"
}
