import easy.notion.ENotion
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
		n.insertRecord(
			databaseid, markdownContent = """
![](https://m.media-amazon.com/images/I/41K9LB4hoxL._SL500_.jpg)
![](https://m.media-amazon.com/images/I/51xVqNkRH8L._SL500_.jpg)
![](https://m.media-amazon.com/images/I/41ekKOkfNsL._SL500_.jpg)
""".trimIndent(),
			"title" to Date()
		)

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

		/*		val sorts = JSONArray().apply {
					put(JSONObject().apply {
						put("timestamp", "last_edited_time")  // 系统时间戳排序
						put("direction", "descending")
					})
				}
				val jsonarr = n.getDataBase(databaseid, sorts = sorts)
				println(jsonarr.toString())*/
	}
}