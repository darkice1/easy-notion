package easy.notion

import java.io.IOException

/** HTTP failure with optional Retry-After in seconds. */
class NotionRequestException(val status: Int, val retryAfterSeconds: Long?) :
	IOException("Notion HTTP $status")
