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
			databaseid, markdownContent = "# 北禾新款冷月轻量版超短节碳素便携28调短节溪流竿评测\n" +
					"\n" +
					"> 轻量冠军版｜便携设计｜超高好评率\n" +
					"\n" +
					"![主图](https://img14.360buyimg.com/pop/jfs/t1/287362/38/6326/145175/68230746Fb04eaecb/39a05ca78bcea775.jpg)\n" +
					"\n" +
					"## 一、产品简介\n" +
					"\n" +
					"**产品名称**：北禾新款冷月轻量版超短节碳素便携28调短节溪流竿手竿钓鱼竿垂钓渔具  \n" +
					"**规格**：3m - 冷月轻量冠军版 雅丹灰  \n" +
					"**配件**：附赠原厂竿稍1节及2节  \n" +
					"**售价**：￥208 元\n" +
					"\n" +
					"## 二、外观与细节展示\n" +
					"\n" +
					"产品配备了丰富的实拍图片，可全方位了解产品细节：\n" +
					"\n" +
					"| 实拍图 | 描述 |\n" +
					"|:---:|:---|\n" +
					"| ![](https://img14.360buyimg.com/pop/jfs/t1/287362/38/6326/145175/68230746Fb04eaecb/39a05ca78bcea775.jpg) | 产品主图 |\n" +
					"| ![](https://img14.360buyimg.com/pop/jfs/t1/291682/7/5280/124498/68230744F516b072a/5fa0481f5fb7e354.jpg) | 碳素材质细节 |\n" +
					"| ![](https://img14.360buyimg.com/pop/jfs/t1/300045/36/2933/45605/68230743F149460b6/66bb6649686225ed.jpg) | 手柄及LOGO |\n" +
					"| ![](https://img14.360buyimg.com/pop/jfs/t1/309403/31/480/78794/68230742Fc47310f2/5896e4689ba543b8.jpg) | 竿体展示 |\n" +
					"| ![](https://img14.360buyimg.com/pop/jfs/t1/299636/22/6755/57579/68230741F798a6eda/656fcd9676fe0db5.jpg) | 配件实拍 |\n" +
					"\n" +
					"## 三、核心亮点\n" +
					"\n" +
					"- **超短节设计**：便于携带和收纳，户外垂钓无需担心空间问题。\n" +
					"- **高强度碳素材质**：轻量且高弹性，持久耐用。\n" +
					"- **28调硬度**：兼顾韧性与操控，适合多种水域环境。\n" +
					"- **丰富配件**：附赠原厂竿稍1节及2节，使用更安心。\n" +
					"- **官方承诺**：7天无理由退货，无后顾之忧。\n" +
					"\n" +
					"## 四、用户口碑\n" +
					"\n" +
					"- **累计评价**：200+\n" +
					"- **好评数量**：45\n" +
					"- **好评率**：94%\n" +
					"- **典型好评关键词**：便携、手感好、做工扎实、性价比高\n" +
					"\n" +
					"> “这款溪流竿超乎预期，轻便又结实，钓小鱼体验极佳。” —— 真实用户反馈\n" +
					"\n" +
					"## 五、价格与购买渠道\n" +
					"\n" +
					"- **现价**：￥208\n" +
					"- **佣金比例**：20%\n" +
					"- **售后服务**：支持7天无理由退货\n" +
					"\n" +
					"---\n" +
					"\n" +
					"## 总结\n" +
					"\n" +
					"**北禾冷月轻量冠军版**凭借便携设计、优良用料和超高性价比，成为当前短节溪流竿市场的优选之一。对于注重装备体验和便携性的钓鱼爱好者来说，是值得入手的精品。\n" +
					"\n" +
					"---\n" +
					"\n" +
					"**免责声明**：本文仅做商品信息分享，不构成购买建议。实际购买请以官方渠道为准。\n" +
					"\n" +
					"---\n" +
					"\n" +
					"如需获取更多实拍图和用户评价，欢迎点击查看原商品页面。\n",
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
	}
}