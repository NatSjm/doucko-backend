package cz.doucko.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * NFR-12: the server interfaces are generated from the pinned contract (`contract/api/openapi.yaml`,
 * profile `pinned-contract`), never hand-written. Controllers implement them, so drift is a compile error.
 */
class ContractGenerationTest {
    private val contract = File("contract/api/openapi.yaml")

    // @trace NFR-12
    @Test
    fun `the contract is pinned as the contract submodule`() {
        contract.isFile shouldBe true
    }

    // @trace NFR-12
    @Test
    fun `every contract tag has its generated Api interface`() {
        val spec: Map<String, Any?> = Yaml().load(contract.readText())
        val tags = (spec["tags"] as List<*>).map { (it as Map<*, *>)["name"].toString() }
        tags.shouldNotBeEmpty()
        val expected =
            tags.map { tag ->
                "cz.doucko.api." + tag.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Api"
            }
        expected.filterNot(::loads).shouldBeEmpty()
    }

    // @trace NFR-12
    @Test
    fun `generated interfaces are not part of the hand-written sources`() {
        File("src/main/kotlin/cz/doucko/api")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("Api.kt") }
            .toList()
            .shouldBeEmpty()
    }

    private fun loads(name: String) = runCatching { Class.forName(name) }.isSuccess
}
