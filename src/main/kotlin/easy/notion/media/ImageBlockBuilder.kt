package easy.notion.media

import easy.notion.block.buildRichSpan
import easy.notion.util.isLikelyValidHttpUrl
import easy.notion.util.sanitizeUrl
import org.json.JSONArray
import org.json.JSONObject

internal class ImageBlockBuilder(
	private val defaultUploader: DataImageUploader,
	private val customUploader: DataImageUploader? = null,
) {

	fun buildImageBlock(url: String, alt: String = ""): JSONObject {
		val source = resolveImageSource(url, alt)
			?: return buildFallbackParagraph(alt)

		val captionArray = JSONArray().put(
			JSONObject().apply {
				put("type", "text")
				put("text", JSONObject().put("content", alt))
			},
		)

		return JSONObject().apply {
			put("object", "block")
			put("type", "image")
			put(
				"image",
				JSONObject().apply {
					when {
						source.fileUploadId != null -> {
							put("type", "file_upload")
							put("file_upload", JSONObject().put("id", source.fileUploadId))
						}

						source.externalUrl != null -> {
							put("type", "external")
							put("external", JSONObject().put("url", source.externalUrl))
						}
					}
					put("caption", captionArray)
				},
			)
		}
	}

	private fun resolveImageSource(url: String, alt: String): ImageSource? {
		val trimmed = url.trim()
		if (trimmed.startsWith("data:", ignoreCase = true)) {
			val parsed = parseDataImage(trimmed) ?: return null
			val (mime, bytes) = parsed
			val suggestedName = suggestFileName(alt, mime)

			val result = if (customUploader != null) {
				runCatching { customUploader!!.upload(mime, bytes, suggestedName) }.getOrNull()
			} else {
				defaultUploader.upload(mime, bytes, suggestedName)
			}

			return when {
				result?.fileUploadId != null -> ImageSource(fileUploadId = result.fileUploadId)
				result?.externalUrl != null -> {
					val sanitized = sanitizeUrl(result.externalUrl)
					if (isLikelyValidHttpUrl(sanitized)) ImageSource(externalUrl = sanitized) else null
				}

				else -> null
			}
		}

		val sanitized = sanitizeUrl(trimmed)
		return if (isLikelyValidHttpUrl(sanitized)) ImageSource(externalUrl = sanitized) else null
	}

	private fun buildFallbackParagraph(alt: String): JSONObject = JSONObject().apply {
		put("object", "block")
		put("type", "paragraph")
		put(
			"paragraph",
			JSONObject().put(
				"rich_text",
				JSONArray().put(buildRichSpan(alt.ifBlank { "[invalid image url]" })),
			),
		)
	}

	private fun parseDataImage(dataUri: String): Pair<String, ByteArray>? {
		val matcher = Regex("^data:([^;]+);base64,(.+)$", RegexOption.IGNORE_CASE).find(dataUri.trim())
			?: return null
		val mime = matcher.groupValues[1].lowercase()
		return try {
			val payload = java.util.Base64.getDecoder().decode(matcher.groupValues[2])
			mime to payload
		} catch (_: Exception) {
			null
		}
	}

	private fun suggestFileName(alt: String, mime: String): String {
		val safeAlt = alt.ifBlank { "image" }
			.replace(Regex("[^A-Za-z0-9._-]+"), "_")
			.trim('_')
			.ifBlank { "image" }
		val ext = when (mime.lowercase()) {
			"image/png" -> "png"
			"image/jpeg", "image/jpg" -> "jpg"
			"image/gif" -> "gif"
			"image/webp" -> "webp"
			"image/svg+xml" -> "svg"
			else -> "bin"
		}
		return if (safeAlt.endsWith(".$ext", ignoreCase = true)) safeAlt else "$safeAlt.$ext"
	}

	private data class ImageSource(
		val externalUrl: String? = null,
		val fileUploadId: String? = null,
	) {
		init {
			require((externalUrl != null) xor (fileUploadId != null))
		}
	}
}
