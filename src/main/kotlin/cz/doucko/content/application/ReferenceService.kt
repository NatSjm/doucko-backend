package cz.doucko.content.application

import cz.doucko.content.domain.Topic
import org.springframework.stereotype.Service

/** Reference data of the editors (contract `ReferenceData`, CA-D16, FR-8). */
data class Reference(
    val grades: List<Int>,
    val subjects: List<String>,
    val topics: List<Topic>,
    val taskLocales: List<String>,
)

@Service
class ReferenceService(
    private val topics: TopicStore,
    private val properties: ContentProperties,
) {
    fun reference() = Reference(properties.grades, properties.subjects, topics.all(), properties.taskLocales)
}
