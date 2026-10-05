package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.ChildHqApi
import cz.doucko.api.ChildPracticeApi
import cz.doucko.api.model.AckRankUpRequest
import cz.doucko.api.model.AttemptRequest
import cz.doucko.api.model.AttemptResult
import cz.doucko.api.model.HqView
import cz.doucko.api.model.MissionBriefing
import cz.doucko.api.model.MissionSummary
import cz.doucko.api.model.OpenedHint
import cz.doucko.api.model.ReportRequest
import cz.doucko.api.model.TaskView
import cz.doucko.practice.application.PracticeService
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

private fun body() = PracticeBodyAdvice.body((RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request)

/**
 * `ChildHqApi` (store spec practice-flow "HQ without login", "Mission list on HQ"; FR-24, FR-25). Login-free:
 * the shared chain lets every child request through. XP, ranks and the summary are slice-8 placeholders
 * (design decision 7).
 */
@RestController
class ChildHqController(
    private val service: PracticeService,
) : ChildHqApi {
    override fun getHq(): ResponseEntity<HqView> = ResponseEntity.ok(PracticeMapping.hq(service.hq()))

    override fun getMissionBriefing(missionId: UUID): ResponseEntity<MissionBriefing> =
        ResponseEntity.ok(PracticeMapping.briefing(service.briefing(missionId)))

    override fun getMissionSummary(missionId: UUID): ResponseEntity<MissionSummary> =
        ResponseEntity.ok(PracticeMapping.summary(service.summary(missionId)))

    /** Nothing is ever pending before slice 8: the body is validated and the answer is 204. */
    override fun ackRankUp(ackRankUpRequest: AckRankUpRequest): ResponseEntity<Unit> {
        PracticeReaders.rankIndex(body())
        return ResponseEntity.noContent().build()
    }
}

/**
 * `ChildPracticeApi` (store spec practice-flow; FR-22, FR-26..FR-31, FR-44): the task screen, attempts,
 * hints, problem reports, resume and task images. Bodies come from [PracticeBodyAdvice].
 */
@RestController
class ChildPracticeController(
    private val service: PracticeService,
) : ChildPracticeApi {
    override fun getTask(
        missionId: UUID,
        taskId: UUID,
    ): ResponseEntity<TaskView> = ResponseEntity.ok(PracticeMapping.task(service.task(missionId, taskId)))

    override fun getCurrentTask(missionId: UUID): ResponseEntity<TaskView> =
        service.currentTask(missionId)?.let { ResponseEntity.ok(PracticeMapping.task(it)) } ?: ResponseEntity.noContent().build()

    override fun submitAttempt(
        missionId: UUID,
        taskId: UUID,
        attemptRequest: AttemptRequest,
    ): ResponseEntity<AttemptResult> {
        val answers = PracticeReaders.answers(body())
        return ResponseEntity.ok(PracticeMapping.attempt(service.submit(missionId, taskId, answers)))
    }

    override fun openNextHint(
        missionId: UUID,
        taskId: UUID,
    ): ResponseEntity<OpenedHint> = ResponseEntity.ok(PracticeMapping.hint(service.openHint(missionId, taskId)))

    override fun reportProblem(
        missionId: UUID,
        taskId: UUID,
        reportRequest: ReportRequest,
    ): ResponseEntity<Unit> {
        service.report(missionId, taskId, PracticeReaders.report(body()))
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    override fun getChildImage(imageId: UUID): ResponseEntity<Resource> {
        val image = service.image(imageId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType)).body(ByteArrayResource(image.content))
    }

    override fun startReplay(missionId: UUID): ResponseEntity<TaskView> = ResponseEntity.ok(PracticeMapping.task(service.replay(missionId)))
}
