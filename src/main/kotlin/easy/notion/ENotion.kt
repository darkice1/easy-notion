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
	 * 渲染富文本数组为 HTML，保留链接并在新窗口打开
	 */
	private fun renderRichText(richArray: JSONArray): String {
		val sb = StringBuilder()
		for (i in 0 until richArray.length()) {
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

	@Suppress("unused")
	fun getDataBase(
		databaseId: String,
		pageSize: Int = 100,
		filter: JSONObject? = null,
		sorts: JSONArray? = null,
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
			val output = JSONArray()

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

						else -> {
							// 其他类型统一转为字符串
//							item[key] = prop.toString()
							item.put(key, prop.toString())
						}
					}
				}

				// 获取页面块内容并手动渲染 HTML
				val blocks = fetchBlockChildren(page.getString("id"))
				val htmlBuilder = StringBuilder()
				var currentListType: String? = null

				for (j in 0 until blocks.length()) {
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
							"</style>"
					htmlBuilder.insert(0, styleTag)
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
	 * 向指定 Notion 数据库插入一条记录
	 *
	 * 1. 通过可变参数 `fields` 直接传入列名-值对，免去手动构造 `Map`。
	 * 2. 若数据库 ID 不存在，方法返回 `null`，不会执行写入。
	 * 3. 写入时会先读取数据库属性 schema，并依据列的「实际类型」自动转换为正确的 Notion 属性结构。
	 * 4. 对不匹配的类型自动做“尽可能合理”的转换（见 `buildProperties`）。
	 *
	 * @param databaseId 目标数据库 ID
	 * @param fields     形如 `"Name" to "Foo", "Score" to 99` 的可变参数
	 * @return 若插入成功，返回 Notion 返回的页面 JSON；否则返回 `null`
	 */
	@Suppress("unused")
	fun insertRecord(
		databaseId: String,
		vararg fields: Pair<String, Any?>,
	): JSONObject? {

		/* ---------- 检查数据库是否存在并获取 schema ---------- */
		val dbJson = fetchDatabaseSchema(databaseId) ?: return null
		val schemaProps = dbJson.getJSONObject("properties")

		/* ---------- 组装 properties ---------- */
		val properties = buildProperties(schemaProps, fields)
		if (properties.isEmpty) return null   // 没有可写入的数据

		/* ---------- 构建请求 ---------- */
		val root = JSONObject().apply {
			put("parent", JSONObject().apply { put("database_id", databaseId) })
			put("properties", properties)
		}

		val body = root.toString().toRequestBody("application/json".toMediaTypeOrNull())

		val pageRequest = requestBuilder("https://api.notion.com/v1/pages")
			.post(body)
			.build()

		return client.newCall(pageRequest).execute().use { resp ->
			if (!resp.isSuccessful) return null
			JSONObject(resp.body?.string())
		}
	}

	/**
	 * 更新指定页面的列内容
	 *
	 * @param databaseId 所在数据库 ID
	 * @param pageId     目标页面 ID
	 * @param fields     待更新的列及新值
	 * @return 更新成功返回页面 JSON；否则返回 null
	 *
	 * 类型转换规则与 `insertRecord` 一致。
	 */
	@Suppress("unused")
	fun updateRecord(
		databaseId: String,
		pageId: String,
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

		return client.newCall(patchReq).execute().use { resp ->
			if (!resp.isSuccessful) return null
			JSONObject(resp.body?.string())
		}
	}
}