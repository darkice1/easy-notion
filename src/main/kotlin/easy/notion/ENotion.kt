package easy.notion

import net.sf.json.JSONArray
import net.sf.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.collections.iterator
import kotlin.text.removePrefix

class ENotion (val apikey: String) {
	/**
	 * 渲染富文本数组为 HTML，保留链接并在新窗口打开
	 */
	private fun renderRichText(richArray: JSONArray): String {
		val sb = StringBuilder()
		for (i in 0 until richArray.size) {
			val obj = richArray.getJSONObject(i)
			val textObj = obj.getJSONObject("text")
			val content = textObj.getString("content")
			val link = textObj.optJSONObject("link")
			val ann = obj.getJSONObject("annotations")
			val inlineCode = ann.optBoolean("code", false)
			if (link != null && link.has("url")) {
				val url = link.getString("url")
				sb.append("<a href=\"").append(url).append("\" target=\"_blank\">")
				if (inlineCode) sb.append("<code>").append(content).append("</code>")
				else sb.append(content)
				sb.append("</a>")
			} else {
				if (inlineCode) sb.append("<code>").append(content).append("</code>")
				else sb.append(content)
			}
		}
		return sb.toString()
	}
	/**
	 * 通用块子项获取
	 * @param client OkHttpClient 实例
	 * @param blockId Notion 块 ID
	 * @return 子块 JSONArray
	 */
	private fun fetchBlockChildren(client: OkHttpClient, blockId: String): JSONArray {
		val childrenUrl = "https://api.notion.com/v1/blocks/$blockId/children?page_size=100"
		val request = Request.Builder()
			.url(childrenUrl)
			.addHeader("Authorization", "Bearer $apikey")
			.addHeader("Notion-Version", "2022-06-28")
			.get()
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Unexpected code $response")
			val json = JSONObject.fromObject(response.body?.string())
			return json.getJSONArray("results")
		}
	}

	fun getDataBase(databaseId: String): JSONArray {
		val client = OkHttpClient()
		val url = "https://api.notion.com/v1/databases/$databaseId/query"
		val mediaType = "application/json".toMediaTypeOrNull()
		val body = "{}".toRequestBody(mediaType)

		val request = Request.Builder()
			.url(url)
			.addHeader("Authorization", "Bearer $apikey")
			.addHeader("Notion-Version", "2022-06-28")
			.post(body)
			.build()

		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Unexpected code $response")
			val responseJson = JSONObject.fromObject(response.body?.string())
			val results = responseJson.getJSONArray("results")
			val output = JSONArray()

			for (i in 0 until results.size) {
				val page = results.getJSONObject(i)
				val properties = page.getJSONObject("properties")
				val item = JSONObject()

				// 添加页面层面的创建和更新时间
				item.put("created_time", page.getString("created_time"))
				item.put("last_edited_time", page.getString("last_edited_time"))

				for (keyAny in properties.keys()) {
					val key = keyAny as String
					val prop = properties.getJSONObject(key)
					when (prop.getString("type")) {
						"title" -> {
							val titleArray = prop.getJSONArray("title")
							if (titleArray.isNotEmpty()) {
								val titleText = titleArray.getJSONObject(0)
									.getJSONObject("text")
									.getString("content")
								item.put(key, titleText)
							} else {
								item.put(key, "")
							}
						}

						"rich_text" -> {
							val texts = prop.getJSONArray("rich_text")
								.map { it as JSONObject }
								.joinToString("") { it.getJSONObject("text").getString("content") }
							item.put(key, texts)
						}

						"number" -> {
							item.put(key, prop.get("number"))
						}

						"checkbox" -> {
							item.put(key, prop.getBoolean("checkbox"))
						}

						"select" -> {
							val sel = prop.optJSONObject("select")
							item.put(key, sel?.getString("name") ?: "")
						}

						"multi_select" -> {
							val arr = prop.getJSONArray("multi_select")
							val names = arr.map { (it as JSONObject).getString("name") }
							item.put(key, names)
						}

						"date" -> {
							val dateObj = prop.optJSONObject("date")
							item.put(key, dateObj?.getString("start") ?: "")
						}

						"url" -> {
							item.put(key, prop.optString("url", ""))
						}

						"email" -> {
							item.put(key, prop.optString("email", ""))
						}

						"phone_number" -> {
							item.put(key, prop.optString("phone_number", ""))
						}

						"created_time" -> {
							// 数据库属性类型为 created_time，直接取属性中的时间戳
							item.put(key, prop.getString("created_time"))
						}

						"last_edited_time" -> {
							// 数据库属性类型为 last_edited_time，直接取属性中的时间戳
							item.put(key, prop.getString("last_edited_time"))
						}

						else -> {
							// 其他类型统一转为字符串
							item.put(key, prop.toString())
						}
					}
				}

				// 获取页面块内容并手动渲染 HTML
				val blocks = fetchBlockChildren(client, page.getString("id"))
				val htmlBuilder = StringBuilder()
				var currentListType: String? = null

				for (j in 0 until blocks.size) {
					val block = blocks.getJSONObject(j)
					val type = block.getString("type")
					// 统一列表关闭逻辑
					val isListItem = type == "bulleted_list_item" || type == "numbered_list_item"
					if (!isListItem && currentListType != null) {
						htmlBuilder.append(if (currentListType == "bulleted_list_item") "</ul>" else "</ol>")
						currentListType = null
					}
					when (type) {
						"code" -> {
							val texts = block.getJSONObject("code").getJSONArray("rich_text")
							val content = renderRichText(texts)
							htmlBuilder.append("<pre><code>").append(content).append("</code></pre>")
						}
						"paragraph" -> {
							val content = renderRichText(block.getJSONObject("paragraph").getJSONArray("rich_text"))
							htmlBuilder.append("<p>").append(content).append("</p>")
						}

						"heading_1", "heading_2", "heading_3" -> {
							val level = type.removePrefix("heading_")
							val content = renderRichText(block.getJSONObject(type).getJSONArray("rich_text"))
							htmlBuilder.append("<h").append(level).append(">")
								.append(content)
								.append("</h").append(level).append(">")
						}

						"bulleted_list_item", "numbered_list_item" -> {
							if (currentListType != type) {
								if (currentListType == "bulleted_list_item") htmlBuilder.append("</ul>")
								if (currentListType == "numbered_list_item") htmlBuilder.append("</ol>")
								htmlBuilder.append(if (type == "bulleted_list_item") "<ul>" else "<ol>")
								currentListType = type
							}
							val key = if (type == "bulleted_list_item") "bulleted_list_item" else "numbered_list_item"
							val content = renderRichText(block.getJSONObject(key).getJSONArray("rich_text"))
							htmlBuilder.append("<li>").append(content).append("</li>")
						}
						"divider" -> htmlBuilder.append("<hr/>")
						"image" -> {
							val imageObj = block.getJSONObject("image")
							val url = if (imageObj.has("file")) imageObj.getJSONObject("file").getString("url")
							else imageObj.getJSONObject("external").getString("url")
							val caption = imageObj.optJSONArray("caption")
								?.joinToString("") { (it as JSONObject).getJSONObject("text").getString("content") }
								?: ""
							// 按 Notion 格式控制宽度
							val fmt = block.optJSONObject("format")
							val bw = fmt?.optDouble("block_width", -1.0) ?: -1.0
							val bh = fmt?.optDouble("block_height", -1.0) ?: -1.0
							val styleSb = StringBuilder()
							if (bw > 0) styleSb.append("width:").append(bw.toInt()).append("px;")
							if (bh > 0) styleSb.append("height:").append(bh.toInt()).append("px;")
							htmlBuilder.append("<img src=\"").append(url)
								.append("\" alt=\"").append(caption.replace("\"", "&quot;"))
								.append("\" decoding=\"async\"")
							if (styleSb.isNotEmpty()) htmlBuilder.append(" style=\"").append(styleSb).append("\"")
							htmlBuilder.append("/>")
						}
						"table" -> {
							val rows = fetchBlockChildren(client, block.getString("id"))
							val tblFmt = block.optJSONObject("format")
							val bw = tblFmt?.optDouble("block_width", -1.0) ?: -1.0
							val tableStyle = if (bw > 0) " style=\"width:${bw.toInt()}px;\"" else ""
							htmlBuilder.append("<table").append(tableStyle).append(">")
							val tblObj = block.getJSONObject("table")
							val hasCol = tblObj.optBoolean("has_column_header", false)
//							val hasRow = tblObj.optBoolean("has_row_header", false)
							if (hasCol && rows.isNotEmpty()) {
								htmlBuilder.append("<thead><tr>")
								val hdr = rows.getJSONObject(0).getJSONObject("table_row").getJSONArray("cells")
								for (c in 0 until hdr.size) {
									val txt = renderRichText(hdr.getJSONArray(c))
									htmlBuilder.append("<th>").append(txt).append("</th>")
								}
								htmlBuilder.append("</tr></thead>")
							}
							htmlBuilder.append("<tbody>")
							for (r in (if (hasCol) 1 else 0) until rows.size) {
								htmlBuilder.append("<tr>")
								val cells = rows.getJSONObject(r).getJSONObject("table_row").getJSONArray("cells")
								for (c in 0 until cells.size) {
									val txt = renderRichText(cells.getJSONArray(c))
									if (c == 0 && tblObj.optBoolean("has_row_header", false)) {
										htmlBuilder.append("<th scope=\"row\">").append(txt).append("</th>")
									} else {
										htmlBuilder.append("<td>").append(txt).append("</td>")
									}
								}
								htmlBuilder.append("</tr>")
							}
							htmlBuilder.append("</tbody></table>")
						}
						else -> {}
					}
				}
				// 结束后关闭列表
				if (currentListType == "bulleted_list_item") htmlBuilder.append("</ul>")
				if (currentListType == "numbered_list_item") htmlBuilder.append("</ol>")
				// 如果有内容，插入样式
				if (htmlBuilder.isNotEmpty()) {
					val styleTag = "<style>" +
							"hr{border:none;border-top:1px solid #E1E3E5;margin:16px 0;}" +
							"table{border-collapse:collapse;border:1px solid #E1E3E5;}" +
							"th,td{border:1px solid #E1E3E5;padding:8px;text-align:left;}" +
							"thead th{background-color:#f2f2f2;}" +
							"tbody th{background-color:#f2f2f2;}" +
							"img{max-width:600px;}" +
							"a{text-decoration:underline;}" +
							"pre{background:#f5f5f5;padding:10px;border-radius:3px;overflow:auto;}" +
							"pre code{background:transparent;padding:0;}" +
							"code{background:#f5f5f5;padding:2px 4px;border-radius:3px;font-family:monospace;}" +
							"</style>"
					htmlBuilder.insert(0, styleTag)
				}
//				println(htmlBuilder.toString())
				item.put("content", htmlBuilder.toString())

				output.add(item)
			}

			return output
		}

	}
}