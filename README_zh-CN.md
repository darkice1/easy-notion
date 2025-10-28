# ENotion（中文）

`ENotion` 是一个用于与 Notion API 交互的 Kotlin 库，支持将 Notion 数据库页面内容转换为可直接嵌入的 HTML。

- English version: README.md

## 特性

- 数据库查询 – `getDataBase`  
  可选传入 filter 与 sorts，返回的每条记录都包含已渲染好的 `content` HTML。

- 插入记录 – `insertRecord`  
  自动根据数据库 schema 将 Kotlin/SQL 值转换为正确的 Notion 属性结构，可选传入 `markdownContent` 自动转为 Notion 块。

- 更新记录 – `updateRecord`  
  与插入相同的自动类型处理，支持附加 `markdownContent`。

- Markdown 内容支持 – `markdownContent`
	- 支持标题（1‑3 级）、段落、图片、表格、列表、引用、分隔线、内联加粗/斜体/代码/链接/删除线等，转换为原生 Notion 块。
	- 表格中的图片会被提取并作为图片块附加，且保留标题说明。
	- 若本机安装了 Node 的 `@tryfabric/martian`，优先使用其进行高保真转换；否则回退到内置转换器。

- data 图片自动上传
	- 默认使用 Notion 官方的 Direct Upload 流程（`/v1/file_uploads` + `/send`），data URI 会自动转成 `file_upload`
	  图片块，无需额外配置。
	- 可选传入 `dataImageUploader` 自定义上传逻辑：返回 `DataImageUploadResult(fileUploadId = ...)` 或
	  `DataImageUploadResult(externalUrl = ...)`。
	- 若自定义回调返回 `null` 或上传失败，将优雅降级为段落文本，避免 Notion 400。

- HTML 渲染与安全  
  `getDataBase` 输出的 HTML 对文本与属性进行安全转义，链接使用 `rel="noopener noreferrer"`。

- 视频块渲染  
  `getDataBase` 支持渲染 Notion `video` 块：默认宽高为 640x360，本地文件以 `<video>` 播放，YouTube/Vimeo
  外链自动转成可嵌入的 `<iframe>`，其它平台降级为带外链的文本。

- 工作区搜索 – `findNotionDatabase`  
  按标题精确查找数据库并返回 ID。

- 创建数据库 – `createNotionDatabase`  
  在任意父页面下创建新数据库并指定列类型。

- Schema 同步 – `ensureDatabaseSchema`  
  对比远端 schema 与本地列定义，自动补充缺失列。

- 时间戳工具 – `getLatestTimestamp`  
  快速获取某日期列的最新时间（标准 `yyyy-MM-dd HH:mm:ss`）。

## 快速开始

### 初始化

```kotlin
import easy.notion.ENotion
import java.io.FileInputStream
import java.util.Properties

fun main() {
    val props = Properties().apply { load(FileInputStream("config.properties")) }
    val notion = ENotion(props.getProperty("NOTIONKEY"))
    println(notion.getDataBase("your_database_id"))
}
```

### 插入与更新

```kotlin
// 插入（包含 Markdown 正文）
notion.insertRecord(
  databaseId = "your_database_id",
  markdownContent = """
    # 欢迎
    这条记录由 **ENotion** 创建。
  """.trimIndent(),
  "ID" to "42",
  "Level" to "INFO",
)

// 更新（局部字段 + 追加 Markdown）
notion.updateRecord(
  databaseId = "your_database_id",
  pageId = "your_page_id",
  markdownContent = "- **现价**：￥208",
  "Level" to "ERROR",
)

// 可选：data 图片自动上传（示例：自定义上传回调）
val notionWithUploader = ENotion(
	apikey = props.getProperty("NOTIONKEY"),
	dataImageUploader = { mime, bytes, suggestedName ->
		// 将 bytes 上传到你的托管（需返回 https 外链）。下面仅为示意：
		val url = myUpload(bytes, suggestedName ?: "image", mime) // 自行实现
		if (url != null) ENotion.DataImageUploadResult(externalUrl = url) else null
	}
)
```

## 配置

在项目根目录创建 `config.properties`：

```
NOTIONKEY=your_notion_api_key
# 可选：示例运行
# DATABASEID=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

可选：若需要更高保真的 Markdown 转换，安装 Node 与 Martian：

```
npm i -g @tryfabric/martian
```

## 依赖

- 语言：Kotlin
- HTTP：OkHttp 4.x
- JSON：org.json
- 构建工具：Gradle（可选 Maven）
- 运行环境：JDK 21，Kotlin 2.1.x

## 开发与测试

- 构建：`./gradlew build`
- 仅测试：`./gradlew test`
- 发布到本地 Maven：`./gradlew publishMavenJavaPublicationToMavenLocal`

在线示例（手动）：在 IDE 中运行 `src/test/kotlin/TestNotionTools.kt`，并确保仓库根目录存在 `config.properties`。

## 输出示例

`getDataBase` 会在内容前插入轻量默认样式，输出的 HTML 可直接嵌入（见英文 README 的示例片段）。

## 注意

- `config.properties` 已在 `.gitignore` 中忽略，请勿提交。
- 修改公共 API 或核心方法时，请同步更新本文件与英文 README（README.md）。

## 许可

MIT License
