package cz.doucko.profile.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The task locales a profile may choose as `secondaryTaskLocale` (store spec child-profile,
 * "Supported task locales are not hardcoded"). MVP configures `uk` in `application.yaml`.
 */
@ConfigurationProperties("doucko.profile")
data class TaskLocaleProperties(
    val taskLocales: List<String> = emptyList(),
)
