package cz.doucko.platform

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Static checks of `compose.yaml` (admin-access "Local network only", NFR-2; NFR-10).
 * The running-stack check (`docker compose ps`) is part of the slice smoke and G5.
 */
class ComposeConfigurationTest {
    private val compose: Map<String, Any?> = Yaml().load(File("compose.yaml").readText())

    @Suppress("UNCHECKED_CAST")
    private val services = compose["services"] as Map<String, Map<String, Any?>>

    // @trace NFR-2
    @Test
    fun `every published port binds the configurable address, loopback by default`() {
        val published = services.flatMap { (name, svc) -> (svc["ports"] as List<*>? ?: emptyList<Any>()).map { name to it.toString() } }
        published.map { it.first } shouldContain "frontend"
        val bindPrefix = "\${DOUCKO_BIND_ADDRESS:-127.0.0.1}:"
        for ((name, port) in published) {
            check(port.startsWith(bindPrefix) && Regex("""\d+:\d+""").matches(port.removePrefix(bindPrefix))) {
                "$name publishes $port; expected $bindPrefix<host port>:<container port>"
            }
        }
    }

    // @trace NFR-2
    @Test
    fun `the backend port is not published, the browser reaches the API only through the frontend's nginx`() {
        val backend = services["backend"].shouldNotBeNull()
        backend["ports"] shouldBe null
    }

    // @trace NFR-2
    @Test
    fun `the database port is not published`() {
        val db = services["db"].shouldNotBeNull()
        db["ports"] shouldBe null
    }

    // @trace NFR-10
    @Test
    fun `postgres data lives on the named volume doucko-pgdata`() {
        val db = services["db"].shouldNotBeNull()
        db["image"].toString().startsWith("postgres:16") shouldBe true
        (db["volumes"] as List<*>).map { it.toString() } shouldContain "doucko-pgdata:/var/lib/postgresql/data"
        (compose["volumes"] as Map<*, *>).keys shouldContain "doucko-pgdata"
    }

    // @trace NFR-17
    @Test
    fun `uploaded files live on the named volume doucko-storage, outside every repository`() {
        val backend = services["backend"].shouldNotBeNull()
        (backend["volumes"] as List<*>).map { it.toString() } shouldContain "doucko-storage:/var/lib/doucko/storage"
        (backend["environment"] as Map<*, *>)["DOUCKO_STORAGE_DIR"] shouldBe "/var/lib/doucko/storage"
        (compose["volumes"] as Map<*, *>).keys shouldContain "doucko-storage"
    }
}
