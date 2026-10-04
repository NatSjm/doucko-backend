package cz.doucko.admin

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * admin-access "No content-changing child operation exists" (NFR-1): every data-changing operation under
 * `/api/child/` in the pinned contract records the child's own practice or settings. A new child write
 * fails this test until it is reviewed against the spec and added here.
 */
class ChildOperationsContractTest {
    @Suppress("UNCHECKED_CAST")
    private val paths =
        (Yaml().load<Map<String, Any?>>(File("contract/api/openapi.yaml").readText())["paths"] as Map<String, Map<String, Any?>>)

    // @trace NFR-1
    @Test
    fun `child writes are only attempts, hints, reports, replay, rank-up acknowledgement and profile settings`() {
        val writes =
            paths
                .filterKeys { it.startsWith("/api/child/") }
                .flatMap { (path, operations) ->
                    operations.keys.filter { it in setOf("post", "put", "patch", "delete") }.map { "${it.uppercase()} $path" }
                }
        writes shouldContainExactlyInAnyOrder
            listOf(
                "POST /api/child/missions/{missionId}/tasks/{taskId}/attempts",
                "POST /api/child/missions/{missionId}/tasks/{taskId}/hints",
                "POST /api/child/missions/{missionId}/tasks/{taskId}/reports",
                "POST /api/child/missions/{missionId}/replay",
                "POST /api/child/hq/rank-up-ack",
                "PATCH /api/child/profile/settings",
            )
    }
}
