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
		/*		n.insertRecord(
					databaseid, markdownContent = """
		![gradient-640x320](data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc2NDAnIGhlaWdodD0nMzIwJz4KICA8ZGVmcz4KICAgIDxsaW5lYXJHcmFkaWVudCBpZD0nZycgeDE9JzAlJyB5MT0nMCUnIHgyPScxMDAlJyB5Mj0nMCUnPgogICAgICA8c3RvcCBvZmZzZXQ9JzAlJyBzdG9wLWNvbG9yPSIjMGVhNWU5Ii8+CiAgICAgIDxzdG9wIG9mZnNldD0nNTAlJyBzdG9wLWNvbG9yPSIjOGI1Y2Y2Ii8+CiAgICAgIDxzdG9wIG9mZnNldD0nMTAwJScgc3RvcC1jb2xvcj0iI2VmNDQ0NCIvPgogICAgPC9saW5lYXJHcmFkaWVudD4KICA8L2RlZnM+CiAgPHJlY3Qgd2lkdGg9JzY0MCcgaGVpZ2h0PSczMjAnIGZpbGw9J3VybCgjZyknLz4KPC9zdmc+)
		""".trimIndent(),
					"title" to Date()
				)*/

//		println(n.getDataBase(databaseid,1))
//		println(n.findNotionDatabase("Neo-WordPress"))
//		println(pages)
//		println(n.insertRecord(databaseid,"wpid" to "${System.currentTimeMillis()}"))
//		println(
//			n.updateRecord(
//				databaseid,
//				"1d800250-dfa5-8000-8b54-ff09a28e6f5a",
//				"wpid" to "${System.currentTimeMillis()}",
//					"wpupdatetime" to "2025-05-06 03:23:23"
//			)
//		)

		val sorts = JSONArray().apply {
					put(JSONObject().apply {
						put("timestamp", "last_edited_time")  // 系统时间戳排序
						put("direction", "descending")
					})
				}
		val jsonarr = n.getDataBase(databaseid, 1, sorts = sorts)
		println(jsonarr.toString())
	}
}