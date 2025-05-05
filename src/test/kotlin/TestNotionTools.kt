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
		println(n.getDataBase(databaseid))
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