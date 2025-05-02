import easy.config.Config
import easy.notion.ENotion

object TestNotionTools {
	@JvmStatic
	fun main(args: Array<String>) {
		val n = ENotion(Config.getProperty("NOTIONKEY").toString())
		print(n.getDataBase(Config.getProperty("DATABASEID").toString()))
	}
}