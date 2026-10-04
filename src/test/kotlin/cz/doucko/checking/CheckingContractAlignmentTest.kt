package cz.doucko.checking

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The checker's own enums mirror the pinned contract (`contract/api/openapi.yaml`), so slices 5 and 7 can map by name. */
class CheckingContractAlignmentTest {
    private val schemas: Map<*, *> =
        Yaml()
            .load<Map<String, Any?>>(File("contract/api/openapi.yaml").readText())
            .let { (it["components"] as Map<*, *>)["schemas"] as Map<*, *> }

    private fun enumOf(name: String): List<String> = ((schemas[name] as Map<*, *>)["enum"] as List<*>).map { it.toString() }

    // @trace FR-14
    @Test
    fun `AnswerType and Verdict equal the contract enums`() {
        AnswerType.entries.map { it.name } shouldContainExactly enumOf("AnswerType")
        Verdict.entries.map { it.name } shouldContainExactly enumOf("Verdict")
    }
}
