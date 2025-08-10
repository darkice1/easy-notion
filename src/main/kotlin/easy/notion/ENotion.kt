/**
 * Easy‑Notion Kotlin helper (single‑file edition).
 *
 * Features:
 *  * Query a database (`getDataBase`)
 *  * Insert a record with automatic type conversion (`insertRecord`)
 *  * Update an existing record (`updateRecord`)
 *
 * The class focuses on JSON manipulation via *net.sf.json* and HTTP calls via **OkHttp 4**.
 * Only a subset of Notion REST API is covered; extend on demand.
 *
 * 2025‑05‑05
 */
package easy.notion

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Lightweight wrapper for selected Notion REST API endpoints.
 *
 * @property apikey  Integration secret (starts with `secret_...`).
 * @property client  Injected **OkHttp 4** instance.
 *                   Defaults to a builder with 60‑second connect / read / write timeouts.
 *
 * You may pass a pre‑configured client (e.g., with logging or caching). If omitted,
 * the default client is created once and reused for all requests.
 */
class ENotion(
	private val apikey: String,
	private val client: OkHttpClient = OkHttpClient.Builder()
		.connectTimeout(60, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.writeTimeout(60, TimeUnit.SECONDS)
		.build(),
) {
	/** Returns a Request.Builder pre‑configured with common Notion headers. */
	private fun requestBuilder(url: String): Request.Builder =
		Request.Builder()
			.url(url)
			.addHeader("Authorization", "Bearer $apikey")
			.addHeader("Notion-Version", "2022-06-28")

	/**
	 * Render a Notion rich‑text array to HTML.
	 *
	 * Supported annotations and formats (complete as of Notion 2024‑10‑23):
	 * • bold, italic, underline, strikethrough, inline code
	 * • foreground colours (e.g. "red") and background colours (e.g. "red_background")
	 * • hyperlinks (always open in new tab)
	 * • non‑text rich‑text types (mention / equation) fall back to their `plain_text`
	 *
	 * Extend easily when Notion ships new annotations: just add another wrapper
	 * before the colour block.
	 */
	private fun renderRichText(richArray: JSONArray): String {
		val sb = StringBuilder()

		for (i in 0 until richArray.length()) {
			val obj = richArray.getJSONObject(i)

			/* ---------- extract raw text + link (if any) ---------- */
			val type = obj.getString("type")
			val (rawContent: String, linkJson: JSONObject?) = when (type) {
				"text" -> {
					val textObj = obj.getJSONObject("text")
					textObj.getString("content") to textObj.optJSONObject("link")
				}
				else -> obj.getString("plain_text") to null       // mention / equation / etc.
			}

			/* ---------- inline Markdown image e.g. ![alt](url) ---------- */
			val imgRegex = Regex("""!\[([^]]*)]\s*\(([^)]+)\)""")
			val imgMatch = imgRegex.matchEntire(rawContent.trim())
			if (imgMatch != null) {
				val alt = imgMatch.groupValues[1]
				val url = imgMatch.groupValues[2]
				sb.append("<img src=\"")
					.append(url)
					.append("\" alt=\"")
					.append(alt.replace("\"", "&quot;"))
					.append("\" decoding=\"async\"/>")
				continue
			}

			/* ---------- apply annotations inside‑out ---------- */
			val ann = obj.getJSONObject("annotations")
			var formatted = rawContent

			if (ann.optBoolean("code")) formatted = "<code>$formatted</code>"
			if (ann.optBoolean("bold")) formatted = "<strong>$formatted</strong>"
			if (ann.optBoolean("italic")) formatted = "<em>$formatted</em>"
			if (ann.optBoolean("underline")) formatted = "<u>$formatted</u>"
			if (ann.optBoolean("strikethrough")) formatted = "<del>$formatted</del>"

			/* ---------- colour (foreground or background) ---------- */
			val color = ann.optString("color", "default")
			if (color != "default") {
				formatted =
					if (color.endsWith("_background")) {
						val bg = color.removeSuffix("_background")
						"<span style=\"background-color:$bg;\">$formatted</span>"
					} else {
						"<span style=\"color:$color;\">$formatted</span>"
					}
			}

			/* ---------- hyperlink wrapper ---------- */
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
	/**
	 * 通用块子项获取
	 * @param blockId Notion 块 ID
	 * @return 子块 JSONArray
	 */
	private fun fetchBlockChildren(blockId: String): JSONArray {
		val childrenUrl = "https://api.notion.com/v1/blocks/$blockId/children?page_size=100"
		val request = requestBuilder(childrenUrl)
			.get()
			.build()
		this.client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Unexpected code $response")
			val json = JSONObject(response.body?.string())
			return json.getJSONArray("results")
		}
	}

	/**
	 * 查询 Notion 数据库内容并返回 JSON 数组。
	 *
	 * @param databaseId    数据库 ID
	 * @param pageSize      每页最大条数（默认 100）
	 * @param filter        可选筛选条件（Notion filter 对象）
	 * @param sorts         可选排序条件（Notion sorts 数组）
	 * @param transformers  optional array of BlockTransformer to pre‑process each block; default null
	 * @return 解析后的 JSONArray，每项为一行，含所有字段和渲染后的 content HTML
	 */
	@Suppress("unused")
	fun getDataBase(
		databaseId: String,
		pageSize: Int = 100,
		filter: JSONObject? = null,
		sorts: JSONArray? = null,
		transformers: Array<BlockTransformer>? = null,
	): JSONArray {
		val url = "https://api.notion.com/v1/databases/$databaseId/query"
		val mediaType = "application/json".toMediaTypeOrNull()
		// Assemble request payload with optional paging, filter and sorting
		val payload = JSONObject().apply {
			put("page_size", pageSize)
			if (filter != null) put("filter", filter)
			if (sorts != null) put("sorts", sorts)
		}
		val body = payload.toString().toRequestBody(mediaType)

		val request = requestBuilder(url)
			.post(body)
			.build()

		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Unexpected code $response")
			val responseJson = JSONObject(response.body?.string())
			val results = responseJson.getJSONArray("results")
//			println(results)
			val output = JSONArray()
			// Collect blocks that were modified by transformers, to patch them back in batch
			/** Replace a single block [blk] (any type) by appending a fresh copy that contains
			 *  the current data from [blk], moving it right after the original, then archiving
			 *  the original block.  This preserves order and avoids the PATCH‑400 limitation
			 *  on media blocks.  Returns `true` on success. */
			fun performBlockReplace(blk: JSONObject): Boolean {
				/* ---------- extract parent info ---------- */
				val parentObj = blk.getJSONObject("parent")
				val parentType = parentObj.optString("type", "page_id")        // page_id | block_id
				val parentId = parentObj.optString(parentType, "")

				/* ---------- 1. build copy of the original block ---------- */
				val blkType = blk.getString("type")
				val newBlockJson = JSONObject().apply {
					put("object", "block")
					put("type", blkType)
					put(blkType, blk.getJSONObject(blkType))   // deep‑copy
				}

				/* ---------- 2. append copy immediately AFTER the old block ---------- */
				val appendBody = JSONObject()
					// insert the new block right after the current block instead of at the end
					.put("after", blk.getString("id"))
					.put("children", JSONArray().put(newBlockJson))
					.toString()
					.toRequestBody("application/json".toMediaTypeOrNull())

				val appendReq = requestBuilder("https://api.notion.com/v1/blocks/$parentId/children")
					.patch(appendBody)
					.build()

				client.newCall(appendReq).execute().use { resp ->
					if (!resp.isSuccessful) return false
				}

				/* ---------- 4. archive (delete) the original block ---------- */
				val archiveBody = JSONObject().put("archived", true)
					.toString()
					.toRequestBody("application/json".toMediaTypeOrNull())

				val archiveReq = requestBuilder("https://api.notion.com/v1/blocks/${blk.getString("id")}")
					.patch(archiveBody)
					.build()

				client.newCall(archiveReq).execute().use { resp ->
					if (!resp.isSuccessful) return false
				}

				return true
			}

			for (i in 0 until results.length()) {
				val page = results.getJSONObject(i)
				val properties = page.getJSONObject("properties")
				val item = JSONObject()

				// 添加页面层面的唯一 ID、创建和更新时间
				item.put("id", page.getString("id"))
				item.put("created_time", page.getString("created_time"))
				item.put("last_edited_time", page.getString("last_edited_time"))

				for (keyAny in properties.keys()) {
					val key = keyAny as String
					val prop = properties.getJSONObject(key)
					when (prop.getString("type")) {
						"title" -> {
							val titleArray = prop.getJSONArray("title")
							if (!titleArray.isEmpty) {
								val titleText = titleArray.getJSONObject(0)
									.getJSONObject("text")
									.getString("content")
//								item[key] = titleText
								item.put(key, titleText)
							} else {
//								item[key] = ""
								item.put(key, "")
							}
						}

						"rich_text" -> {
							val texts = prop.getJSONArray("rich_text")
								.map { it as JSONObject }
								.joinToString("") { it.getJSONObject("text").getString("content") }
//							item[key] = texts
							item.put(key, texts)
						}

						"number" -> {
//							item[key] = prop.get("number")
							item.put(key, prop.get("number"))
						}

						"checkbox" -> {
//							item[key] = prop.getBoolean("checkbox")
							item.put(key, prop.get("checkbox"))
						}

						"select" -> {
							val sel = prop.optJSONObject("select")
//							item[key] = sel?.getString("name") ?: ""
							item.put(key, sel?.getString("name") ?: "")
						}

						"multi_select" -> {
							val arr = prop.getJSONArray("multi_select")
							val names = arr.map { (it as JSONObject).getString("name") }
//							item[key] = names
							item.put(key, names)
						}

						"date" -> {
							val dateObj = prop.optJSONObject("date")
//							item[key] = dateObj?.getString("start") ?: ""
							item.put(key, dateObj?.getString("start") ?: "")
						}

						"url" -> {
//							item[key] = prop.optString("url", "")
							item.put(key, prop.optString("url", ""))
						}

						"email" -> {
//							item[key] = prop.optString("email", "")
							item.put(key, prop.optString("email", ""))
						}

						"phone_number" -> {
//							item[key] = prop.optString("phone_number", "")
							item.put(key, prop.optString("phone_number", ""))
						}

						"created_time" -> {
							// 数据库属性类型为 created_time，直接取属性中的时间戳
//							item[key] = prop.getString("created_time")
							item.put(key, prop.getString("created_time"))
						}

						"last_edited_time" -> {
							// 数据库属性类型为 last_edited_time，直接取属性中的时间戳
//							item[key] = prop.getString("last_edited_time")
							item.put(key, prop.getString("last_edited_time"))
						}
						"button" -> {
							continue
						}

						else -> {
							// 其他类型统一转为字符串
//							item[key] = prop.toString()
							try {
								item.put(key, prop.toString())
							} catch (e: Exception) {
								throw e
							}
						}
					}
				}

				// 获取页面块内容并手动渲染 HTML
				val blocks = fetchBlockChildren(page.getString("id"))
				val htmlBuilder = StringBuilder()
				var currentListType: String? = null

				for (j in 0 until blocks.length()) {
					val block = blocks.getJSONObject(j)
					/* ---------- transformer hook ---------- */
					var modified = false
					if (transformers != null) {
						for (t in transformers) {
							if (t.handle(block)) modified = true
						}
					}
					if (modified) {
						// Persist the change to Notion so the remote document stays in sync.
						// Then continue rendering *this same, now‑modified* block so that
						// htmlBuilder reflects the latest content.
						performBlockReplace(block)
					}
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
						"quote" -> {
							val content = renderRichText(block.getJSONObject("quote").getJSONArray("rich_text"))
							htmlBuilder.append("<blockquote>").append(content).append("</blockquote>")
						}

						"callout" -> {
							val content = renderRichText(block.getJSONObject("callout").getJSONArray("rich_text"))
							htmlBuilder.append("<div class=\"callout\">").append(content).append("</div>")
						}
						"divider" -> htmlBuilder.append("<hr/>")
						"image" -> {
							val imageObj = block.getJSONObject("image")
							val purl = if (imageObj.has("file")) imageObj.getJSONObject("file").getString("url")
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
							htmlBuilder.append("<img src=\"").append(purl)
								.append("\" alt=\"").append(caption.replace("\"", "&quot;"))
								.append("\" decoding=\"async\"")
							if (styleSb.isNotEmpty()) htmlBuilder.append(" style=\"").append(styleSb).append("\"")
							htmlBuilder.append("/>")
						}
						"table" -> {
							val rows = fetchBlockChildren(block.getString("id"))
							val tblFmt = block.optJSONObject("format")
							val bw = tblFmt?.optDouble("block_width", -1.0) ?: -1.0
							val tableStyle = if (bw > 0) " style=\"width:${bw.toInt()}px;\"" else ""
							htmlBuilder.append("<table").append(tableStyle).append(">")
							val tblObj = block.getJSONObject("table")
							val hasCol = tblObj.optBoolean("has_column_header", false)
//							val hasRow = tblObj.optBoolean("has_row_header", false)
							if (hasCol && !rows.isEmpty) {
								htmlBuilder.append("<thead><tr>")
								val hdr = rows.getJSONObject(0).getJSONObject("table_row").getJSONArray("cells")
								for (c in 0 until hdr.length()) {
									val txt = renderRichText(hdr.getJSONArray(c))
									htmlBuilder.append("<th>").append(txt).append("</th>")
								}
								htmlBuilder.append("</tr></thead>")
							}
							htmlBuilder.append("<tbody>")
							for (r in (if (hasCol) 1 else 0) until rows.length()) {
								htmlBuilder.append("<tr>")
								val cells = rows.getJSONObject(r).getJSONObject("table_row").getJSONArray("cells")
								for (c in 0 until cells.length()) {
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
							"blockquote{border-left:4px solid #E1E3E5;margin:16px 0;padding-left:12px;}" +
							".callout{border-left:4px solid #E0E2E4;background:#FAFAFA;padding:12px;margin:16px 0;}" +
							"</style>"
					item.put("styleTag", styleTag)
//					htmlBuilder.insert(0, styleTag)
				}
//				println(htmlBuilder.toString())
//				item["content"] = htmlBuilder.toString()
				item.put("content", htmlBuilder.toString())


//				output.add(item)
				output.put(item)
//				println(item)
			}

			return output
		}

	}

	/**
	 * 根据数据库 schema 与列值列表构造 Notion properties JSON，并在必要时自动尝试类型转换
	 * （例如 `"1" → number、true → 1、"foo,bar" → multi‑select 等）。
	 */
	private fun buildProperties(
		schemaProps: JSONObject,
		fields: Array<out Pair<String, Any?>>,
	): JSONObject {
		val properties = JSONObject()
		for ((key, value) in fields) {
			if (!schemaProps.has(key)) continue
			val schema = schemaProps.getJSONObject(key)
			val type = schema.getString("type")
			val prop = JSONObject()
			when (type) {
				"title", "rich_text" -> {
					val textObj = JSONObject().apply { put("content", value.toString()) }
					val wrapper = JSONObject().apply {
						put("type", "text")
						put("text", textObj)
					}
//					prop[type] = JSONArray().apply { add(wrapper) }
					prop.put(type, JSONArray().apply { put(wrapper) })
				}

				"number" -> {
					val num: Number? = when (value) {
						is Number -> value
						is String -> value.toDoubleOrNull()
						is Boolean -> if (value) 1 else 0
						else -> null
					}
//					if (num != null) prop["number"] = num else continue    // 若无法转换则跳过
					if (num != null) prop.put("number", num) else continue

				}

				"checkbox" -> {
					val bool: Boolean = when (value) {
						is Boolean -> value
						is String -> value.equals("true", true) || value == "1"
						is Number -> value.toInt() != 0
						else -> false
					}
//					prop["checkbox"] = bool
					prop.put("checkbox", bool)
				}

				"select" -> {
					val name = when (value) {
						is String -> value
						is Array<*> -> value.firstOrNull()?.toString() ?: ""
						is Collection<*> -> value.firstOrNull()?.toString() ?: ""
						else -> value.toString()
					}
//					prop["select"] = JSONObject().apply { put("name", name) }
					prop.put("select", JSONObject().apply { put("name", name) })
				}

				"multi_select" -> {
					val arr = JSONArray()
					when (value) {
						is Collection<*> -> value.forEach { v ->
//							arr.add(JSONObject().apply { put("name", v.toString()) })
							arr.put(JSONObject().apply { put("name", v.toString()) })
						}
						is Array<*> -> value.forEach { v ->
							arr.put(JSONObject().apply { put("name", v.toString()) })
						}
						is String -> {              // 逗号分隔字符串
							value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
//								.forEach { v -> arr.add(JSONObject().apply { put("name", v) }) }
								.forEach { v -> arr.put(JSONObject().apply { put("name", v) }) }
						}
//						else -> arr.add(JSONObject().apply { put("name", value.toString()) })
						else -> arr.put(JSONObject().apply { put("name", value.toString()) })
					}
//					prop["multi_select"] = arr
					prop.put("multi_select", arr)
				}

				"date" -> {
					// 支持字符串日期或 java.time.* 类型
					val start = when (value) {
						is java.time.temporal.TemporalAccessor -> value.toString()
						else -> value.toString()
					}
//					prop["date"] = JSONObject().apply { put("start", start) }
					prop.put("date", JSONObject().apply { put("start", start) })
				}

//				"url" -> prop["url"] = value.toString()
				"url" -> prop.put("url", value.toString())
//				"email" -> prop["email"] = value.toString()
				"email" -> prop.put("email", value.toString())
//				"phone_number" -> prop["phone_number"] = value.toString()
				"phone_number" -> prop.put("phone_number", value.toString())
				else -> {
					val textObj = JSONObject().apply { put("content", value.toString()) }
					val wrapper = JSONObject().apply {
						put("type", "text")
						put("text", textObj)
					}
//					prop["rich_text"] = JSONArray().apply { add(wrapper) }
					prop.put("rich_text", JSONArray().apply { put(wrapper) })
//
				}
			}
//			properties[key] = prop
			properties.put(key, prop)
		}
		return properties
	}

	/**
	 * 从 Notion 获取数据库元数据（schema）。
	 *
	 * @return 若请求成功返回完整 JSON；失败返回 `null`
	 */
	private fun fetchDatabaseSchema(databaseId: String): JSONObject? {
		val req = requestBuilder("https://api.notion.com/v1/databases/$databaseId")
			.get()
			.build()

		return this.client.newCall(req).execute().use { resp ->
			if (!resp.isSuccessful) null else JSONObject(resp.body?.string())
		}
	}

	/**
	 * Search the current workspace for a Notion database whose title exactly matches
	 * [databaseName] and return its database ID.
	 *
	 * The result clearly distinguishes between three outcomes:
	 *  * **Success + non‑null value** – a matching database was found.
	 *  * **Success + null value**  – the request succeeded but no match exists.
	 *  * **Failure** – network/API error or other exception during the request.
	 */
	@Suppress("unused")
	fun findNotionDatabase(databaseName: String): Result<String?> = runCatching {
		val searchUrl = "https://api.notion.com/v1/search"
		val payload = JSONObject().apply {
			put("query", databaseName)
			put(
				"filter",
				JSONObject().apply {
					put("value", "database")
					put("property", "object")
				},
			)
			put("page_size", 25)
		}

		val body = payload
			.toString()
			.toRequestBody("application/json".toMediaTypeOrNull())

		val request = requestBuilder(searchUrl)
			.post(body)
			.build()

		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Unexpected code $response")
			val json = JSONObject(response.body?.string())
			val results = json.getJSONArray("results")

			for (i in 0 until results.length()) {
				val db = results.getJSONObject(i)
				val titleArr = db.getJSONArray("title")
				if (!titleArr.isEmpty) {
					val name = titleArr
						.getJSONObject(0)
						.getJSONObject("text")
						.getString("content")
					if (name == databaseName) {
						return@runCatching db.getString("id")
					}
				}
			}
			// No matching database found
			null
		}
	}

	/**
	 * Create a brand‑new Notion database under a parent page.
	 *
	 * @param pageId        ID of the parent page that will hold the new database.
	 * @param databaseName  The name (title) of the database.
	 * @param columns       Map of column names to Notion property types
	 *                      (valid types: title, rich_text, number, select,
	 *                       multi_select, checkbox, date, url, email, phone_number).
	 * @param timeField     Column that should be forced to `date` type and typically
	 *                      used to store event timestamps (e.g. "Created").
	 * @param primaryKey    Column that should be forced to `title` type (Notion
	 *                      requires at least one title property).
	 * @return Newly‑created database ID, or `null` if the API call fails.
	 *
	 * Note: If `columns` already contains the `timeField` or `primaryKey`, their
	 *       requested types will be overridden to `date` and `title` respectively.
	 *       Additional property configuration (options for select / multi‑select,
	 *       number format, etc.) can be added on demand.
	 */
	@Suppress("unused")
	fun createNotionDatabase(
		pageId: String,
		databaseName: String,
		columns: Map<String, String>,
		timeField: String,
		primaryKey: String,
	): String? {
		/* ---------- assemble property definitions ---------- */
		val props = JSONObject()

		fun putProperty(name: String, type: String) {
			val prop = JSONObject()
			when (type) {
				"title" -> prop.put("title", JSONObject())
				"rich_text" -> prop.put("rich_text", JSONObject())
				"number" -> prop.put("number", JSONObject())
				"select" -> prop.put("select", JSONObject())
				"multi_select" -> prop.put("multi_select", JSONObject())
				"checkbox" -> prop.put("checkbox", JSONObject())
				"date" -> prop.put("date", JSONObject())
				"url" -> prop.put("url", JSONObject())
				"email" -> prop.put("email", JSONObject())
				"phone_number" -> prop.put("phone_number", JSONObject())
				else -> prop.put("rich_text", JSONObject())
			}
			props.put(name, prop)
		}

		// user‑defined columns
		for ((name, type) in columns) {
			putProperty(name, type)
		}

		// enforce primaryKey as title
		putProperty(primaryKey, "title")

		// enforce timeField as date
		putProperty(timeField, "date")

		/* ---------- build request body ---------- */
		val root = JSONObject().apply {
			put("parent", JSONObject().apply { put("page_id", pageId) })
			put(
				"title",
				JSONArray().apply {
					put(
						JSONObject().apply {
							put("type", "text")
							put("text", JSONObject().apply { put("content", databaseName) })
						},
					)
				},
			)
			put("properties", props)
		}

		val body = root
			.toString()
			.toRequestBody("application/json".toMediaTypeOrNull())

		val req = requestBuilder("https://api.notion.com/v1/databases")
			.post(body)
			.build()

		this.client.newCall(req).execute().use { resp ->
			if (!resp.isSuccessful) return null
			val json = JSONObject(resp.body?.string())
			return json.optString("id", null)
		}
	}

	/**
	 * 向指定 Notion 数据库插入一条记录
	 *
	 * 1. 通过可变参数 `fields` 直接传入列名-值对，免去手动构造 `Map`。
	 * 2. 若数据库 ID 不存在，方法返回 `null`，不会执行写入。
	 * 3. 写入时会先读取数据库属性 schema，并依据列的「实际类型」自动转换为正确的 Notion 属性结构。
	 * 4. 对不匹配的类型自动做“尽可能合理”的转换（见 `buildProperties`）。
	 *
	 * @param databaseId 目标数据库 ID
	 * @param markdownContent 可选的 Markdown 内容，将自动转换为 Notion 块
	 * @param fields     形如 `"Name" to "Foo", "Score" to 99` 的可变参数
	 * @return 若插入成功，返回 Notion 返回的页面 JSON；否则返回 `null`
	 */
	@Suppress("unused")
	fun insertRecord(
		databaseId: String,
		markdownContent: String? = null,
		vararg fields: Pair<String, Any?>,
	): JSONObject? {

		/* ---------- 检查数据库是否存在并获取 schema ---------- */
		val dbJson = fetchDatabaseSchema(databaseId) ?: return null
		val schemaProps = dbJson.getJSONObject("properties")

		/* ---------- 组装 properties (自动补全 title) ---------- */
		val properties = buildProperties(schemaProps, fields)

		// Ensure the database‑required "title" property exists.
		// Determine the key of the first column whose type == title
		val titleKey: String? = sequence {
			val keys = schemaProps.keys()
			while (keys.hasNext()) yield(keys.next() as String)
		}.firstOrNull { k -> schemaProps.getJSONObject(k).optString("type") == "title" }

		if (titleKey != null && !properties.has(titleKey)) {
			// Fallback title: 1) first Markdown heading / first non‑empty line
			//                2)  timestamp "yyyy-MM-dd HH:mm:ss"
			val fallbackTitle: String = run {
				if (!markdownContent.isNullOrBlank()) {
					markdownContent.lineSequence()
						.map { it.trim().removePrefix("#").trim() }
						.firstOrNull { it.isNotBlank() }
						?: ""
				} else ""
			}.ifBlank {
				LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
			}

			val wrapper = JSONObject().apply {
				put("type", "text")
				put("text", JSONObject().put("content", fallbackTitle))
			}
			val titleArr = JSONArray().put(wrapper)
			properties.put(titleKey, JSONObject().put("title", titleArr))
		}

		// If still empty (edge‑case: schema without a title column), abort early.
		if (properties.isEmpty) return null

		/* ---------- 构建请求 ---------- */
		val root = JSONObject().apply {
			put("parent", JSONObject().apply { put("database_id", databaseId) })
			put("properties", properties)
		}
		if (!markdownContent.isNullOrBlank()) {
			root.put("children", markdownToNotionBlocks(markdownContent))
		}

		val body = root.toString().toRequestBody("application/json".toMediaTypeOrNull())

		val pageRequest = requestBuilder("https://api.notion.com/v1/pages")
			.post(body)
			.build()

		return client.newCall(pageRequest).execute().use { resp ->
			val bodyTxt = resp.body?.string()
			if (!resp.isSuccessful) {
				// 直接抛出异常让调用方感知错误详情，而不是静默返回 null
				throw IOException("Notion API error (${resp.code}): $bodyTxt")
			}
			JSONObject(bodyTxt)
		}
	}

	/**
	 * 更新指定页面的列内容
	 *
	 * @param databaseId 所在数据库 ID
	 * @param pageId     目标页面 ID
	 * @param markdownContent 可选的 Markdown 内容，将自动转换为 Notion 块并附加
	 * @param fields     待更新的列及新值
	 * @return 更新成功返回页面 JSON；否则返回 null
	 *
	 * 类型转换规则与 `insertRecord` 一致。
	 */
	@Suppress("unused")
	fun updateRecord(
		databaseId: String,
		pageId: String,
		markdownContent: String? = null,
		vararg fields: Pair<String, Any?>,
	): JSONObject? {

		// 获取 schema
		val dbJson = fetchDatabaseSchema(databaseId) ?: return null
		val schemaProps = dbJson.getJSONObject("properties")
//	    println("schemaProps: $schemaProps")
		// 构造 properties
		val properties = buildProperties(schemaProps, fields)
//	    println("properties: $properties")

		if (properties.isEmpty) return null

		val root = JSONObject().apply { put("properties", properties) }
		val body = root.toString().toRequestBody("application/json".toMediaTypeOrNull())

		val patchReq = requestBuilder("https://api.notion.com/v1/pages/$pageId")
			.patch(body)
			.build()

		if (!markdownContent.isNullOrBlank()) {
			val blocks = markdownToNotionBlocks(markdownContent)
			if (blocks.length() > 0) {
				val appendBody = JSONObject().put("children", blocks)
					.toString().toRequestBody("application/json".toMediaTypeOrNull())
				val appendReq = requestBuilder("https://api.notion.com/v1/blocks/$pageId/children")
					.patch(appendBody)
					.build()
				client.newCall(appendReq).execute().use { /* ignore */ }
			}
		}

		return client.newCall(patchReq).execute().use { resp ->
			if (!resp.isSuccessful) return null
			JSONObject(resp.body?.string())
		}
	}
	/* --------------------------------------------------------------------
	 *  Convenience helpers extracted from NotionSyncService
	 * ------------------------------------------------------------------ */

	/** Map a JDBC/SQL column type to a Notion property type. */
	private fun mapSqlTypeToNotionType(
		sqlType: String,
		columnName: String,
		timeField: String,
		primaryKey: String,
	): String {
		if (columnName == primaryKey) return "title"
		if (columnName == timeField) return "date"
		val t = sqlType.uppercase()
		return if (t.contains("INT") || t.contains("DECIMAL") ||
			t.contains("NUMERIC") || t.contains("FLOAT")
		) "number" else "rich_text"
	}

	/** Return the *properties* object of a Notion database, or `null` on error. */
	fun getDatabaseProperties(databaseId: String): JSONObject? {
		val req = requestBuilder("https://api.notion.com/v1/databases/$databaseId")
			.get()
			.build()
		return client.newCall(req).execute().use { resp ->
			if (!resp.isSuccessful) null
			else JSONObject(resp.body?.string()).getJSONObject("properties")
		}
	}

	/** Build a rich‑text span with optional annotations. */
	private fun buildRichSpan(
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

	/** Build a rich‑text span that embeds a hyperlink, with optional bold / italic flags. */
	private fun buildLinkedRichSpan(
		text: String,
		url: String,
		bold: Boolean = false,
		italic: Boolean = false,
	): JSONObject = buildRichSpan(text, bold, italic).apply {
		getJSONObject("text").put(
			"link",
			JSONObject().apply {
				put("type", "url")
				put("url", url)
			},
		)
	}

	/** Apply additional bold / italic flags to every span in [arr] (mutates in‑place). */
	private fun applyStyle(arr: JSONArray, addBold: Boolean = false, addItalic: Boolean = false): JSONArray {
		for (i in 0 until arr.length()) {
			val span = arr.getJSONObject(i)
			val ann = span.getJSONObject("annotations")
			if (addBold) ann.put("bold", true)
			if (addItalic) ann.put("italic", true)
		}
		return arr
	}

	/** Convert a single Markdown inline string to Notion rich_text array with bold / italic / link / code / strike. */
	private fun inlineMarkdownToRichText(raw: String): JSONArray {
		val rich = JSONArray()
		var cursor = 0
		val pattern = Regex("""(\*\*.+?\*\*|\*[^*\s][^*]*?\*|~~[^~]+~~|`[^`]+`|\[[^]]+]\([^)]+\))""")
		pattern.findAll(raw).forEach { m ->
			if (m.range.first > cursor) {
				val plain = raw.substring(cursor, m.range.first)
				if (plain.isNotEmpty()) rich.put(buildRichSpan(plain))
			}
			val token = m.value
			when {
				token.startsWith("**") -> {
					val inner = token.removeSurrounding("**")
					// Recursively parse inner Markdown, then force‑apply bold
					val sub = applyStyle(inlineMarkdownToRichText(inner), addBold = true)
					for (k in 0 until sub.length()) rich.put(sub.getJSONObject(k))
				}

				token.startsWith("*") -> {
					val inner = token.removeSurrounding("*")
					// Recursively parse inner Markdown, then force‑apply italic
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
			}
			cursor = m.range.last + 1
		}
		if (cursor < raw.length) {
			val rest = raw.substring(cursor)
			if (rest.isNotEmpty()) rich.put(buildRichSpan(rest))
		}
		return rich
	}

	/** Build an image block (external or file) with `alt` as caption. */
	private fun buildImageBlock(url: String, alt: String = ""): JSONObject = JSONObject().apply {
		put("object", "block")
		put("type", "image")
		put(
			"image",
			JSONObject().apply {
				if (url.startsWith("http")) {
					put("type", "external")
					put("external", JSONObject().put("url", url))
				} else {
					put("type", "file")
					put("file", JSONObject().put("url", url))
				}
				put(
					"caption",
					JSONArray().put(
						JSONObject().apply {
							put("type", "text")
							put("text", JSONObject().put("content", alt))
						},
					),
				)
			},
		)
	}

	/** Build a table_row block with plain‑text cells. */
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

	/** Given a list of Markdown table lines, convert to a Notion *table* block. */
	private fun buildTableBlock(tableLines: List<String>): JSONObject {
		if (tableLines.size < 2) return JSONObject()   // malformed

		// Header |---|---| separator line is usually second. Ignore it safely.
		val headerCells = tableLines.first()
			.trim('|')
			.split("|")
			.map { it.trim() }

		val tableWidth = headerCells.size
		val rowChildren = JSONArray()

		// ---------- header row ----------
		rowChildren.put(buildTableRow(headerCells))

		// ---------- data rows ----------
		// start scanning after header and separator lines
		for (idx in 2 until tableLines.size) {
			val rowCells = tableLines[idx]
				.trim('|')
				.split("|")
				.map { it.trim() }
				.let { cells ->
					// normalise row length to tableWidth
					if (cells.size < tableWidth) cells + List(tableWidth - cells.size) { "" }
					else cells.take(tableWidth)
				}
			rowChildren.put(buildTableRow(rowCells))
		}

		// ---------- assemble table block ----------
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

	private fun normalizeMarkdown(md: String): String {
		// single pattern with 4 alternations; groups:
		// 1‑2:  image  alt / url
		// 3‑4:  Chinese txt / url
		// 5:    bare url
		// 6‑7:  link   txt / url
		val pattern = Regex(
			"""!\[([^]]*)]\(\s*([^)]+?)\s*\)|【([^】]+)】\s*\[([^]]+)]|\[((?:https?|ftp)://[^\s\]]+)]|\[(.+?)]\(\s*([^)]+?)\s*\)""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)

		val sb = StringBuilder()
		var last = 0
		for (m in pattern.findAll(md)) {
			sb.append(md, last, m.range.first)
			when {
				m.groups[1] != null -> {         // image
					val alt = m.groups[1]!!.value
					val url = m.groups[2]!!.value.trim()
					sb.append("![").append(alt).append("](").append(url).append(")")
				}

				m.groups[3] != null -> {         // Chinese link
					val txt = m.groups[3]!!.value
					val url = m.groups[4]!!.value.trim()
					sb.append("[").append(txt).append("](").append(url).append(")")
				}

				m.groups[5] != null -> {         // bare URL
					val url = m.groups[5]!!.value
					sb.append("[").append(url).append("](").append(url).append(")")
				}

				else -> {                        // normal link (6‑7)
					val txt = m.groups[6]!!.value
					val url = m.groups[7]!!.value.trim()
					sb.append("[").append(txt).append("](").append(url).append(")")
				}
			}
			last = m.range.last + 1
		}
		sb.append(md, last, md.length)
		return sb.toString()
	}

	/**
	 * Simple Markdown → Notion block converter (supports paragraphs, headings, lists,
	 * code, quotes, **bold**, and inline images). For full‑fidelity conversion,
	 * see [markdownToNotionBlocks].
	 */
	private fun parseMarkdownToBlocks(markdown: String): JSONArray {
		// --- normalise images & links in one pass ---
		val normalisedMarkdown = normalizeMarkdown(markdown)
		val lines = normalisedMarkdown.lines()
		val imgPattern = Regex("""!\[(.*?)]\((.*?)\)""")
//        val boldPattern = Regex("""\*\*(.+?)\*\*""")
		val blocks = JSONArray()
		var inCode = false
		val codeLines = mutableListOf<String>()
		var codeLanguage = ""
		// --- table state ---
		var collectingTable = false
		val tableBuffer = mutableListOf<String>()

		for (line in lines) {
			val trimmed = line.trim()

			// Detect Markdown table row (pipes on both ends)
			val isTableRow = trimmed.startsWith("|") && trimmed.endsWith("|")

			// ------------------------------------------------------------------
			// Special case: table row that embeds an image in the first column
			// e.g. "| ![](url) | caption |"
			// Notion table cells *cannot* contain images, therefore convert such
			// rows into:  <image block> + <paragraph caption>
			// ------------------------------------------------------------------
			if (isTableRow && trimmed.contains("![")) {
				// Flush any pending textual table first
				if (collectingTable && tableBuffer.size >= 2) {
					blocks.put(buildTableBlock(tableBuffer))
				}
				tableBuffer.clear()
				collectingTable = false

				// Split the row and extract parts
				val cells = trimmed.trim('|').split("|")
				val imgCell = cells.getOrNull(0)?.trim() ?: ""
				val descCell = cells.getOrNull(1)?.trim() ?: ""

				val imgMatch = Regex("""!\[(.*?)]\((.*?)\)""").find(imgCell)
				if (imgMatch != null) {
					val alt = imgMatch.groupValues[1]
					val url = imgMatch.groupValues[2]
					// 1) image block
					blocks.put(buildImageBlock(url, alt))
					// 2) optional caption as paragraph (use descCell if provided, else alt)
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

			// ------------------------------------------------------------------
			// Regular textual table rows: accumulate for buildTableBlock later
			// ------------------------------------------------------------------
			if (isTableRow) {
				collectingTable = true
				tableBuffer.add(trimmed)
				continue
			}
			if (collectingTable) {
				// End of table – flush
				if (tableBuffer.size >= 2) {
					blocks.put(buildTableBlock(tableBuffer))
				}
				tableBuffer.clear()
				collectingTable = false
			}
			// ------------------------------------------------------------------
			// Horizontal rule: lines with only --- or *** (no other text)
			// ------------------------------------------------------------------
			if (trimmed == "---" || trimmed == "***" || trimmed.matches(Regex("""^[-*_]{3,}\s*$"""))) {
				blocks.put(
					JSONObject()
						.put("object", "block")
						.put("type", "divider")
						.put("divider", JSONObject()),
				)
				continue
			}
			// --- image ---
			val imgMatch = imgPattern.matchEntire(trimmed)
			if (imgMatch != null) {
				val alt = imgMatch.groupValues[1]
				val url = imgMatch.groupValues[2]
				blocks.put(buildImageBlock(url, alt))
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
												.put("text", JSONObject().put("content", codeLines.joinToString("\n")))
										)
									)
							)
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
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "heading_1")
							.put(
								"heading_1",
								JSONObject().put(
									"rich_text",
									contentArr
								)
							)
					)
				}

				trimmed.startsWith("## ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("## ").trim())
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "heading_2")
							.put(
								"heading_2",
								JSONObject().put(
									"rich_text",
									contentArr
								)
							)
					)
				}

				trimmed.startsWith("### ") -> {
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("### ").trim())
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "heading_3")
							.put(
								"heading_3",
								JSONObject().put(
									"rich_text",
									contentArr
								)
							)
					)
				}

				trimmed.startsWith("#### ") -> {
					// Markdown supports h4‑h6, but Notion only has heading_1‑3.
					// Map any deeper heading (####, #####, ######) to heading_3.
					val contentArr = inlineMarkdownToRichText(trimmed.removePrefix("#### ").trim())
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "heading_3")
							.put(
								"heading_3",
								JSONObject().put(
									"rich_text",
									contentArr
								)
							)
					)
				}

				trimmed.startsWith("> ") -> {
					blocks.put(
						JSONObject()
							.put("object", "block")
							.put("type", "quote")
							.put(
								"quote",
								JSONObject().put(
									"rich_text",
									JSONArray().put(
										JSONObject()
											.put("type", "text")
											.put("text", JSONObject().put("content", trimmed.removePrefix("> ").trim()))
									)
								)
							)
					)
				}

				trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
					val content = trimmed.drop(2).trim()
					val imgMatch = imgPattern.matchEntire(content)
					if (imgMatch != null) {
						// The list item itself is a Markdown image – render it as an image block
						val alt = imgMatch.groupValues[1]
						val url = imgMatch.groupValues[2]
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "bulleted_list_item")
								.put(
									"bulleted_list_item",
									JSONObject()
										.put("rich_text", JSONArray())            // no inline text
										.put("children", JSONArray().put(buildImageBlock(url, alt)))
								)
						)
					} else {
						// Fallback to the original behaviour
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "bulleted_list_item")
								.put(
									"bulleted_list_item",
									JSONObject().put(
										"rich_text",
										inlineMarkdownToRichText(content)
									)
								)
						)
					}
				}

				trimmed.matches(Regex("""\d+\.\s+.*""")) -> {
					val content = trimmed.replace(Regex("""^\d+\.\s+"""), "").trim()
					val imgMatch = imgPattern.matchEntire(content)
					if (imgMatch != null) {
						val alt = imgMatch.groupValues[1]
						val url = imgMatch.groupValues[2]
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "numbered_list_item")
								.put(
									"numbered_list_item",
									JSONObject()
										.put("rich_text", JSONArray())
										.put("children", JSONArray().put(buildImageBlock(url, alt)))
								)
						)
					} else {
						blocks.put(
							JSONObject()
								.put("object", "block")
								.put("type", "numbered_list_item")
								.put(
									"numbered_list_item",
									JSONObject().put(
										"rich_text",
										inlineMarkdownToRichText(content)
									)
								)
						)
					}
				}

				trimmed.isBlank() -> {
					// skip blank lines
				}

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
		// After loop: flush any pending table
		if (collectingTable && tableBuffer.size >= 2) {
			blocks.put(buildTableBlock(tableBuffer))
		}
		return blocks
	}

	/**
	 * High‑fidelity Markdown → Notion conversion that prefers the mature
	 * Node package **@tryfabric/martian** (460+ GitHub stars).
	 * Falls back to the simple converter when Node/Martian is unavailable.
	 *
	 * Requires a working `node` runtime and a global install:
	 *     npm i -g @tryfabric/martian
	 */
	private fun markdownToNotionBlocks(markdown: String): JSONArray {
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
	@Suppress("unused")
	fun ensureDatabaseSchema(
		databaseId: String,
		localColumns: Map<String, String>,
		timeField: String,
		primaryKey: String,
	): Boolean {
		val currentProps = getDatabaseProperties(databaseId) ?: return false
		val newProps = JSONObject()

		for ((name, sqlType) in localColumns) {
			if (name == primaryKey) continue
			if (!currentProps.has(name)) {
				when (mapSqlTypeToNotionType(sqlType, name, timeField, primaryKey)) {
					"date" -> newProps.put(name, JSONObject().put("date", JSONObject()))
					"number" -> newProps.put(name, JSONObject().put("number", JSONObject()))
					else -> newProps.put(name, JSONObject().put("rich_text", JSONObject()))
				}
			}
		}
		if (newProps.length() == 0) return true   // nothing to do

		val body = JSONObject().put("properties", newProps)
			.toString()
			.toRequestBody("application/json".toMediaTypeOrNull())

		val req = requestBuilder("https://api.notion.com/v1/databases/$databaseId")
			.patch(body)
			.build()

		client.newCall(req).execute().use { resp -> return resp.isSuccessful }
	}

	/** Latest value of [timeField] in MySQL `yyyy-MM-dd HH:mm:ss` format, or `null` when empty. */
	@Suppress("unused")
	fun getLatestTimestamp(databaseId: String, timeField: String): String? {
		/* ---------- 1. query the latest page sorted by [timeField] ---------- */
		val payload = JSONObject().apply {
			put(
				"sorts",
				JSONArray().put(
					JSONObject().apply {
						put("property", timeField)
						put("direction", "descending")
					},
				),
			)
			put("page_size", 1)
		}
		val body = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

		val req = requestBuilder("https://api.notion.com/v1/databases/$databaseId/query")
			.post(body)
			.build()

		client.newCall(req).execute().use { resp ->
			if (!resp.isSuccessful) return null
			val results = JSONObject(resp.body?.string()).getJSONArray("results")
			if (results.length() == 0) return null

			val props = results.getJSONObject(0).getJSONObject("properties")
			val fieldObj = props.optJSONObject(timeField) ?: return null

			/* ---------- 2. extract the raw string value ---------- */
			val raw: String? = when (fieldObj.optString("type")) {
				"date" -> fieldObj.optJSONObject("date")?.optString("start")
				"rich_text" -> fieldObj.optJSONArray("rich_text")?.optJSONObject(0)
					?.optJSONObject("text")?.optString("content")

				"title" -> fieldObj.optJSONArray("title")?.optJSONObject(0)
					?.optJSONObject("text")?.optString("content")

				else -> null
			}
			if (raw.isNullOrBlank()) return null

			/* ---------- 3. normalise to "yyyy-MM-dd HH:mm:ss" ---------- */
			val targetFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			// Try ISO formats first
			return try {
				OffsetDateTime.parse(raw).format(targetFmt)
			} catch (ignored: Exception) {
				try {
					LocalDateTime.parse(raw, targetFmt).format(targetFmt)
				} catch (ignored2: Exception) {
					null
				}
			}
		}
	}

	/** Extract the comparable string value from a Notion property. */
	@Suppress("unused")
	fun extractValueFromProperty(property: JSONObject, notionType: String): String? = when (notionType) {
		"title" -> property.optJSONArray("title")?.optJSONObject(0)
			?.optJSONObject("text")?.optString("content")

		"rich_text" -> property.optJSONArray("rich_text")?.optJSONObject(0)
			?.optJSONObject("text")?.optString("content")

		"date" -> property.optJSONObject("date")?.optString("start")
		"number" -> property.opt("number")?.toString()
		"select" -> property.optJSONObject("select")?.optString("name")
		else -> null
	}

	/**
	 * Locate a page whose [primaryKey] equals [primaryKeyValue].
	 *
	 * The result lets callers clearly distinguish between three outcomes:
	 *
	 *  * **Success + non‑null value** –the page was found.
	 *  * **Success + null value** the request succeeded but no matching page exists.
	 *  * **Failure** network / API error, schema missing, or other exception.
	 *
	 * Example usage:
	 * ```
	 * when (val r = findPageByPrimaryKey(dbId, "Name", "Alice")) {
	 *     is Result.Success -> if (r.getOrNull() != null) { ... } else { ... }   // not found
	 *     is Result.Failure -> logger.error(r.exceptionOrNull())
	 * }
	 * ```
	 */
	@Suppress("unused")
	fun findPageByPrimaryKey(
		databaseId: String,
		primaryKey: String,
		primaryKeyValue: String,
	): Result<JSONObject?> = runCatching {
		/* ---------------- obtain schema & primary key type ---------------- */
		val props = getDatabaseProperties(databaseId)
			?: throw IOException("Failed to fetch properties for database $databaseId")

		val primaryProp = props.optJSONObject(primaryKey)
			?: throw IllegalArgumentException("Primary key \"$primaryKey\" not present in database schema")

		val type = primaryProp.optString("type", "rich_text")

		/* ---------------- build filter for exact match ---------------- */
		val filter = JSONObject().apply {
			put("property", primaryKey)
			when (type) {
				"title" -> put("title", JSONObject().put("equals", primaryKeyValue))
				"rich_text" -> put("rich_text", JSONObject().put("equals", primaryKeyValue))
				"number" -> put(
					"number", JSONObject().put(
						"equals",
						primaryKeyValue.toDoubleOrNull() ?: primaryKeyValue
					)
				)

				"select" -> put("select", JSONObject().put("equals", primaryKeyValue))
				else -> put("rich_text", JSONObject().put("equals", primaryKeyValue))
			}
		}

		/* ---------------- execute search query ---------------- */
		val payload = JSONObject().apply {
			put("filter", filter)
			put("page_size", 1)
		}
		val body = payload
			.toString()
			.toRequestBody("application/json".toMediaTypeOrNull())

		val req = requestBuilder("https://api.notion.com/v1/databases/$databaseId/query")
			.post(body)
			.build()

		client.newCall(req).execute().use { resp ->
			if (!resp.isSuccessful) throw IOException("Unexpected code $resp")
			val results = JSONObject(resp.body?.string()).getJSONArray("results")
			if (results.length() > 0) results.getJSONObject(0) else null
		}
	}
}