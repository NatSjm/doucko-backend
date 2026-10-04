package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.AdminMissionsApi
import cz.doucko.api.model.AdminMission
import cz.doucko.api.model.MissionInput
import cz.doucko.api.model.StatusChange
import cz.doucko.content.application.MissionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The admin mission operations of the contract (store spec content-authoring "Missions", "Mission
 * publishing rules", "Edit existing content", "Delete only unused content"; FR-9, FR-55, FR-56, FR-57).
 * Behind the admin session and CSRF chain (admin-access). JSON bodies are read by [ContentBodyAdvice];
 * the generated DTO parameters only ever hold its placeholder.
 */
@RestController
class MissionController(
    private val service: MissionService,
) : AdminMissionsApi {
    override fun listMissions(): ResponseEntity<List<AdminMission>> = ResponseEntity.ok(service.list().map(ApiMapping::adminMission))

    override fun createMission(missionInput: MissionInput): ResponseEntity<AdminMission> =
        ResponseEntity.status(201).body(ApiMapping.adminMission(service.create(JsonReaders.mission(body()))))

    override fun getAdminMission(missionId: UUID): ResponseEntity<AdminMission> =
        ResponseEntity.ok(ApiMapping.adminMission(service.get(missionId)))

    override fun updateMission(
        missionId: UUID,
        missionInput: MissionInput,
    ): ResponseEntity<AdminMission> = ResponseEntity.ok(ApiMapping.adminMission(service.update(missionId, JsonReaders.mission(body()))))

    override fun deleteMission(missionId: UUID): ResponseEntity<Unit> {
        service.delete(missionId)
        return ResponseEntity.noContent().build()
    }

    override fun setMissionStatus(
        missionId: UUID,
        statusChange: StatusChange,
    ): ResponseEntity<AdminMission> = ResponseEntity.ok(ApiMapping.adminMission(service.setStatus(missionId, JsonReaders.status(body()))))

    private fun body() = ContentBodyAdvice.body(Requests.current())
}
