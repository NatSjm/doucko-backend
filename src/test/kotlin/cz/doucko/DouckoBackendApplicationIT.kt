package cz.doucko

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class DouckoBackendApplicationIT {
    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `context starts against Postgres 16 with Flyway applied`() {
    }

    // @trace NFR-2
    @Test
    fun `only the health endpoint of actuator is reachable`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }
        for (path in listOf("/actuator/modulith", "/actuator/info", "/actuator/env", "/actuator/httpexchanges")) {
            mvc.get(path).andExpect { status { isNotFound() } }
        }
    }
}
