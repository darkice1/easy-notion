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

import easy.notion.http.notionRequestBuilder
import easy.notion.markdown.MarkdownConverter
import easy.notion.media.DataImageUploader
import easy.notion.media.ImageBlockBuilder
import easy.notion.media.NotionDirectImageUploader
import easy.notion.render.renderGenericBlockHtml
import easy.notion.render.renderHeadingHtml
import easy.notion.render.renderListItemHtml
import easy.notion.render.renderRichText
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.concurrent.TimeUnit
import easy.notion.media.DataImageUploadResult as MediaDataImageUploadResult

private const val DEFAULT_VIDEO_WIDTH = 640
private const val DEFAULT_VIDEO_HEIGHT = 360
private val NORMALIZED_TIMESTAMP_FORMATTER: DateTimeFormatter =
	DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val OFFSET_TIMESTAMP_FORMATTERS: List<DateTimeFormatter> = listOf(
	DateTimeFormatter.ISO_OFFSET_DATE_TIME,
	DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"),
	DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX"),
)
private val LOCAL_TIMESTAMP_FORMATTERS: List<DateTimeFormatter> = listOf(
	DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
	DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd HH:mm")
		.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
		.parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
		.toFormatter(),
)

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
	/**
	 * 可选的 data: 图片上传回调。
	 *
	 * 当 markdownContent 中出现 `data:image/...;base64,...` 时，库会调用该回调将图片字节上传到
	 * 你选择的托管位置（例如：自建对象存储、GitHub、自定义网关）。若未提供回调，则默认使用
	 * Notion 的 Direct Upload（/v1/file_uploads + /send）将图片托管到 Notion。
	 */
	dataImageUploader: ((mime: String, data: ByteArray, suggestedName: String?) -> DataImageUploadResult?)? = null,
) {
	private val defaultImageUploader = NotionDirectImageUploader(client, apikey)
	private val customImageUploader = dataImageUploader?.let { cb ->
		DataImageUploader { mime, data, suggestedName ->
			cb.invoke(mime, data, suggestedName)?.toExternal()
		}
	}

	data class DataImageUploadResult(
		val externalUrl: String? = null,
		val fileUploadId: String? = null,
	) {
		init {
			require((externalUrl != null) xor (fileUploadId != null)) {
				"DataImageUploadResult 需要在 externalUrl 与 fileUploadId 之间二选一"
			}
		}

		internal fun toExternal(): MediaDataImageUploadResult =
			MediaDataImageUploadResult(externalUrl = externalUrl, fileUploadId = fileUploadId)
	}

	private val imageBlockBuilder = ImageBlockBuilder(defaultImageUploader, customImageUploader)
	private val markdownConverter = MarkdownConverter(imageBlockBuilder)

	private fun markdownToNotionBlocks(markdown: String): JSONArray =
		markdownConverter.markdownToNotionBlocks(markdown)

	/** Returns a Request.Builder pre‑配置 with常见 Notion headers. */
	private fun requestBuilder(url: String): Request.Builder =
		notionRequestBuilder(apikey, url)

	/** Read one cursor page of Notion metadata. Does not fetch or render any page blocks. */
	fun queryPages(
		databaseId: String,
		pageSize: Int = 100,
		filter: JSONObject? = null,
		sorts: JSONArray? = null,
		cursor: String? = null,
	): NotionPageBatch {
		require(pageSize in 1..100)
		val payload = JSONObject().put("page_size", pageSize).put("filter", filter)
			.put("sorts", sorts).put("start_cursor", cursor)
		val request = requestBuilder("https://api.notion.com/v1/databases/$databaseId/query")
			.post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
		return parsePageBatch(readJson(request), cursor)
	}

	/** Read the current Notion page properties without touching its content or updating it. */
	@Suppress("unused")
	fun readPage(pageId: String): JSONObject =
		readJson(requestBuilder("https://api.notion.com/v1/pages/$pageId").get().build())

	/** Write only the supplied, already typed properties. Values such as date:null are sent as-is. */
	fun patchPageProperties(pageId: String, properties: JSONObject): JSONObject {
		require(!properties.isEmpty)
		val payload = JSONObject().put("properties", properties)
		val request = requestBuilder("https://api.notion.com/v1/pages/$pageId")
			.patch(payload.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
		return readJson(request)
	}

	/** Read the database schema, preserving HTTP errors for callers that distinguish failures. */
	@Suppress("unused")
	fun readDatabaseProperties(databaseId: String): JSONObject =
		readJson(requestBuilder("https://api.notion.com/v1/databases/$databaseId").get().build()).getJSONObject("properties")

	/** Render one page with all block pages, without any remote writes. */
	fun renderPage(page: JSONObject): NotionDocument {
		check(!page.optBoolean("archived") && !page.optBoolean("in_trash")) { "Notion page is archived" }
		return renderPage(page, null, persistTransforms = false)
	}

	private fun readJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
		if (!response.isSuccessful) {
			val retryAfter = response.header("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 }
			throw NotionRequestException(response.code, retryAfter)
		}
		JSONObject(requireNotNull(response.body).string())
	}

	private fun parsePageBatch(json: JSONObject, cursor: String?): NotionPageBatch {
		val next = if (json.optBoolean("has_more"))
			json.optString("next_cursor").takeIf { it.isNotBlank() && it != cursor }
				?: throw IOException("Notion pagination did not advance")
		else null
		val results = json.getJSONArray("results")
		return NotionPageBatch((0 until results.length()).map(results::getJSONObject), next)
	}

	private fun fetchBlockChildren(blockId: String): List<JSONObject> {
		val seen = mutableSetOf<String>()
		return buildList {
			var cursor: String? = null
			do {
				val url = "https://api.notion.com/v1/blocks/$blockId/children".toHttpUrl().newBuilder()
					.addQueryParameter("page_size", "100").apply {
						cursor?.let { addQueryParameter("start_cursor", it) }
					}.build()
				val batch = parsePageBatch(readJson(requestBuilder(url.toString()).get().build()), cursor)
				addAll(batch.pages)
				cursor = batch.nextCursor
				if (cursor != null && !seen.add(cursor)) throw IOException("Notion block pagination repeated a cursor")
			} while (cursor != null)
		}
	}

	/** Compatibility API: query one database batch and render all pages in that batch. */
	@Suppress("unused")
	fun getDataBase(
		databaseId: String,
		pageSize: Int = 100,
		filter: JSONObject? = null,
		sorts: JSONArray? = null,
		transformers: Array<BlockTransformer>? = null,
	): JSONArray = JSONArray(queryPages(databaseId, pageSize, filter, sorts).pages.map {
		renderPage(it, transformers, persistTransforms = true).values
	})

	private fun renderPage(page: JSONObject, transformers: Array<BlockTransformer>?, persistTransforms: Boolean): NotionDocument {
		val properties = page.getJSONObject("properties")
		val item = JSONObject()

		item.put("id", page.getString("id"))
		item.put("created_time", page.getString("created_time"))
		item.put("last_edited_time", page.getString("last_edited_time"))

		for (keyAny in properties.keys()) {
			val prop = properties.getJSONObject(keyAny)
			when (prop.getString("type")) {
				"title", "rich_text" -> item.put(keyAny, propertyText(prop.getJSONArray(prop.getString("type"))))
				"number" -> {
					item.put(keyAny, prop.get("number"))
				}

				"checkbox" -> {
					item.put(keyAny, prop.get("checkbox"))
				}

				"select" -> {
					val sel = prop.optJSONObject("select")
					item.put(keyAny, sel?.getString("name") ?: "")
				}

				"multi_select" -> {
					val arr = prop.getJSONArray("multi_select")
					val names = arr.map { (it as JSONObject).getString("name") }
					item.put(keyAny, names)
				}

				"date" -> {
					val dateObj = prop.optJSONObject("date")
					item.put(keyAny, dateObj?.getString("start") ?: "")
				}

				"url" -> {
					item.put(keyAny, prop.optString("url", ""))
				}

				"email" -> {
					item.put(keyAny, prop.optString("email", ""))
				}

				"phone_number" -> {
					item.put(keyAny, prop.optString("phone_number", ""))
				}

				"created_time" -> {
					item.put(keyAny, prop.getString("created_time"))
				}

				"last_edited_time" -> {
					item.put(keyAny, prop.getString("last_edited_time"))
				}
				"button" -> {
					continue
				}

				else -> item.put(keyAny, prop.toString())
			}
		}
		val hostedFiles = mutableSetOf<String>()
		val html = renderBlocks(fetchBlockChildren(page.getString("id")), 0, hostedFiles, transformers, persistTransforms)
		// 如果有内容，插入样式
		if (html.isNotEmpty()) {
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
		}
		item.put("content", html)
		return NotionDocument(item, hostedFiles)
	}

	private fun propertyText(parts: JSONArray): String = parts.joinToString("") {
		val part = it as JSONObject
		part.optString("plain_text", part.optJSONObject("text")?.optString("content").orEmpty())
	}

	private fun renderBlocks(
		blocks: List<JSONObject>,
		depth: Int,
		hostedFiles: MutableSet<String>,
		transformers: Array<BlockTransformer>?,
		persistTransforms: Boolean,
	): String {
		fun renderChildren(id: String, level: Int): String {
			check(level <= 32) { "Notion block nesting exceeds 32 levels" }
			return renderBlocks(fetchBlockChildren(id), level, hostedFiles, transformers, persistTransforms)
		}
		val htmlBuilder = StringBuilder()
		var currentListType: String? = null

		for (block in blocks) {
			val originalType = block.getString("type")
			block.optJSONObject(originalType)?.optJSONObject("file")?.optString("url")
				?.takeIf { it.isNotBlank() }?.let(hostedFiles::add)
			/* ---------- transformer hook ---------- */
			var modified = false
			if (transformers != null) {
				for (t in transformers) {
					if (t.handle(block)) modified = true
				}
			}
			if (modified && persistTransforms) {
				// Persist the change to Notion so the remote document stays in sync.
				// Then continue rendering *this same, now‑modified* block so that
				// htmlBuilder reflects the latest content.
				performBlockReplace(block)
			}
			val type = block.getString("type")
			fun children(): String = if (block.optBoolean("has_children"))
				renderChildren(block.getString("id"), depth + 1) else ""
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
					htmlBuilder.append("<p>").append(content).append("</p>").append(children())
				}

				"heading_1", "heading_2", "heading_3" -> {
					val level = type.removePrefix("heading_")
					val contentArr = block.getJSONObject(type).getJSONArray("rich_text")
					htmlBuilder.append(renderHeadingHtml(level, contentArr)).append(children())
				}

				"bulleted_list_item", "numbered_list_item" -> {
					if (currentListType != type) {
						if (currentListType == "bulleted_list_item") htmlBuilder.append("</ul>")
						if (currentListType == "numbered_list_item") htmlBuilder.append("</ol>")
						htmlBuilder.append(if (type == "bulleted_list_item") "<ul>" else "<ol>")
						currentListType = type
					}
					val key = if (type == "bulleted_list_item") "bulleted_list_item" else "numbered_list_item"
					val contentArr = block.getJSONObject(key).getJSONArray("rich_text")
					val itemHtml = renderListItemHtml(contentArr)
					htmlBuilder.append(itemHtml.removeSuffix("</li>")).append(children()).append("</li>")
				}
				"quote" -> htmlBuilder.append("<blockquote>")
					.append(renderRichText(block.getJSONObject("quote").getJSONArray("rich_text")))
					.append(children()).append("</blockquote>")
				"callout" -> {
					val content = renderRichText(block.getJSONObject("callout").getJSONArray("rich_text"))
					htmlBuilder.append("<div class=\"callout\">").append(content).append(children()).append("</div>")
				}
				"divider" -> htmlBuilder.append("<hr/>")
				"video" -> {
					val videoHtml = renderVideoBlock(block)
					if (videoHtml.isNotEmpty()) htmlBuilder.append(videoHtml)
				}
				"image" -> {
					val imageObj = block.getJSONObject("image")
					val (purl, caption) = extractImageUrlAndCaption(imageObj)
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
					if (hasCol && rows.isNotEmpty()) {
						htmlBuilder.append("<thead><tr>")
						val hdr = rows[0].getJSONObject("table_row").getJSONArray("cells")
						for (c in 0 until hdr.length()) {
							val txt = renderRichText(hdr.getJSONArray(c))
							htmlBuilder.append("<th>").append(txt).append("</th>")
						}
						htmlBuilder.append("</tr></thead>")
					}
					htmlBuilder.append("<tbody>")
					for (r in (if (hasCol) 1 else 0) until rows.size) {
						htmlBuilder.append("<tr>")
						val cells = rows[r].getJSONObject("table_row").getJSONArray("cells")
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
				"child_page", "child_database" -> {
					require(persistTransforms) { "Nested pages and databases are separate documents" }
				}
				else -> {
					val text = renderGenericBlockHtml(block)
					val nested = children()
					require(persistTransforms || text.isNotBlank() || nested.isNotBlank() ||
						type in setOf("breadcrumb", "table_of_contents")) { "Unsupported Notion content block: $type" }
					htmlBuilder.append(text).append(nested)
				}
			}
		}
		// 结束后关闭列表
		if (currentListType == "bulleted_list_item") htmlBuilder.append("</ul>")
		if (currentListType == "numbered_list_item") htmlBuilder.append("</ol>")
		return htmlBuilder.toString()
	}

	/** Replace a single block [blk] (any type) by appending a fresh copy that contains
	 *  the current data from [blk], moving it right after the original, then archiving
	 *  the original block.  This preserves order and avoids the PATCH‑400 limitation
	 *  on media blocks.  Returns `true` on success. */
	private fun performBlockReplace(blk: JSONObject): Boolean {
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
					prop.put("date", if (value == null) JSONObject.NULL else JSONObject().put("start", start))
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

	/** 兼容旧签名：不传 `markdownContent` 时转调到新签名。 */
	@Suppress("unused")
	fun insertRecord(
		databaseId: String,
		vararg fields: Pair<String, Any?>,
	): JSONObject? = insertRecord(databaseId, null, *fields)

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

	/** 兼容旧签名：不传 `markdownContent` 时转调到新签名。 */
	@Suppress("unused")
	fun updateRecord(
		databaseId: String,
		pageId: String,
		vararg fields: Pair<String, Any?>,
	): JSONObject? = updateRecord(databaseId, pageId, null, *fields)

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

	private fun extractPlainTextValue(items: JSONArray?): String? {
		if (items == null || items.isEmpty) return null

		val content = buildString {
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				append(
					item.optString("plain_text").ifBlank {
						item.optJSONObject("text")?.optString("content").orEmpty()
					},
				)
			}
		}.trim()

		return content.ifBlank { null }
	}

	private fun extractTimestampRawValue(property: JSONObject): String? = when (property.optString("type")) {
		"date" -> property.optJSONObject("date")?.optString("start")?.trim()?.ifBlank { null }
		"rich_text" -> extractPlainTextValue(property.optJSONArray("rich_text"))
		"title" -> extractPlainTextValue(property.optJSONArray("title"))
		else -> null
	}

	private fun normalizeTimestamp(raw: String): String? {
		val value = raw.trim()
		if (value.isEmpty()) return null

		for (formatter in OFFSET_TIMESTAMP_FORMATTERS) {
			try {
				return OffsetDateTime.parse(value, formatter)
					.toLocalDateTime()
					.format(NORMALIZED_TIMESTAMP_FORMATTER)
			} catch (_: Exception) {
			}
		}

		for (formatter in LOCAL_TIMESTAMP_FORMATTERS) {
			try {
				return LocalDateTime.parse(value, formatter)
					.format(NORMALIZED_TIMESTAMP_FORMATTER)
			} catch (_: Exception) {
			}
		}

		return null
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
			val raw = extractTimestampRawValue(fieldObj) ?: return null

			/* ---------- 3. normalise to "yyyy-MM-dd HH:mm:ss" ---------- */
			return normalizeTimestamp(raw)
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

/**
 * Helper method to extract image URL and caption from an image block object.
 */
private fun extractImageUrlAndCaption(imageObj: JSONObject): Pair<String, String> {
	val purl = if (imageObj.has("file")) imageObj.getJSONObject("file").getString("url")
	else imageObj.getJSONObject("external").getString("url")
	val caption = imageObj.optJSONArray("caption")
		?.joinToString("") { (it as JSONObject).getJSONObject("text").getString("content") }
		?: ""
	return purl to caption
}

private fun renderVideoBlock(block: JSONObject): String {
	val videoObj = block.optJSONObject("video") ?: return ""
	val captionHtml = videoObj.optJSONArray("caption")
		?.let { renderRichText(it) }
		?.takeIf { it.isNotBlank() }

	val format = block.optJSONObject("format")
	val rawWidth = format?.optDouble("block_width", -1.0)?.takeIf { it > 0 }?.toInt()
	val rawHeight = format?.optDouble("block_height", -1.0)?.takeIf { it > 0 }?.toInt()
	val (resolvedWidth, resolvedHeight) = when {
		rawWidth != null && rawHeight != null -> rawWidth to rawHeight
		rawWidth != null -> rawWidth to (rawWidth * DEFAULT_VIDEO_HEIGHT / DEFAULT_VIDEO_WIDTH).coerceAtLeast(1)
		rawHeight != null -> (rawHeight * DEFAULT_VIDEO_WIDTH / DEFAULT_VIDEO_HEIGHT).coerceAtLeast(1) to rawHeight
		else -> DEFAULT_VIDEO_WIDTH to DEFAULT_VIDEO_HEIGHT
	}
	val styleAttr = "width:${resolvedWidth}px;height:${resolvedHeight}px;"

	val contentHtml = when (videoObj.optString("type")) {
		"file" -> {
			val fileUrl = videoObj.optJSONObject("file")?.optString("url")
				?.takeIf { it.isNotBlank() } ?: return ""
			val safeUrl = fileUrl.replace("\"", "&quot;")
			buildString {
				append("<video src=\"").append(safeUrl)
					.append("\" controls preload=\"metadata\" width=\"").append(resolvedWidth)
					.append("\" height=\"").append(resolvedHeight).append("\"")
				if (styleAttr.isNotEmpty()) append(" style=\"").append(styleAttr).append("\"")
				append("></video>")
			}
		}

		"external" -> {
			val rawUrl = videoObj.optJSONObject("external")?.optString("url")
				?.takeIf { it.isNotBlank() } ?: return ""
			val embedInfo = resolveExternalVideoEmbed(rawUrl)
			if (embedInfo != null) {
				val safeEmbedUrl = embedInfo.embedUrl.replace("\"", "&quot;")
				buildString {
					append("<iframe src=\"").append(safeEmbedUrl).append("\" frameborder=\"0\"")
						.append(" width=\"").append(resolvedWidth).append("\" height=\"").append(resolvedHeight).append("\"")
					if (styleAttr.isNotEmpty()) append(" style=\"").append(styleAttr).append("\"")
					embedInfo.allow?.let { append(" allow=\"").append(it).append("\"") }
					if (embedInfo.allowFullScreen) append(" allowfullscreen")
					append(" loading=\"lazy\"></iframe>")
				}
			} else {
				val safeUrl = rawUrl.replace("\"", "&quot;")
				buildString {
					append("<a href=\"").append(safeUrl)
						.append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
						.append(safeUrl)
						.append("</a>")
				}
			}
		}

		else -> ""
	}

	if (contentHtml.isBlank()) return ""

	return buildString {
		append("<figure class=\"notion-video\">")
		append(contentHtml)
		captionHtml?.let {
			append("<figcaption>").append(it).append("</figcaption>")
		}
		append("</figure>")
	}
}

private data class ExternalVideoEmbed(
	val embedUrl: String,
	val allow: String? = null,
	val allowFullScreen: Boolean = true,
)

private fun resolveExternalVideoEmbed(rawUrl: String): ExternalVideoEmbed? {
	val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
	val host = uri.host?.lowercase() ?: return null

	return when {
		host.contains("youtube.com") || host.contains("youtu.be") -> {
			val videoId = extractYouTubeId(uri)
			videoId?.let {
				ExternalVideoEmbed(
					embedUrl = "https://www.youtube.com/embed/$it",
					allow = "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share",
					allowFullScreen = true,
				)
			}
		}

		host.contains("vimeo.com") -> {
			val videoId = extractVimeoId(uri)
			videoId?.let {
				ExternalVideoEmbed(
					embedUrl = "https://player.vimeo.com/video/$it",
					allow = "autoplay; fullscreen; picture-in-picture",
					allowFullScreen = true,
				)
			}
		}

		else -> null
	}
}

private fun extractYouTubeId(uri: URI): String? {
	val host = uri.host?.lowercase() ?: return null
	val path = uri.path ?: ""

	return when {
		host.contains("youtu.be") -> path.trim('/').substringBefore('/').substringBefore('?')
		path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/').substringBefore('?')
		path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/').substringBefore('?')
		else -> {
			val query = uri.query ?: return null
			query.split("&")
				.mapNotNull { kv ->
					val idx = kv.indexOf('=')
					if (idx <= 0) null else kv.take(idx) to kv.substring(idx + 1)
				}
				.firstOrNull { (key, _) -> key == "v" }
				?.second
		}
	}?.takeIf { it.isNotBlank() }
}

private fun extractVimeoId(uri: URI): String? {
	val path = uri.path ?: return null
	val segments = path.trim('/').split('/').filter { it.isNotBlank() }
	if (segments.isEmpty()) return null

	val candidate = segments
		.asReversed()
		.firstOrNull { segment -> segment.all { it.isDigit() } }
		?: return null

	val id = candidate.substringBefore('?')
	return id.takeIf { it.isNotBlank() && id.all { ch -> ch.isDigit() } }
}
