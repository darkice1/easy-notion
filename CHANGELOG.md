# Changelog

## 2026-04-20

- feat: restore backward-compatible `insertRecord` and `updateRecord` overloads while keeping the Markdown-aware
  signatures.
- fix: normalize latest timestamps from `date`, `rich_text`, and `title` properties through one parser and format them
  as `yyyy-MM-dd HH:mm:ss`.
- test: add MockWebServer coverage for legacy overloads and real timestamp parsing cases in `ENotion`.

## 2025-11-25

- fix: normalize unsupported Markdown code languages to `plain text` before sending to Notion to prevent 400 validation
  errors.

## 2025-10-28

- feat: render Notion `video` blocks into HTML with a default 640x360 size, supporting local files via `<video>` and YouTube/Vimeo embeds via `<iframe>`, with safe fallbacks for other providers.
