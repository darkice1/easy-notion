import easy.config.Config
import easy.notion.ENotion

object TestNotionTools {
	@JvmStatic
	fun main(args: Array<String>) {
		val apikey = Config.getProperty("NOTIONKEY").toString()
		val databaseid = Config.getProperty("DATABASEID").toString()
		val n = ENotion(apikey)
//		println(n.getDataBase(databaseid))
//		println(n.insertRecord(databaseid,"wpid" to "${System.currentTimeMillis()}"))
		println(
			n.updateRecord(
				databaseid,
				"1d800250-dfa5-8000-8b54-ff09a28e6f5a",
				"wpid" to "${System.currentTimeMillis()}"
			)
		)
	}
}