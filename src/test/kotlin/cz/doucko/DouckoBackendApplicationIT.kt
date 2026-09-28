package cz.doucko

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DouckoBackendApplicationIT {
    @Test
    fun `context starts against Postgres 16 with Flyway applied`() {
    }
}
