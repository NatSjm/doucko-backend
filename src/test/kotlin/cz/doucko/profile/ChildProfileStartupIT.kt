package cz.doucko.profile

import cz.doucko.DouckoBackendApplication
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * child-profile first start and restarts with the whole application against one Postgres 16 server:
 * each case gets its own database, and "restart" means a new application run on the same database.
 * Only `application.yaml` is loaded, so the test `application.properties` (which sets a display name)
 * cannot hide an unset variable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class ChildProfileStartupIT {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
    private val databases = AtomicInteger()
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = postgres.stop()

    private fun freshDatabase(): String {
        val name = "profile_it_${databases.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().execute("create database $name")
        }
        return postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$name")
    }

    /** Starts the backend on [url]; [displayName] null leaves the variable unset. */
    private fun start(
        url: String,
        displayName: String?,
    ): ConfigurableApplicationContext {
        val args =
            listOfNotNull(
                "--spring.config.location=classpath:/application.yaml",
                "--server.port=0",
                "--spring.datasource.url=$url",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--ADMIN_PASSWORD=Kocka-na-plote-42",
                displayName?.let { "--DOUCKO_CHILD_DISPLAY_NAME=$it" },
            )
        return SpringApplicationBuilder(DouckoBackendApplication::class.java).run(*args.toTypedArray())
    }

    private fun rows(url: String): List<Triple<String, String?, String>> =
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery("select display_name, secondary_task_locale, answer_mode from child_profile").use {
                buildList { while (it.next()) add(Triple(it.getString(1), it.getString(2), it.getString(3))) }
            }
        }

    private fun ConfigurableApplicationContext.settings(
        method: String = "GET",
        body: String? = null,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getProperty("local.server.port")}/api/child/profile/settings"))
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun Throwable.allText(): String = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }

    // @trace FR-43
    // @trace NFR-3
    @Test
    fun `first start stores the trimmed name, restarts keep it and its settings whatever the variable says`(output: CapturedOutput) {
        val url = freshDatabase()
        start(url, "  $FICTIONAL_NAME  ").use { app ->
            app.settings().body() shouldBe """{"secondaryTaskLocale":null,"answerMode":"DIRECT"}"""
            app.settings("PATCH", """{"secondaryTaskLocale":"uk"}""").statusCode() shouldBe 200
            app.settings("PATCH", """{"secondaryTaskLocale":"ru"}""").statusCode() shouldBe 400
        }
        rows(url) shouldBe listOf(Triple(FICTIONAL_NAME, "uk", "DIRECT"))
        for (variable in listOf("Jiné jméno", null, "", "y".repeat(200), "a\nb")) {
            start(url, variable).use { app ->
                app.settings().body() shouldBe """{"secondaryTaskLocale":"uk","answerMode":"DIRECT"}"""
            }
        }
        rows(url) shouldBe listOf(Triple(FICTIONAL_NAME, "uk", "DIRECT"))
        output.all shouldNotContain FICTIONAL_NAME
    }

    // @trace FR-43
    // @trace NFR-3
    @Test
    fun `a refused name fails the startup, names the variable, never the value, and stores nothing`(output: CapturedOutput) {
        val run = "x".repeat(50)
        for (name in listOf(null, "   ", "Hrd\ninka", "$FICTIONAL_NAME$run")) {
            val url = freshDatabase()
            val failure = shouldThrowAny { start(url, name).close() }.allText()
            failure shouldContain "DOUCKO_CHILD_DISPLAY_NAME"
            failure shouldNotContain FICTIONAL_NAME
            failure shouldNotContain run
            rows(url) shouldBe emptyList()
        }
        output.all shouldNotContain FICTIONAL_NAME
        output.all shouldNotContain run
    }

    // @trace FR-43
    @Test
    fun `a 50 code point name is accepted on a fresh database`() {
        val url = freshDatabase()
        val name = "ž".repeat(25) + String(Character.toChars(0x1D11E)).repeat(25)
        start(url, name).close()
        rows(url).map { it.first } shouldBe listOf(name)
    }

    // @trace FR-43
    @Test
    fun `the schema holds at most one profile`() {
        val url = freshDatabase()
        start(url, FICTIONAL_NAME).close()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            shouldThrowAny {
                connection.createStatement().execute("insert into child_profile (id, display_name) values (2, 'Druhy')")
            }
        }
        rows(url).size shouldBe 1
    }
}
