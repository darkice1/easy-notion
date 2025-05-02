以下是一个基于`ENotion`内容生成的`readme.md`英文介绍示例：

```markdown
# ENotion

`ENotion` is a Kotlin library for interacting with the Notion API, supporting the conversion of Notion database content into HTML format.

## Features

- **Supported Notion Block Types**:
  - Paragraph (`paragraph`)
  - Headings (`heading_1`, `heading_2`, `heading_3`)
  - List Items (`bulleted_list_item`, `numbered_list_item`)
  - Divider (`divider`)
  - Image (`image`)
  - Table (`table`)

- **HTML Output**:
  - Automatically generates HTML tags (e.g., `<p>`, `<h1>`, `<ul>`, `<table>`, etc.).
  - Supports style customization (e.g., image dimensions, table width).
  - Built-in stylesheet for basic styling.

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

## Configuration

Create a `config.txt` file in the project root directory and add the following content:

```
NOTIONKEY=your_notion_api_key
```

## Dependencies

- **Language**: Kotlin
- **Build Tool**: Maven

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
```