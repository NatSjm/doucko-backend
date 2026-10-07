package cz.doucko.practice.application

import cz.doucko.practice.domain.GamificationConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

/**
 * Loads the gamification configuration when the context starts (design decision 2): the XP values and the
 * rank ladder from the JSON file at `doucko.gamification.config` (default `classpath:gamification.json`).
 * An unreadable or invalid file stops the start with [InvalidGamificationConfig] naming the key, so no XP is
 * ever computed from an invalid configuration (OQ-29).
 */
@Configuration(proxyBeanMethods = false)
class GamificationSettings {
    @Bean
    fun gamificationConfig(
        @Value("\${doucko.gamification.config:classpath:gamification.json}") location: String,
        resources: ResourceLoader,
    ): GamificationConfig {
        val resource = resources.getResource(location)
        if (!resource.exists()) throw InvalidGamificationConfig("doucko.gamification.config", "names no readable file")
        val text = resource.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        return GamificationConfigParser.parse(text)
    }
}
