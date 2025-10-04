# ENotion

[中文文档](README_zh-CN.md)

`ENotion` is a Kotlin library for interacting with the Notion API, supporting the conversion of Notion database content into HTML format.

## Features

- **Database Query** – `getDataBase`  
  Query any Notion database with optional *filter* and *sorts*.  
  Each page is returned as a JSON object whose `content` field already contains fully‑rendered HTML for all supported
  block types.

- **Record Insertion** – `insertRecord`  
  Insert a row into a database while automatically converting Kotlin/SQL values to the correct Notion property
  structure, with optional *markdownContent* rendered as rich Notion blocks.

- **Record Update** – `updateRecord`  
  Patch one or more columns of an existing page with the same automatic type handling as *insertRecord*, and
  optionally append converted Markdown content.

- **Markdown Content Support** – `markdownContent`  
  The `insertRecord` and `updateRecord` methods now accept an optional **markdownContent** parameter.
  - Converts full Markdown (headings 1‑3, paragraphs, images, tables, lists, block quotes, horizontal rules, inline *
    *bold** / *italic* / `code`, links, strike‑through) into native Notion blocks on‑the‑fly.
  - Images inside tables are automatically extracted and appended with captions to satisfy Notion API constraints.
  - Supports an optional high‑fidelity converter via Node’s `@tryfabric/martian` when available; otherwise falls back to
    the built‑in Kotlin converter.

- **Data image auto‑upload**  
  Markdown `data:image/...;base64,...` blobs are uploaded automatically via Notion's Direct Upload API and rendered as
  `file_upload` image blocks. Optionally provide a `dataImageUploader` callback to override the behaviour and return an
  `externalUrl` or `fileUploadId`. On failure the content falls back to a paragraph to avoid Notion validation errors.

- **HTML Rendering & Safety**  
  HTML output from `getDataBase` escapes text and attributes and uses `rel="noopener noreferrer"` on links.

- **Workspace Search** – `findNotionDatabase`  
  Locate a database by its exact title and return its ID.

- **Database Creation** – `createNotionDatabase`  
  Programmatically create a brand‑new database with a custom schema under any parent page.

- **Schema Management** – `ensureDatabaseSchema`  
  Compare the remote schema with local column definitions and add any missing properties on‑the‑fly.

- **Timestamp Helper** – `getLatestTimestamp`  
  Quickly obtain the newest ISO date stored in a designated *date* column (useful for incremental sync).

- **Miscellaneous Utilities**  
  Helpers for reading a database’s `properties` object, mapping SQL types to Notion types, and extracting primitive
  values from Notion properties.

## Usage

### Initialization

Add `ENotion` to your project and initialize it with your Notion API key:

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

### Searching for a database

```kotlin
val dbId = notion.findNotionDatabase("My Tasks")
if (dbId == null) println("Database not found") else println("ID = $dbId")
```

### Creating a database

```kotlin
val newDbId = notion.createNotionDatabase(
	pageId = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",          // parent page
	databaseName = "Log Entries",
	columns = mapOf("Level" to "select", "Message" to "rich_text"),
	timeField = "Created",
	primaryKey = "ID",
)
```

### Inserting and updating records

```kotlin
// insert (with Markdown body)
notion.insertRecord(
  databaseId = newDbId!!,
  markdownContent = """
        # Welcome
        This record was **created** via *ENotion*.
        
        ---
        
        | Picture | Description |
        |:------:|-------------|
        | ![](https://example.com/kitten.png) | Cute kitten |
        """.trimIndent(),
  "ID" to "42",
  "Level" to "INFO",
  "Message" to "Application started",
  "Created" to "2025-05-25 10:00:00",
)

// update (patch fields + append more Markdown)
notion.updateRecord(
  databaseId = newDbId,
  pageId = "pppppppppppppppppppppppppppppppp",
  markdownContent = "- **现价**：￥208",
  "Level" to "ERROR",
  "Message" to "Oops, something went wrong",
)

// Optional: data image auto‑upload (custom uploader example)
val notionWithUploader = ENotion(
	apikey = props.getProperty("NOTIONKEY"),
	dataImageUploader = { mime, bytes, suggestedName ->
		// Upload bytes to your hosting and return an https URL. Example only:
		val url = myUpload(bytes, suggestedName ?: "image", mime) // implement yourself
		if (url != null) ENotion.DataImageUploadResult(externalUrl = url) else null
	}
)
```

### Ensuring schema matches local columns

```kotlin
val ok = notion.ensureDatabaseSchema(
	databaseId = newDbId,
	localColumns = mapOf("ID" to "VARCHAR", "Level" to "VARCHAR", "Detail" to "TEXT"),
	timeField = "Created",
	primaryKey = "ID",
)
```

### Output Example

Convert Notion database content to HTML:

```html
<style>
    hr { border: none; border-top: 1px solid #E1E3E5; margin: 16px 0; }
    table { border-collapse: collapse; border: 1px solid #E1E3E5; }
    th, td { border: 1px solid #E1E3E5; padding: 8px; text-align: left; }
    thead th { background-color: #f2f2f2; }
    tbody th { background-color: #f2f2f2; }
    img { max-width: 600px; }
    a { text-decoration: underline; }
    pre { background: #f5f5f5; padding: 10px; border-radius: 3px; overflow: auto; }
    pre code { background: transparent; padding: 0; }
    code { background: #f5f5f5; padding: 2px 4px; border-radius: 3px; font-family: monospace; }
</style>
<p>Sample paragraph content</p>
<h1>Sample Heading</h1>
<ul>
    <li>Sample list item</li>
</ul>
<table style="width:600px;">
    <thead>
    <tr>
        <th>Header 1</th>
        <th>Header 2</th>
    </tr>
    </thead>
    <tbody>
    <tr>
        <td>Cell 1</td>
        <td>Cell 2</td>
    </tr>
    </tbody>
</table>
```

The library automatically prepends a lightweight default stylesheet (visible in the snippet above) so the HTML is ready
for direct embedding.

> **Note**  
> When you supply *markdownContent* to *insertRecord* / *updateRecord*, ENotion converts the Markdown to Notion blocks
> first, so the HTML produced by `getDataBase` will precisely match your original Markdown—including images, tables, and
> dividers.

## Configuration

Create a `config.properties` file in the project root directory and add the following content:

```
NOTIONKEY=your_notion_api_key
# Optional for examples
# DATABASEID=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Optional: for high‑fidelity Markdown conversion, install Node and Martian globally:

```
npm i -g @tryfabric/martian
```

## Dependencies

- **Language**: Kotlin
- **HTTP**: OkHttp 4.x
- **JSON**: org.json
- **Build Tool**: Gradle (Maven optional)

## Development and Testing

- Build: `./gradlew build`
- Run tests only: `./gradlew test`
- Publish to local Maven: `./gradlew publishMavenJavaPublicationToMavenLocal`

For a quick online example (manual, not a unit test), run `src/test/kotlin/TestNotionTools.kt` in your IDE with a valid
`config.properties` in the project root.

## Notes

- Ensure that the `config.properties` file is not committed to version control (already configured in `.gitignore`).
- Make sure the Notion API key and database ID are valid before use.

## License

This project is licensed under the MIT License.
