package easy.notion

import org.json.JSONObject

/** Raw metadata for one Notion query page, with its continuation cursor if any. */
data class NotionPageBatch(val pages: List<JSONObject>, val nextCursor: String?)
