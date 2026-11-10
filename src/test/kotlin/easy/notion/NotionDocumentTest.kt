package easy.notion

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotionDocumentTest {
	@Test
	fun `metadata query advances its cursor without reading any blocks`() = withServer { api, server ->
		server.enqueue(json(batch(listOf(page("one")), "metadata cursor")))
		server.enqueue(json(batch(listOf(page("two")))))
		val first = api.queryPages("db", pageSize = 1)
		val second = api.queryPages("db", pageSize = 1, cursor = first.nextCursor)
		assertEquals("two", second.pages.single().getString("id"))
		assertNull(second.nextCursor)
		repeat(2) { index ->
			val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
			assertEquals("/v1/databases/db/query", request.path)
			val body = JSONObject(request.body.readUtf8())
			if (index == 1) assertEquals("metadata cursor", body.getString("start_cursor"))
		}
		assertEquals(2, server.requestCount)
	}

	@Test
	fun `reads beyond 100 blocks and follows nested block cursors`() = withServer { api, server ->
		server.enqueue(json(batch((1..100).map { paragraph("body-$it") }, "root cursor")))
		server.enqueue(json(batch(listOf(JSONObject("""{"id":"quote","type":"quote","has_children":true,"quote":{"rich_text":[]}}""")))))
		server.enqueue(json(batch(listOf(paragraph("nested first")), "nested cursor")))
		server.enqueue(json(batch(listOf(paragraph("nested last")))))
		val content = api.renderPage(page("page")).values.getString("content")
		assertTrue(content.contains("<p>body-100</p><blockquote><p>nested first</p><p>nested last</p></blockquote>"))
		val paths = List(4) { requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).requestUrl!! }
		assertEquals(listOf(null, "root cursor", null, "nested cursor"), paths.map { it.queryParameter("start_cursor") })
	}

	@Test
	fun `blocked nested content is not returned as a partial document`() = withServer { api, server ->
		server.enqueue(json(batch(listOf(paragraph("valid prefix"),
			JSONObject("""{"id":"nested","type":"toggle","has_children":true,"toggle":{"rich_text":[]}}""")))))
		server.enqueue(MockResponse().setResponseCode(403))
		assertEquals(403, assertFailsWith<NotionRequestException> { api.renderPage(page("page")) }.status)
	}

	@Test
	fun `unrenderable content is rejected without reading a separate child page`() = withServer { api, server ->
		server.enqueue(json(batch(listOf(paragraph("prefix"),
			JSONObject("""{"id":"child","type":"child_page","has_children":true,"child_page":{"title":"separate page"}}""")))))
		assertFailsWith<IllegalArgumentException> { api.renderPage(page("page")) }
		assertEquals(1, server.requestCount)
	}

	@Test
	fun `signed files do not change the content version but a replacement does`() {
		fun fingerprint(url: String, title: String = "title") =
			NotionDocument(JSONObject().put("content", "<img src=\"$url\">").put("title", title), setOf(url))
				.fingerprint(listOf("title"))
		assertEquals(fingerprint("https://files.example/a?sig=1"), fingerprint("https://files.example/a?sig=2"))
		assertNotEquals(fingerprint("https://files.example/a"), fingerprint("https://files.example/b"))
		assertNotEquals(fingerprint("https://files.example/a"), fingerprint("https://files.example/a", "edited"))
	}

	@Test
	fun `clears a date with JSON null and exposes server backoff`() = withServer { api, server ->
		server.enqueue(json(JSONObject("""{"properties":{"retry":{"type":"date"}}}""")))
		server.enqueue(json(JSONObject().put("id", "page")))
		api.updateRecord("db", "page", "retry" to null)
		assertEquals("/v1/databases/db", requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path)
		val patch = JSONObject(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8())
		assertTrue(patch.getJSONObject("properties").getJSONObject("retry").isNull("date"))
		server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "27"))
		assertEquals(27L, assertFailsWith<NotionRequestException> { api.queryPages("db") }.retryAfterSeconds)
	}

	private fun withServer(run: (ENotion, MockWebServer) -> Unit) {
		MockWebServer().use { server ->
			server.start()
			val client = OkHttpClient.Builder().addInterceptor { chain ->
				chain.proceed(chain.request().newBuilder().url(
					server.url(chain.request().url.encodedPath + chain.request().url.encodedQuery?.let { "?$it" }.orEmpty())
				).build())
			}.build()
			run(ENotion("fixture", client), server)
		}
	}

	private fun page(id: String): JSONObject = JSONObject().put("id", id)
		.put("created_time", "2026-11-09T00:00:00Z").put("last_edited_time", "2026-11-09T00:00:00Z").put("properties", JSONObject())

	private fun paragraph(text: String): JSONObject = JSONObject().put("type", "paragraph").put("paragraph",
		JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("plain_text", text)
			.put("text", JSONObject().put("content", text)).put("annotations", JSONObject()))))

	private fun batch(items: List<JSONObject>, next: String? = null): JSONObject =
		JSONObject().put("results", JSONArray(items)).put("has_more", next != null).put("next_cursor", next ?: JSONObject.NULL)

	private fun json(body: JSONObject): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
}
