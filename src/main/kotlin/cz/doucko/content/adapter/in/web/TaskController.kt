package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.AdminTasksApi
import cz.doucko.api.model.AdminTask
import cz.doucko.api.model.AttemptRequest
import cz.doucko.api.model.BulkPublishTasksRequest
import cz.doucko.api.model.Modality
import cz.doucko.api.model.PartResult
import cz.doucko.api.model.PublishResult
import cz.doucko.api.model.PublishStatus
import cz.doucko.api.model.ReorderTaskImagesRequest
import cz.doucko.api.model.StatusChange
import cz.doucko.api.model.TaskImage
import cz.doucko.api.model.TaskInput
import cz.doucko.api.model.TaskSummary
import cz.doucko.api.model.TaskView
import cz.doucko.api.model.Verdict
import cz.doucko.content.application.ContentProperties
import cz.doucko.content.application.TaskQuery
import cz.doucko.content.application.TaskService
import cz.doucko.content.application.Upload
import cz.doucko.content.domain.Limits
import cz.doucko.content.domain.Task
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import cz.doucko.content.domain.Modality as DomainModality
import cz.doucko.content.domain.PublishStatus as DomainStatus

/**
 * The admin task operations of the contract (store spec content-authoring; FR-3..FR-8, FR-10, FR-11,
 * FR-13, OQ-32). Behind the admin session and CSRF chain (admin-access). JSON bodies are read by
 * [ContentBodyAdvice]; the generated DTO parameters only ever hold its placeholder.
 */
@RestController
class TaskController(
    private val service: TaskService,
    private val properties: ContentProperties,
) : AdminTasksApi {
    override fun listTasks(
        grade: Int?,
        topicId: UUID?,
        sourceId: UUID?,
        page: Int?,
        modality: Modality?,
        status: PublishStatus?,
        q: String?,
    ): ResponseEntity<List<TaskSummary>> {
        val query =
            TaskQuery(
                grade,
                topicId,
                sourceId,
                page,
                modality?.let { DomainModality.valueOf(it.value) },
                status?.let { DomainStatus.valueOf(it.value) },
                q,
            )
        val tasks = service.list(query)
        val sources = service.sourcesOf(tasks)
        return ResponseEntity.ok(tasks.map { ApiMapping.summary(it, sources[it.content.sourceRef.sourceId]) })
    }

    override fun createTask(taskInput: TaskInput): ResponseEntity<AdminTask> =
        ResponseEntity.status(201).body(admin(service.create(JsonReaders.task(body()))))

    override fun getAdminTask(taskId: UUID): ResponseEntity<AdminTask> = ResponseEntity.ok(admin(service.get(taskId)))

    override fun updateTask(
        taskId: UUID,
        taskInput: TaskInput,
    ): ResponseEntity<AdminTask> = ResponseEntity.ok(admin(service.update(taskId, JsonReaders.task(body()))))

    override fun deleteTask(taskId: UUID): ResponseEntity<Unit> {
        service.delete(taskId)
        return ResponseEntity.noContent().build()
    }

    override fun setTaskStatus(
        taskId: UUID,
        statusChange: StatusChange,
    ): ResponseEntity<AdminTask> = ResponseEntity.ok(admin(service.setStatus(taskId, JsonReaders.status(body()))))

    override fun bulkPublishTasks(bulkPublishTasksRequest: BulkPublishTasksRequest): ResponseEntity<List<PublishResult>> =
        ResponseEntity.ok(
            service.bulkPublish(JsonReaders.taskIds(body())).map { PublishResult(it.taskId, it.published, it.reason) },
        )

    override fun addTaskImage(
        taskId: UUID,
        file: MultipartFile,
    ): ResponseEntity<TaskImage> {
        val upload = Upload(file.size) { file.inputStream.use { it.readNBytes(Limits.IMAGE_BYTES.toInt() + 1) } }
        return ResponseEntity.status(201).body(ApiMapping.image(taskId, service.addImage(taskId, upload)))
    }

    override fun reorderTaskImages(
        taskId: UUID,
        reorderTaskImagesRequest: ReorderTaskImagesRequest,
    ): ResponseEntity<List<TaskImage>> =
        ResponseEntity.ok(service.reorderImages(taskId, JsonReaders.imageIds(body())).map { ApiMapping.image(taskId, it) })

    override fun getAdminImage(
        taskId: UUID,
        imageId: UUID,
    ): ResponseEntity<Resource> {
        val (image, content) = service.image(taskId, imageId)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.mediaType))
            .body(InputStreamResource(content))
    }

    override fun deleteTaskImage(
        taskId: UUID,
        imageId: UUID,
    ): ResponseEntity<Unit> {
        service.deleteImage(taskId, imageId)
        return ResponseEntity.noContent().build()
    }

    override fun previewTask(
        taskId: UUID,
        locale: String?,
    ): ResponseEntity<TaskView> = ResponseEntity.ok(ApiMapping.preview(service.preview(taskId, locale), locale, properties.taskLocales))

    override fun testAnswers(
        taskId: UUID,
        attemptRequest: AttemptRequest,
    ): ResponseEntity<List<PartResult>> =
        ResponseEntity.ok(
            service.test(taskId, JsonReaders.answers(body())).map {
                PartResult(it.key, Verdict.forValue(it.result.verdict.name), it.result.normalized, it.result.formatTip, false)
            },
        )

    private fun body() = ContentBodyAdvice.body(Requests.current())

    private fun admin(task: Task): AdminTask =
        ApiMapping.adminTask(task, service.sourcesOf(listOf(task))[task.content.sourceRef.sourceId], service.facts(task))
}
