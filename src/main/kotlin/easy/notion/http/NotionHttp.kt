package easy.notion.http

import okhttp3.Request

internal fun notionRequestBuilder(apikey: String, url: String): Request.Builder =
	Request.Builder()
		.url(url)
		.addHeader("Authorization", "Bearer $apikey")
		.addHeader("Notion-Version", "2022-06-28")
