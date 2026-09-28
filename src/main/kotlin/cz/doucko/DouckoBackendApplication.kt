package cz.doucko

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DouckoBackendApplication

fun main(args: Array<String>) {
    runApplication<DouckoBackendApplication>(*args)
}
