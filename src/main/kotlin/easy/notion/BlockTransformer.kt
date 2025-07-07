package easy.notion

import org.json.JSONObject

/**
 * 通用块处理器：
 *  1. 在遍历 Notion 块树时被调用；
 *  2. 若实现类对 block 做了原地修改，则返回 true，表示需向 Notion 发送 PATCH。
 */
abstract class BlockTransformer {

	/**
	 * @param block  当前 Notion 块的完整 JSON
	 * @return       若返回 true，遍历器应当把修改后的 block 通过 API 写回
	 */
	abstract fun handle(block: JSONObject): Boolean
}
