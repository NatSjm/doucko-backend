package cz.doucko.profile

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * child-profile "The contract offers no way to create, delete or switch profiles" and the contract half
 * of "Admin API cannot change the profile", checked on the pinned contract.
 */
class ProfileContractTest {
    @Suppress("UNCHECKED_CAST")
    private val paths =
        (Yaml().load<Map<String, Any?>>(File("contract/api/openapi.yaml").readText())["paths"] as Map<String, Map<String, Any?>>)

    private fun operations(filter: (String) -> Boolean) =
        paths.filterKeys(filter).flatMap { (path, ops) ->
            ops.keys.filter { it in setOf("get", "post", "put", "patch", "delete") }.map { "${it.uppercase()} $path" }
        }

    // @trace FR-43
    @Test
    fun `the only profile operations are the child settings get and patch`() {
        operations { "profile" in it } shouldContainExactlyInAnyOrder
            listOf("GET /api/child/profile/settings", "PATCH /api/child/profile/settings")
        operations { it.startsWith("/api/admin/") && "profile" in it }.shouldBeEmpty()
    }
}
