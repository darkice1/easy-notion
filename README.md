# Notion JAVA/KOTLIN SDK
快速接入notion 并提供一些方便接口

# 升级信息
1. 基础接口

# 初始化
```kotlin
val notion = ENotion("token")
```
# maven
```xml
<!-- https://mvnrepository.com/artifact/com.github.darkice1/easy-biance -->
<dependency>
    <groupId>com.github.darkice1</groupId>
    <artifactId>easy-biance</artifactId>
    <version>1.0.5</version>
</dependency>
```

# notion API地址
- https://developers.notion.com/

# 获取数据库基础信息
- https://developers.notion.com/reference/retrieve-a-database
- notion.retrieveDatabases()

# 获取数据库具体数据
- https://developers.notion.com/reference/post-database-query
- notion.queryDatabases()

# 获取页面基础信息
- https://developers.notion.com/reference/retrieve-a-page
- notion.retrievePages()
