package cz.doucko

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** Runs the app locally against a throwaway Postgres container: `node scripts/mvnw.mjs spring-boot:test-run`. */
fun main(args: Array<String>) {
    fromApplication<DouckoBackendApplication>().with(TestcontainersConfiguration::class).run(*args)
}
