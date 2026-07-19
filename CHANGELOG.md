# Changelog

## 2026-07-19

- chore(build): upgrade the project toolchain to JDK 25, Kotlin 2.4.10, Gradle 9.5.0, and Nexus Publish Plugin 2.0.0.
- docs: document the JDK 25 build requirement and JVM 25 bytecode target in both READMEs.

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
