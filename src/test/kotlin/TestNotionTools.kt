import easy.notion.ENotion
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.util.*

object TestNotionTools {
	@JvmStatic
	fun main(args: Array<String>) {
		val prop = Properties().apply {
			load(FileInputStream("config.properties"))
		}
		println(prop)

		val apikey = prop.getProperty("NOTIONKEY").toString()
		val databaseid = prop.getProperty("DATABASEID").toString()
		val n = ENotion(apikey)
		val filter = JSONObject().apply {
			put("property", "wpid")
			put("number", JSONObject().put("is_empty", true))   // 数字列用 number.is_empty
		}

		val sorts = JSONArray().apply {
			put(JSONObject().apply {
				put("timestamp", "last_edited_time")  // 系统时间戳排序
				put("direction", "descending")
			})
		}

		val pages = n.getDataBase(databaseid, filter = filter, sorts = sorts)
		println(pages)
//		println(n.insertRecord(databaseid,"wpid" to "${System.currentTimeMillis()}"))
//		println(
//			n.updateRecord(
//				databaseid,
//				"1d800250-dfa5-8000-8b54-ff09a28e6f5a",
//				"wpid" to "${System.currentTimeMillis()}",
//					"wpupdatetime" to "2025-05-06 03:23:23"
//			)
//		)
	}
}