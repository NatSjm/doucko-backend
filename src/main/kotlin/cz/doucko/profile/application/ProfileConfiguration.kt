package cz.doucko.profile.application

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** The profile's use case and first-start initializer, over whichever [ChildProfileStore] is present. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskLocaleProperties::class)
class ProfileConfiguration {
    @Bean
    fun profileSettingsService(
        store: ChildProfileStore,
        locales: TaskLocaleProperties,
    ) = ProfileSettingsService(store, locales)

    @Bean
    fun childProfileInitializer(
        store: ChildProfileStore,
        environment: Environment,
    ) = ChildProfileInitializer(store, environment)
}
