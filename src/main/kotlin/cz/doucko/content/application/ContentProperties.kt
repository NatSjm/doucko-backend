package cz.doucko.content.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * content-authoring configuration (CA-D16, FR-8, NFR-17): allowed grades and subjects (MVP `6`, `math`),
 * task locales (bound from `doucko.profile.task-locales`, one setting for both modules) and the storage
 * root for uploaded files (`DOUCKO_STORAGE_DIR`), which must lie outside every repository.
 */
@ConfigurationProperties("doucko.content")
data class ContentProperties(
    val grades: List<Int> = listOf(6),
    val subjects: List<String> = listOf("math"),
    val taskLocales: List<String> = emptyList(),
    val storageDir: String = "",
)
