@file:Suppress("VulnerableLibrariesLocal")

import org.jetbrains.dokka.gradle.DokkaTask

plugins {
	kotlin("jvm") version "2.1.20"
	`java-library`
	id("org.jetbrains.dokka") version "2.0.0"
	`maven-publish`
	signing                               // Gradle 自带插件
}

group = "com.github.darkice1"
version = "0.0.1"

val projectName = "easy-notion"
val projectDesc = "Neo easy Notion SDK."
val repoName = "easy-notion"

// -------- Java toolchain & JAR 附件 --------
java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
	withSourcesJar()
	withJavadocJar()
}

// -------- 仓库 --------
repositories {
	mavenCentral()
	mavenLocal()
}

// -------- 依赖 --------
dependencies {
	implementation("com.github.darkice1:easy:1.0.79")
	implementation("com.squareup.okhttp3:okhttp:4.12.0")

	implementation(kotlin("stdlib"))
	testImplementation(kotlin("test"))
}

// -------- Kotlin 编译选项 --------
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}

// -------- Javadoc 选项 --------
tasks.withType<Javadoc>().configureEach {
	(options as StandardJavadocDocletOptions).apply {
		encoding = "UTF-8"
		docEncoding = "UTF-8"
		addStringOption("Xdoclint:none", "-quiet")
	}
}

// -------- Dokka --------
tasks.withType<DokkaTask>().configureEach {
	outputDirectory.set(layout.buildDirectory.dir("dokka"))
}

// -------- 发布到 OSSRH --------
publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
			artifactId = projectName

			pom {
				name.set(projectName)
				description.set(projectDesc)
				url.set("https://github.com/darkice1/$repoName")

				licenses {
					license {
						name.set("MIT License")
						url.set("https://opensource.org/licenses/MIT")
					}
				}
				developers {
					developer {
						id.set("neo")
						name.set("neo")
						email.set("starneo@gmail.com")
					}
				}
				scm {
					connection.set("scm:git:https://github.com/darkice1/$repoName.git")
					url.set("https://github.com/darkice1/$repoName")
				}
			}
		}
	}

	repositories {
		maven {
			name = "ossrh"
			url = uri(
				if (version.toString().endsWith("SNAPSHOT"))
					"https://oss.sonatype.org/content/repositories/snapshots/"
				else
					"https://oss.sonatype.org/service/local/staging/deploy/maven2/"
			)
			credentials {
				username = providers.gradleProperty("ossrhUsername")
					.orElse(System.getenv("OSSRH_USERNAME")).getOrNull()
				password = providers.gradleProperty("ossrhPassword")
					.orElse(System.getenv("OSSRH_PASSWORD")).getOrNull()
			}
		}
	}
}

// -------- GPG 签名 --------
signing {
	// 1) CI 推荐：gradle.properties / 环境变量注入 ASCII 私钥
	val inMemKey: String? = providers.gradleProperty("signingKey").orNull
	val inMemPwd: String? = providers.gradleProperty("signingPassword").orNull

	when {
		inMemKey != null && inMemPwd != null -> useInMemoryPgpKeys(inMemKey, inMemPwd)
		// 2) 本地直接调用 gpg 命令
		else -> useGpgCmd()
	}

	sign(publishing.publications["mavenJava"])
}