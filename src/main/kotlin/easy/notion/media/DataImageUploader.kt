package easy.notion.media

import easy.notion.http.notionRequestBuilder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class DataImageUploadResult(
	val externalUrl: String? = null,
	val fileUploadId: String? = null,
) {
	init {
		require((externalUrl != null) xor (fileUploadId != null)) {
			"DataImageUploadResult 必须在 externalUrl 与 fileUploadId 中二选一"
		}
	}
}

internal fun interface DataImageUploader {
	fun upload(mime: String, data: ByteArray, suggestedName: String): DataImageUploadResult?
}

internal class NotionDirectImageUploader(
	private val client: OkHttpClient,
	private val apikey: String,
) : DataImageUploader {
	override fun upload(mime: String, data: ByteArray, suggestedName: String): DataImageUploadResult? {
		return try {
			val createPayload = JSONObject()
				.put("filename", suggestedName)
				.put("content_type", mime)

			val createRequest = notionRequestBuilder(apikey, "https://api.notion.com/v1/file_uploads")
				.post(createPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
				.build()

			val (uploadId, uploadUrl) = client.newCall(createRequest).execute().use { resp ->
				if (!resp.isSuccessful) return null
				val body = resp.body?.string().orEmpty()
				val json = JSONObject(body.ifBlank { "{}" })
				val id = json.optString("id")
				val url = json.optString("upload_url")
				if (id.isNullOrBlank() || url.isNullOrBlank()) return null
				id to url
			}

			val multipartBody = MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart(
					"file",
					suggestedName,
					data.toRequestBody(mime.toMediaTypeOrNull()),
				)
				.build()

			val sendRequest: Request = notionRequestBuilder(apikey, uploadUrl)
				.post(multipartBody)
				.build()

			client.newCall(sendRequest).execute().use { resp ->
				if (!resp.isSuccessful) return null
			}

			DataImageUploadResult(fileUploadId = uploadId)
		} catch (_: Exception) {
			null
		}
	}
}
