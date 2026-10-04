package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.AdminReferenceApi
import cz.doucko.api.model.ReferenceData
import cz.doucko.api.model.ReferenceDataTopicsInner
import cz.doucko.content.application.ReferenceService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** `getReferenceData`: configured grades, subjects and task locales, and the seeded topics (CA-D16, FR-8). */
@RestController
class ReferenceController(
    private val service: ReferenceService,
) : AdminReferenceApi {
    override fun getReferenceData(): ResponseEntity<ReferenceData> {
        val reference = service.reference()
        return ResponseEntity.ok(
            ReferenceData(
                grades = reference.grades,
                subjects = reference.subjects,
                topics = reference.topics.map { ReferenceDataTopicsInner(it.id, it.code, it.grade, it.name) },
                taskLocales = reference.taskLocales,
            ),
        )
    }
}
