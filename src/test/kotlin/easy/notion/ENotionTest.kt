package easy.notion

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ENotionTest {
	@Test
	fun `insertRecord 旧签名兼容重载会转调新签名`() {
		withMockNotion { notion, server ->
			server.enqueue(
				jsonResponse(
					JSONObject().put(
						"properties",
						JSONObject().put(
							"ID",
							JSONObject()
								.put("type", "title")
								.put("title", JSONObject()),
						),
					),
				),
			)
			server.enqueue(jsonResponse(JSONObject().put("id", "page-1")))

			val result = notion.insertRecord("db-1", "ID" to "42")

			assertNotNull(result)
			assertEquals("page-1", result.getString("id"))

			val schemaRequest = server.takeRequest()
			assertEquals("GET", schemaRequest.method)
			assertEquals("/v1/databases/db-1", schemaRequest.path)

			val insertRequest = server.takeRequest()
			assertEquals("POST", insertRequest.method)
			assertEquals("/v1/pages", insertRequest.path)

			val body = JSONObject(insertRequest.body.readUtf8())
			assertEquals("db-1", body.getJSONObject("parent").getString("database_id"))
			assertFalse(body.has("children"))
			assertEquals(
				"42",
				body.getJSONObject("properties")
					.getJSONObject("ID")
					.getJSONArray("title")
					.getJSONObject(0)
					.getJSONObject("text")
					.getString("content"),
			)
		}
	}

	@Test
	fun `updateRecord 旧签名兼容重载会转调新签名`() {
		withMockNotion { notion, server ->
			server.enqueue(
				jsonResponse(
					JSONObject().put(
						"properties",
						JSONObject().put(
							"Message",
							JSONObject()
								.put("type", "rich_text")
								.put("rich_text", JSONObject()),
						),
					),
				),
			)
			server.enqueue(jsonResponse(JSONObject().put("id", "page-1")))

			val result = notion.updateRecord("db-1", "page-1", "Message" to "Updated")

			assertNotNull(result)
			assertEquals("page-1", result.getString("id"))

			val schemaRequest = server.takeRequest()
			assertEquals("GET", schemaRequest.method)
			assertEquals("/v1/databases/db-1", schemaRequest.path)

			val patchRequest = server.takeRequest()
			assertEquals("PATCH", patchRequest.method)
			assertEquals("/v1/pages/page-1", patchRequest.path)

			val body = JSONObject(patchRequest.body.readUtf8())
			assertEquals(
				"Updated",
				body.getJSONObject("properties")
					.getJSONObject("Message")
					.getJSONArray("rich_text")
					.getJSONObject(0)
					.getJSONObject("text")
					.getString("content"),
			)
		}
	}

	@Test
	fun `getLatestTimestamp 支持 date start 的 ISO offset 时间`() {
		assertLatestTimestamp(
			property = timestampProperty("date", "2026-04-20T05:09:00.000+00:00"),
			expected = "2026-04-20 05:09:00",
		)
	}

	@Test
	fun `getLatestTimestamp 支持 rich_text 的空格分隔 offset 时间`() {
		assertLatestTimestamp(
			property = timestampProperty("rich_text", "2026-04-20 00:01:18+00:00"),
			expected = "2026-04-20 00:01:18",
		)
	}

	@Test
	fun `getLatestTimestamp 支持 title 的空格分隔 offset 时间`() {
		assertLatestTimestamp(
			property = timestampProperty("title", "2026-04-20 00:01:18+00:00"),
			expected = "2026-04-20 00:01:18",
		)
	}

	private fun assertLatestTimestamp(property: JSONObject, expected: String) {
		withMockNotion { notion, server ->
			server.enqueue(
				jsonResponse(
					JSONObject().put(
						"results",
						JSONArray().put(
							JSONObject().put(
								"properties",
								JSONObject().put("Created", property),
							),
						),
					),
				),
			)

			val actual = notion.getLatestTimestamp("db-1", "Created")

			assertEquals(expected, actual)

			val request = server.takeRequest()
			assertEquals("POST", request.method)
			assertEquals("/v1/databases/db-1/query", request.path)
		}
	}

	private fun timestampProperty(type: String, value: String): JSONObject = when (type) {
		"date" -> JSONObject()
			.put("type", "date")
			.put("date", JSONObject().put("start", value))

		"rich_text" -> JSONObject()
			.put("type", "rich_text")
			.put("rich_text", textArray(value))

		"title" -> JSONObject()
			.put("type", "title")
			.put("title", textArray(value))

		else -> error("Unsupported property type: $type")
	}

	private fun textArray(value: String): JSONArray = JSONArray().put(
		JSONObject()
			.put("plain_text", value)
			.put("text", JSONObject().put("content", value)),
	)

	private fun jsonResponse(body: JSONObject): MockResponse = MockResponse()
		.setHeader("Content-Type", "application/json")
		.setBody(body.toString())

	private fun withMockNotion(block: (ENotion, MockWebServer) -> Unit) {
		val server = MockWebServer()
		server.start()
		try {
			val baseUrl = server.url("/")
			val client = OkHttpClient.Builder()
				.addInterceptor { chain ->
					val request = chain.request()
					val redirectedUrl = request.url.newBuilder()
						.scheme(baseUrl.scheme)
						.host(baseUrl.host)
						.port(baseUrl.port)
						.build()
					chain.proceed(request.newBuilder().url(redirectedUrl).build())
				}
				.build()
			block(ENotion("test-key", client), server)
		} finally {
			server.shutdown()
		}
	}
}
