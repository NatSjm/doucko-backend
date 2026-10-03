package cz.doucko

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    private val modules = ApplicationModules.of(DouckoBackendApplication::class.java)

    // @trace NFR-11
    @Test
    fun `modules only talk through each other's public API`() {
        modules.verify()
    }
}
