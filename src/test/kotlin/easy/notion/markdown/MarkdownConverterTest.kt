package easy.notion.markdown

import easy.notion.media.DataImageUploader
import easy.notion.media.ImageBlockBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownConverterTest {
	private fun newConverter(): MarkdownConverter {
		val stubUploader = DataImageUploader { _, _, _ -> null }
		return MarkdownConverter(ImageBlockBuilder(stubUploader))
	}

	@Test
	fun unsupportedCodeLanguageFallsBackToPlainText() {
		val blocks = newConverter().markdownToNotionBlocks(
			"""
			```pinescript
			plot(1)
			```
			""".trimIndent(),
		)

		val code = blocks.getJSONObject(0).getJSONObject("code")
		assertEquals("plain text", code.getString("language"))
	}

	@Test
	fun aliasCodeLanguageNormalizesToAllowed() {
		val blocks = newConverter().markdownToNotionBlocks(
			"""
			```js
			console.log('hi')
			```
			""".trimIndent(),
		)

		val code = blocks.getJSONObject(0).getJSONObject("code")
		assertEquals("javascript", code.getString("language"))
	}
}
