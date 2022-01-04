import easy.io.EHttpClient
import net.sf.json.JSONArray
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

	private fun postObject(funname:String, urlparm:String="",postjson:JSONObject?=null): JSONObject {
		val url = "$apiurl$funname/$urlparm"
		println("postObject: $url")
		val post = if (postjson!=null)
		{
			val map = HashMap<String,String>()
			map[""] = postjson.toString()
			map
		}
		else
		{
			null
		}

		println(postjson)


		val result = client.postToString(url,post,header)
//		val result = HttpUtil.post(url, json.toString(), token)
		return JSONObject.fromObject(result)
	}

	private fun getObject(funname:String, parm:String): JSONObject {
		val url = "$apiurl$funname/$parm"
//		println(url)

		val result = client.get(url,header,null)
//		val result = HttpUtil.post(url, json.toString(), token)
		return JSONObject.fromObject(result)
	}

	/**
	 * https://developers.notion.com/reference/retrieve-a-database
	 */
	fun retrieveDatabases(id:String): JSONObject {
		return getObject("databases",id)
	}

	/**
	 * https://developers.notion.com/reference/retrieve-a-page
	 */
	fun retrievePages(id:String): JSONObject {
		return getObject("pages",id)
	}

	fun createPages(parent:JSONObject,properties:JSONObject,children: JSONArray?=null,icon:JSONObject?=null,cover:JSONObject?=null): JSONObject {
		val json = JSONObject()
		json["parent"] = parent
		json["properties"] = properties
		if (children != null)
		{
			json["children"] = children
		}
		if (icon != null)
		{
			json["icon"] = icon
		}
		if (cover != null)
		{
			json["cover"] = cover
		}
		return postObject("pages", postjson = json)
	}

	/**
	 * https://developers.notion.com/reference/post-database-query
	 */
	fun queryDatabases(id:String, postjson:JSONObject?=null): JSONObject {
		return postObject("databases","$id/query",postjson)
	}
}