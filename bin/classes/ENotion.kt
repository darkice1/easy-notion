import easy.io.EHttpClient
import net.sf.json.JSONObject

class ENotion(val token:String) {
	private val apiurl = "https://api.notion.com/v1/"
	private val header by lazy {
		val map = HashMap<String, String>()
		map["Authorization"] ="Bearer $token"
		map["Notion-Version"] ="2021-08-16"
		map
	}

	private val client by lazy {
		EHttpClient()
	}

	private fun mapToUrl(map:Map<String,Any>):String{
		val buf = StringBuilder()
		map.forEach { (k, v) ->
			buf.append("$k=${java.net.URLEncoder.encode(v.toString(),"utf-8")}&")
		}
		if (buf.isNotEmpty())
		{
			buf.setLength(buf.length-1)
		}

		return buf.toString()
	}

	private fun getObject(funname:String, parm:String): JSONObject {
		val url = "$apiurl$funname/$parm"
		println(url)

		val result = client.get(url,header,null)
//		val result = HttpUtil.post(url, json.toString(), token)
		return JSONObject.fromObject(result)
	}

	fun databasesInfo(id:String): JSONObject {
		return getObject("databases",id)
	}

	fun databasesQuery(id:String): JSONObject {
		return getObject("databases","$id/query")
	}
}