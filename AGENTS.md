# Repository Guidelines（仓库贡献指南）

## 语言与交互（对 Codex 的约定）

- 默认使用中文（简体）与用户沟通与说明：包括日常回复、计划（`update_plan`）、工具调用前的前言（preamble）、修改摘要与结果说明。
- 如用户明确使用其它语言或提出要求，则跟随用户语言作答。
- 代码、标识符、API 名、日志与对外协议保持原样（无需中文化）；仅人机沟通内容默认中文。
- 文档更新：若新增或修改用户可读文档，优先提供中文描述（可同时附英文版本）。
- 若与其它文档指引存在冲突，本条语言约定对“与用户交互的语言选择”具有更高优先级。

## 项目结构与模块组织

- 源码：`src/main/kotlin/easy/notion/`（库代码），核心文件：`ENotion.kt`、`BlockTransformer.kt`。
- 测试/示例：`src/test/kotlin/`（如 `TestNotionTools.kt`），更偏向手动验证，建议逐步迁移为单元测试。
- 构建文件：Gradle 为主（`build.gradle.kts`），Maven 可选（`pom.xml`）。
- 运行环境：JDK 21，Kotlin 2.1.x。

## 构建、测试与本地开发命令

- 构建（Gradle）：`./gradlew build` —— 编译并运行测试，生成 JAR。
- 仅运行测试：`./gradlew test`
- 安装到本地 Maven：`./gradlew publishMavenJavaPublicationToMavenLocal`（或 `./gradlew publiclocal`）。
- 发布到 Sonatype（需凭据）：`./gradlew publishAndCloseSonatype` / `./gradlew release`。
- Maven 等价命令（可选）：`mvn -q package`、`mvn test`、`mvn install`。

## 编码风格与命名约定

- 遵循 Kotlin 官方风格，tab 键缩进，避免通配符导入。
- 包名保持在 `easy.notion`；每个公共类型单独文件，文件名与类型名一致。
- 命名：类用 PascalCase；方法/变量用 camelCase；常量用 UPPER_SNAKE_CASE。
- 倾向使用 `val` 与不可变数据；为公共 API 补充 KDoc 注释。

## 测试指南

- 测试框架：`kotlin-test`（JUnit 平台）。测试放在 `src/test/kotlin`，文件名以 `*Test.kt` 结尾。
- 运行测试：`./gradlew test`。
- 联网示例：可在 IDE 运行 `src/test/kotlin/TestNotionTools.kt`。需在仓库根目录创建 `config.properties`，包含
  `NOTIONKEY=...`（可选 `DATABASEID=...`）。单元测试应避免依赖网络。

## 提交与拉取请求规范

- 使用 Conventional Commits：`feat:`、`fix:`、`chore:`、`docs:`、`refactor:`、`test:` 等。示例：
  `feat(notion): add Markdown to blocks`。
- PR 需包含：清晰描述、关联 issue、必要的前后对比（示例 JSON/HTML）、测试或覆盖性说明、涉及 API 变更时同步更新 README/本文件。
- 提交前请确保本地 `./gradlew build` 通过。

## 安全与配置

- 机密信息禁止入库：`config.properties` 已被 `.gitignore` 忽略，仅在本地保存。
- 发布任务需要签名与凭据（Gradle 属性或环境变量）：`signingKey`、`signingPassword`、`centralUsername`、`centralPassword`。
- 使用 JDK 21（`JAVA_HOME` 指向 21）以匹配工具链。
