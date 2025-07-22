# ENotion

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
import easy.config.Config
import easy.notion.ENotion

fun main() {
	val notion = ENotion(Config.getProperty("NOTIONKEY").toString())
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

Create a `config.txt` file in the project root directory and add the following content:

```
NOTIONKEY=your_notion_api_key
```

## Dependencies

- **Language**: Kotlin
- **HTTP**: OkHttp 4.x
- **JSON**: org.json
- **Build Tool**: Maven (or Gradle)

## Development and Testing

### Testing Tool

Use `TestNotionTools` for testing:

```kotlin
object TestNotionTools {
	@JvmStatic
	fun main(args: Array<String>) {
		val n = ENotion(Config.getProperty("NOTIONKEY").toString())
		print(n.getDataBase("1d800250dfa5805e8f45dfcd67d37e60"))
	}
}
```

## Notes

- Ensure that the `config.txt` file is not committed to version control (already configured in `.gitignore`).
- Make sure the Notion API key and database ID are valid before use.

## License

This project is licensed under the MIT License.