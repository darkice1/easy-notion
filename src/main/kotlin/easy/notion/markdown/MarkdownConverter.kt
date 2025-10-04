package easy.notion.markdown

import easy.notion.block.applyStyle
import easy.notion.block.buildLinkedRichSpan
import easy.notion.block.buildRichSpan
import easy.notion.media.ImageBlockBuilder
import easy.notion.util.sanitizeUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class MarkdownConverter(
	private val imageBlockBuilder: ImageBlockBuilder,
) {

	fun markdownToNotionBlocks(markdown: String): JSONArray {
		if (markdown.isBlank()) return JSONArray()
		return try {
			val js = """
                const { markdownToBlocks } = require('@tryfabric/martian');
                const fs = require('fs');
                const md = fs.readFileSync(0, 'utf8');
                console.log(JSON.stringify(markdownToBlocks(md)));
            """.trimIndent()

			val proc = ProcessBuilder("node", "-e", js)
				.redirectErrorStream(true)
				.start()

			proc.outputStream.bufferedWriter().use { it.write(markdown) }

			if (!proc.waitFor(10, TimeUnit.SECONDS)) {
				proc.destroyForcibly()
				return parseMarkdownToBlocks(markdown)
			}

			val out = proc.inputStream.bufferedReader().readText().trim()
			if (out.isEmpty()) parseMarkdownToBlocks(markdown) else JSONArray(out)
		} catch (_: Exception) {
			parseMarkdownToBlocks(markdown)
		}
	}

	private fun parseMarkdownToBlocks(markdown: String): JSONArray {
		val normalisedMarkdown = normalizeMarkdown(markdown)
		val lines = normalisedMarkdown.lines()
		val imgPattern = Regex("""!\[(.*?)]\((.*?)\)""")
		val blocks = JSONArray()

		var inCode = false
		val codeLines = mutableListOf<String>()
		var codeLanguage = ""

		var collectingTable = false
		val tableBuffer = mutableListOf<String>()

		for (line in lines) {
			val trimmed = line.trim()
			val isTableRow = trimmed.startsWith("|") && trimmed.endsWith("|")

			if (isTableRow && trimmed.contains("![")) {
				if (collectingTable && tableBuffer.size >= 2) {
					blocks.put(buildTableBlock(tableBuffer))
				}
				tableBuffer.clear()
				collectingTable = false

				val cells = trimmed.trim('|').split("|")
				val imgCell = cells.getOrNull(0)?.trim() ?: ""
				val descCell = cells.getOrNull(1)?.trim() ?: ""

				val imgMatch = Regex("""!\[(.*?)]\((.*?)\)""").find(imgCell)
				if (imgMatch != null) {
					val alt = imgMatch.groupValues[1]
					val url = imgMatch.groupValues[2]
					blocks.put(imageBlockBuilder.buildImageBlock(url, alt))
					val captionText = descCell.ifBlank { alt }
					if (captionText.isNotBlank()) {
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "paragraph")
								.put(
									"paragraph",
									JSONObject().put(
										"rich_text",
										JSONArray().put(buildRichSpan(captionText)),
									),
								),
						)
					}
				}
				continue
			}

			if (isTableRow) {
				collectingTable = true
				tableBuffer.add(trimmed)
				continue
			}
			if (collectingTable) {
				if (tableBuffer.size >= 2) {
					blocks.put(buildTableBlock(tableBuffer))
				}
				tableBuffer.clear()
				collectingTable = false
			}

			if (trimmed == "---" || trimmed == "***" || trimmed.matches(Regex("""^[-*_]{3,}\s*$"""))) {
				blocks.put(
					JSONObject()
						.put("object", "block")
						.put("type", "divider")
						.put("divider", JSONObject()),
				)
				continue
			}

			val imgMatch = imgPattern.matchEntire(trimmed)
			if (imgMatch != null) {
				val alt = imgMatch.groupValues[1]
				val url = imgMatch.groupValues[2]
				blocks.put(imageBlockBuilder.buildImageBlock(url, alt))
				continue
			}

			if (trimmed.startsWith("```")) {
				if (inCode) {
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "code")
							.put(
								"code",
								JSONObject()
									.put("language", codeLanguage.ifBlank { "plain text" })
									.put(
										"rich_text",
										JSONArray().put(
											JSONObject()
												.put("type", "text")
												.put("text", JSONObject().put("content", codeLines.joinToString("\n"))),
										),
									),
							),
					)
					codeLines.clear()
					codeLanguage = ""
					inCode = false
				} else {
					inCode = true
					codeLanguage = trimmed.removePrefix("```").trim()
				}
				continue
			}

			if (inCode) {
				codeLines.add(line)
				continue
			}

			when {
				trimmed.startsWith("# ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("# ").trim())
					blocks.put(buildHeadingBlock("heading_1", contentArr))
				}

				trimmed.startsWith("## ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("## ").trim())
					blocks.put(buildHeadingBlock("heading_2", contentArr))
				}

				trimmed.startsWith("### ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("### ").trim())
					blocks.put(buildHeadingBlock("heading_3", contentArr))
				}

				trimmed.startsWith("#### ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("#### ").trim())
					blocks.put(buildHeadingBlock("heading_4", contentArr))
				}

				trimmed.startsWith("> ") -> {
					val content = trimmed.removePrefix("> ").trim()
					val match = imgPattern.matchEntire(content)
					if (match != null) {
						val alt = match.groupValues[1]
						val url = match.groupValues[2]
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "quote")
								.put(
									"quote",
									JSONObject()
										.put("rich_text", JSONArray())
										.put("children", JSONArray().put(imageBlockBuilder.buildImageBlock(url, alt))),
								),
						)
					} else {
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "quote")
								.put(
									"quote",
									JSONObject().put(
										"rich_text",
										inlineMarkdownToRichText(content),
									),
								),
						)
					}
				}

				trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
					val content = trimmed.drop(2).trim()
					val match = imgPattern.matchEntire(content)
					if (match != null) {
						val alt = match.groupValues[1]
						val url = match.groupValues[2]
						blocks.put(buildListItemBlock("bulleted_list_item", alt, url))
					} else {
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "bulleted_list_item")
								.put(
									"bulleted_list_item",
									JSONObject().put(
										"rich_text",
										inlineMarkdownToRichText(content),
									),
								),
						)
					}
				}

				trimmed.matches(Regex("""\d+\.\s+.*""")) -> {
					val content = trimmed.replace(Regex("""^\d+\.\s+"""), "").trim()
					val match = imgPattern.matchEntire(content)
					if (match != null) {
						val alt = match.groupValues[1]
						val url = match.groupValues[2]
						blocks.put(buildListItemBlock("numbered_list_item", alt, url))
					} else {
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "numbered_list_item")
								.put(
									"numbered_list_item",
									JSONObject().put(
										"rich_text",
										inlineMarkdownToRichText(content),
									),
								),
						)
					}
				}

				trimmed.isBlank() -> Unit

				else -> {
					val rich = inlineMarkdownToRichText(trimmed)
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "paragraph")
							.put("paragraph", JSONObject().put("rich_text", rich)),
					)
				}
			}
		}

		if (collectingTable && tableBuffer.size >= 2) {
			blocks.put(buildTableBlock(tableBuffer))
		}

		return blocks
	}

	private fun buildTableBlock(tableLines: List<String>): JSONObject {
		if (tableLines.size < 2) return JSONObject()

		val headerCells = tableLines.first()
			.trim('|')
			.split("|")
			.map { it.trim() }

		val tableWidth = headerCells.size
		val rowChildren = JSONArray()

		rowChildren.put(buildTableRow(headerCells))

		for (idx in 2 until tableLines.size) {
			val rowCells = tableLines[idx]
				.trim('|')
				.split("|")
				.map { it.trim() }
				.let { cells ->
					if (cells.size < tableWidth) cells + List(tableWidth - cells.size) { "" }
					else cells.take(tableWidth)
				}
			rowChildren.put(buildTableRow(rowCells))
		}

		return JSONObject().apply {
			put("object", "block")
			put("type", "table")
			put(
				"table",
				JSONObject()
					.put("table_width", tableWidth)
					.put("has_column_header", true)
					.put("has_row_header", false)
					.put("children", rowChildren),
			)
		}
	}

	private fun buildTableRow(cells: List<String>): JSONObject {
		val cellArr = JSONArray()
		for (text in cells) {
			val rich = JSONArray().put(buildRichSpan(text.trim()))
			cellArr.put(rich)
		}
		return JSONObject().apply {
			put("object", "block")
			put("type", "table_row")
			put(
				"table_row",
				JSONObject().put("cells", cellArr),
			)
		}
	}

	private fun buildHeadingBlock(type: String, contentArr: JSONArray): JSONObject =
		JSONObject()
			.put("object", "block")
			.put("type", type)
			.put(
				type,
				JSONObject().put("rich_text", contentArr),
			)

	private fun buildListItemBlock(type: String, alt: String, url: String): JSONObject =
		JSONObject()
			.put("object", "block")
			.put("type", type)
			.put(
				type,
				JSONObject()
					.put("rich_text", JSONArray())
					.put("children", JSONArray().put(imageBlockBuilder.buildImageBlock(url, alt))),
			)

	private fun normalizeMarkdown(md: String): String {
		val pattern = Regex(
			"""!\[([^]]*)]\(\s*([^)]+?)\s*\)|【([^】]+)】\s*\[([^]]+)]|\[((?:https?|mailto|tel|ftp|file):[^\s\]]+)]|\[(.+?)]\(\s*([^)]+?)\s*\)|<((?:https?|mailto|tel|ftp|file):[^>]+)>|((?:https?|mailto|tel|ftp|file):[^\s\]]+)""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)

		val sb = StringBuilder()
		var last = 0
		for (m in pattern.findAll(md)) {
			sb.append(md, last, m.range.first)
			when {
				m.groups[1] != null -> {
					val alt = m.groups[1]!!.value
					val url = sanitizeUrl(m.groups[2]!!.value)
					sb.append("![").append(alt).append("](").append(url).append(")")
				}

				m.groups[3] != null -> {
					val txt = m.groups[3]!!.value
					val url = sanitizeUrl(m.groups[4]!!.value)
					sb.append("[").append(txt).append("](").append(url).append(")")
				}

				m.groups[5] != null -> {
					val url = m.groups[5]!!.value
					sb.append("[").append(url).append("](").append(url).append(")")
				}

				m.groups[8] != null -> {
					val url = m.groups[8]!!.value
					sb.append("[").append(url).append("](").append(url).append(")")
				}

				m.groups[9] != null -> {
					val url = m.groups[9]!!.value
					sb.append("[").append(url).append("](").append(url).append(")")
				}

				else -> {
					val txt = m.groups[6]!!.value
					val url = sanitizeUrl(m.groups[7]!!.value)
					sb.append("[").append(txt).append("](").append(url).append(")")
				}
			}
			last = m.range.last + 1
		}
		sb.append(md, last, md.length)
		return sb.toString()
	}

	private fun inlineMarkdownToRichText(raw: String): JSONArray {
		val rich = JSONArray()
		var cursor = 0
		val pattern = Regex(
			"""(\*\*.+?\*\*|\*[^*\s][^*]*?\*|~~[^~]+~~|`[^`]+`|\[[^]]+]\([^)]+\)|<(https?|mailto|tel|ftp|file):[^>]+>|(https?|mailto|tel|ftp|file):[^\s)<>]+)""",
		)
		pattern.findAll(raw).forEach { m ->
			if (m.range.first > cursor) {
				val plain = raw.substring(cursor, m.range.first)
				if (plain.isNotEmpty()) rich.put(buildRichSpan(plain))
			}
			val token = m.value
			when {
				token.startsWith("**") -> {
					val inner = token.removeSurrounding("**")
					val sub = applyStyle(inlineMarkdownToRichText(inner), addBold = true)
					for (k in 0 until sub.length()) rich.put(sub.getJSONObject(k))
				}

				token.startsWith("*") -> {
					val inner = token.removeSurrounding("*")
					val sub = applyStyle(inlineMarkdownToRichText(inner), addItalic = true)
					for (k in 0 until sub.length()) rich.put(sub.getJSONObject(k))
				}

				token.startsWith("~~") -> {
					val span = buildRichSpan(token.removeSurrounding("~~"))
					span.getJSONObject("annotations").put("strikethrough", true)
					rich.put(span)
				}

				token.startsWith("`") -> {
					val span = buildRichSpan(token.removeSurrounding("`"))
					span.getJSONObject("annotations").put("code", true)
					rich.put(span)
				}

				token.startsWith("[") -> {
					val md = Regex("""\[(.+)]\((.+)\)""").find(token)!!
					val txt = md.groupValues[1]
					val url = md.groupValues[2]
					rich.put(buildLinkedRichSpan(txt, url))
				}

				else -> {
					if (token.startsWith("<") && token.endsWith(">")) {
						val inner = token.substring(1, token.length - 1)
						rich.put(buildLinkedRichSpan(inner, inner))
					} else {
						val schemeMatch = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
						if (schemeMatch.containsMatchIn(token)) {
							rich.put(buildLinkedRichSpan(token, token))
						}
					}
				}
			}
			cursor = m.range.last + 1
		}
		if (cursor < raw.length) {
			val rest = raw.substring(cursor)
			if (rest.isNotEmpty()) rich.put(buildRichSpan(rest))
		}
		return rich
	}
}
