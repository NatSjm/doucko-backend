package cz.doucko.content.application

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Binds [ContentProperties]. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentProperties::class)
class ContentConfiguration
