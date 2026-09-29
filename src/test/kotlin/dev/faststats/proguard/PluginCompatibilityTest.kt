package dev.faststats.proguard

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

class PluginCompatibilityTest {

    companion object {
        private val repo = File(System.getProperty("functionalTest.repo"))
        private val pluginVersion: String = System.getProperty("functionalTest.pluginVersion")

        @JvmStatic
        fun gradleVersions(): List<String> = listOf(
            "8.0.2", // Kotlin 1.8.10
            "8.10.2", // Kotlin 1.9.24
            "8.14.3", // Kotlin 2.0.21
            System.getProperty("functionalTest.currentGradleVersion"),
        )

        private const val MAPPING = """com.example.Foo -> a.a:
    int bar -> a
    void baz() -> b
com.example.Qux -> a.b:
    java.lang.String name -> a
"""
    }

    private class Request(val authorization: String?, val body: String)

    @TempDir
    lateinit var projectDir: File

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Request>()

    private val endpoint get() = "http://127.0.0.1:${server.address.port}/upload"

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(Request(exchange.requestHeaders.getFirst("authorization"), body))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    fun uploadsMappingsFromKotlinDsl(gradleVersion: String) {
        writeSettings("settings.gradle.kts")
        projectDir.resolve("mapping.txt").writeText(MAPPING)
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import dev.faststats.proguard.UploadProguardMappingsTask

            plugins {
                id("dev.faststats.proguard-mappings-upload") version "$pluginVersion"
            }

            version = "1.2.3"

            mappingsUpload {
                authToken.set("test-token")
                endpoint.set("$endpoint")
                mappingFiles.from(layout.projectDirectory.file("mapping.txt"))
            }

            tasks.named<UploadProguardMappingsTask>("uploadProguardMappings") {
                doFirst { logger.lifecycle("Uploading to " + endpoint.get()) }
            }
            """.trimIndent(),
        )

        val result = run(gradleVersion, "uploadProguardMappings")

        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadProguardMappings")?.outcome)
        assertUploaded(buildId = "1.2.3")
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    fun uploadsMappingsFromGroovyDsl(gradleVersion: String) {
        writeSettings("settings.gradle")
        projectDir.resolve("mapping.txt").writeText(MAPPING)
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'dev.faststats.proguard-mappings-upload' version '$pluginVersion'
            }

            mappingsUpload {
                authToken = 'test-token'
                endpoint = '$endpoint'
                buildId = 'groovy-build'
                mappingFiles.from(layout.projectDirectory.file('mapping.txt'))
            }
            """.trimIndent(),
        )

        val result = run(gradleVersion, "uploadProguardMappings")

        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadProguardMappings")?.outcome)
        assertUploaded(buildId = "groovy-build")
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    fun writesBuildIdIntoJar(gradleVersion: String) {
        writeSettings("settings.gradle.kts")
        projectDir.resolve("src/main/resources/META-INF/faststats.properties").apply {
            parentFile.mkdirs()
            writeText("buildId=stale\nother=value\n")
        }
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("dev.faststats.proguard-mappings-upload") version "$pluginVersion"
            }

            version = "1.2.3"
            """.trimIndent(),
        )

        val result = run(gradleVersion, "jar")

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)
        val properties = ZipFile(projectDir.resolve("build/libs/consumer-1.2.3.jar")).use { jar ->
            jar.getInputStream(jar.getEntry("META-INF/faststats.properties")).readBytes().toString(Charsets.UTF_8)
        }
        assertEquals("other=value\nbuildId=1.2.3\n", properties)
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    fun failsWhenMappingFileIsMissing(gradleVersion: String) {
        writeSettings("settings.gradle.kts")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("dev.faststats.proguard-mappings-upload") version "$pluginVersion"
            }

            mappingsUpload {
                authToken.set("test-token")
                endpoint.set("$endpoint")
                mappingFiles.from(layout.buildDirectory.file("proguard/mapping.txt"))
            }
            """.trimIndent(),
        )

        val result = runner(gradleVersion, "uploadProguardMappings").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":uploadProguardMappings")?.outcome)
        assertTrue("No ProGuard mapping files found." in result.output, result.output)
        assertTrue(requests.isEmpty())
    }

    private fun writeSettings(fileName: String) {
        val repoUri = repo.toURI().toString()
        val content = if (fileName.endsWith(".kts")) {
            """
            pluginManagement {
                repositories {
                    maven("$repoUri")
                    mavenCentral()
                }
            }

            rootProject.name = "consumer"
            """
        } else {
            """
            pluginManagement {
                repositories {
                    maven { url = uri('$repoUri') }
                    mavenCentral()
                }
            }

            rootProject.name = 'consumer'
            """
        }
        projectDir.resolve(fileName).writeText(content.trimIndent())
    }

    private fun runner(gradleVersion: String, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(projectDir)
        .withArguments(*arguments, "--stacktrace")

    private fun run(gradleVersion: String, vararg arguments: String): BuildResult =
        runner(gradleVersion, *arguments).build()

    private fun assertUploaded(buildId: String) {
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("Bearer test-token", request.authorization)

        val payload = JsonParser.parseString(request.body).asJsonObject
        assertEquals("proguard", payload["type"].asString)
        assertEquals(buildId, payload["buildId"].asString)

        val files = payload["files"].asJsonArray
        assertEquals(1, files.size())
        val file = files[0].asJsonObject
        assertEquals("mapping/1.txt", file["fileName"].asString)
        assertEquals(MAPPING, file["content"].asString)
    }
}
