package cz.doucko.content.application

import cz.doucko.checking.AnswerChecker
import cz.doucko.checking.AnswerType
import cz.doucko.checking.CheckResult
import cz.doucko.checking.RawInput
import cz.doucko.content.ContentUsage
import cz.doucko.content.domain.FieldError
import cz.doucko.content.domain.FileKind
import cz.doucko.content.domain.Limits
import cz.doucko.content.domain.MissionRules
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.content.domain.TaskContext
import cz.doucko.content.domain.TaskDraft
import cz.doucko.content.domain.TaskFilter
import cz.doucko.content.domain.TaskImage
import cz.doucko.content.domain.TaskRules
import cz.doucko.content.domain.Validation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.util.UUID

/** One part's raw answer in the answer tester (contract `AttemptRequest.answers`). */
sealed interface AnswerInput {
    data class Text(
        val value: String,
    ) : AnswerInput

    data class Keys(
        val keys: List<String>,
    ) : AnswerInput
}

/** One part's tester result (contract `PartResult`, `locked` always false). */
data class PartCheck(
    val key: String,
    val result: CheckResult,
)

/** One bulk-publish result (contract `PublishResult`). */
data class PublishOutcome(
    val taskId: UUID,
    val published: Boolean,
    val reason: String?,
)

/** What other entities know about a task (contract `AdminTask.missionIds`, `attemptCount`, `deletable`). */
data class TaskFacts(
    val missionIds: List<UUID>,
    val attemptCount: Int,
    val deletable: Boolean,
)

/** List filters as submitted (FR-11); [query] is the raw `q`. */
data class TaskQuery(
    val grade: Int?,
    val topicId: UUID?,
    val sourceId: UUID?,
    val page: Int?,
    val modality: Modality?,
    val status: PublishStatus?,
    val query: String?,
)

/**
 * Tasks (store spec content-authoring: create/edit with a source reference, images, modality, parts,
 * answer specs, hints, translations, list filters and search, preview, answer tester, bulk publish;
 * FR-3..FR-8, FR-10, FR-11, FR-13). A refused save stores nothing. The answer tester grades with
 * [AnswerChecker] and records nothing (FR-10). Edits, status changes and deletes keep the missions that
 * contain the task valid and take the content lock first (FR-56, FR-57, CA-D6, CA-D8, CA-D10, CA-D22).
 */
@Service
class TaskService(
    private val tasks: TaskStore,
    private val sources: SourceStore,
    private val topics: TopicStore,
    private val storage: FileStoragePort,
    private val properties: ContentProperties,
    private val missions: MissionStore,
    private val lock: ContentLock,
    private val conflicts: Conflicts,
    private val usageProvider: ObjectProvider<ContentUsage>,
) {
    private val usage: ContentUsage get() = usageProvider.getIfAvailable { ContentUsage.NONE }

    fun list(query: TaskQuery): List<Task> {
        val text =
            when (val q = TaskRules.query(query.query)) {
                is Validation.Invalid -> throw InvalidContent(q.errors)
                is Validation.Valid -> q.value
            }
        return tasks.list(
            TaskFilter(query.grade, query.topicId, query.sourceId, query.page, query.modality, query.status, text),
        )
    }

    fun get(id: UUID): Task = tasks.find(id) ?: throw ContentNotFound()

    /** Missions, attempts and deletability of [task] (FR-56). */
    fun facts(task: Task): TaskFacts {
        val containing = missions.containing(task.id)
        val used = task.id in usage.usedTasks(listOf(task.id))
        return TaskFacts(
            missionIds = containing.map { it.id },
            attemptCount = usage.attemptCounts(listOf(task.id))[task.id] ?: 0,
            deletable = MissionRules.taskDelete(task.id, containing, used) == null,
        )
    }

    /** The source of each task (provenance, FR-13). */
    fun sourcesOf(list: List<Task>): Map<UUID, Source> =
        list
            .map { it.content.sourceRef.sourceId }
            .distinct()
            .mapNotNull { sources.find(it) }
            .associateBy { it.id }

    @Transactional
    fun create(draft: TaskDraft): Task {
        val task = Task(UUID.randomUUID(), validate(draft), PublishStatus.DRAFT, emptyList())
        tasks.insert(task)
        return task
    }

    @Transactional
    fun update(
        id: UUID,
        draft: TaskDraft,
    ): Task {
        lock.exclusive()
        val current = get(id)
        val updated = current.copy(content = validate(draft))
        MissionRules.taskEdit(current.content, updated.content, missions.containing(id))?.let { throw conflicts.of(it) }
        tasks.update(updated)
        return updated
    }

    @Transactional
    fun delete(id: UUID) {
        lock.exclusive()
        val current = get(id)
        val used = id in usage.usedTasks(listOf(id))
        MissionRules.taskDelete(id, missions.containing(id), used)?.let { throw conflicts.of(it) }
        missions.removeTask(id)
        tasks.delete(id)
        afterCommit { current.images.forEach { storage.delete(it.storageKey) } }
    }

    @Transactional
    fun setStatus(
        id: UUID,
        status: PublishStatus,
    ): Task {
        lock.exclusive()
        val current = get(id)
        MissionRules.taskStatus(status, missions.containing(id))?.let { throw conflicts.of(it) }
        if (current.status != status) tasks.setStatus(id, status)
        return current.copy(status = status)
    }

    /** Publishes each task independently (OQ-32); an unknown id is reported as `not found`. */
    @Transactional
    fun bulkPublish(ids: List<UUID>): List<PublishOutcome> {
        lock.exclusive()
        return ids.map { id ->
            val task = tasks.find(id) ?: return@map PublishOutcome(id, false, NOT_FOUND)
            if (task.status != PublishStatus.PUBLISHED) tasks.setStatus(id, PublishStatus.PUBLISHED)
            PublishOutcome(id, true, null)
        }
    }

    @Transactional
    fun addImage(
        taskId: UUID,
        file: Upload,
    ): TaskImage {
        tasks.lock(taskId)
        val task = get(taskId)
        if (file.size > Limits.IMAGE_BYTES) throw UploadTooLarge(UploadKind.IMAGE)
        val bytes = file.read()
        if (bytes.size > Limits.IMAGE_BYTES) throw UploadTooLarge(UploadKind.IMAGE)
        if (bytes.isEmpty()) throw InvalidContent(listOf(FieldError("file", "file-empty")))
        val kind = FileKind.detect(bytes.copyOf(minOf(bytes.size, HEAD)))
        if (kind != FileKind.PNG && kind != FileKind.JPEG) throw UnsupportedUpload(UploadKind.IMAGE)
        if (task.images.size >= Limits.IMAGES) throw InvalidContent(listOf(FieldError("file", "too-many-images", listOf(Limits.IMAGES))))
        val id = UUID.randomUUID()
        val image = TaskImage(id, task.images.size + 1, kind.mediaType, "images/$id.${kind.extension}")
        storage.store(image.storageKey, bytes)
        onRollback { storage.delete(image.storageKey) }
        tasks.addImage(taskId, image)
        return image
    }

    @Transactional
    fun reorderImages(
        taskId: UUID,
        imageIds: List<UUID>,
    ): List<TaskImage> {
        tasks.lock(taskId)
        val task = get(taskId)
        val duplicates = imageIds.withIndex().filter { (i, id) -> imageIds.indexOf(id) != i }
        if (duplicates.isNotEmpty()) throw InvalidContent(duplicates.map { FieldError("imageIds[${it.index}]", "duplicate") })
        if (imageIds.toSet() != task.images.map { it.id }.toSet()) {
            throw InvalidContent(listOf(FieldError("imageIds", "images-exactly-once")))
        }
        tasks.reorderImages(taskId, imageIds)
        return get(taskId).images
    }

    fun image(
        taskId: UUID,
        imageId: UUID,
    ): Pair<TaskImage, InputStream> {
        val image = get(taskId).images.firstOrNull { it.id == imageId } ?: throw ContentNotFound()
        return image to (storage.open(image.storageKey) ?: throw ContentNotFound())
    }

    @Transactional
    fun deleteImage(
        taskId: UUID,
        imageId: UUID,
    ) {
        tasks.lock(taskId)
        val image = get(taskId).images.firstOrNull { it.id == imageId } ?: throw ContentNotFound()
        tasks.removeImage(taskId, imageId)
        afterCommit { storage.delete(image.storageKey) }
    }

    /** The task for the admin preview; [locale] must be a configured task locale (FR-8, FR-10). */
    fun preview(
        id: UUID,
        locale: String?,
    ): Task {
        if (locale != null && locale !in properties.taskLocales) {
            throw InvalidContent(listOf(FieldError("locale", "locale", listOf(locales()))))
        }
        return get(id)
    }

    /** Grades every part with the checker (FR-10). Nothing is recorded. A part without an answer is graded as empty input. */
    fun test(
        id: UUID,
        answers: Map<String, AnswerInput>,
    ): List<PartCheck> {
        val parts = get(id).content.parts
        val errors =
            answers.mapNotNull { (key, input) ->
                val part = parts.firstOrNull { it.key == key } ?: return@mapNotNull FieldError("answers.$key", "answer-unknown-part")
                val choice = part.spec.type == AnswerType.CHOICE_SINGLE || part.spec.type == AnswerType.CHOICE_MULTI
                if (choice != (input is AnswerInput.Keys)) FieldError("answers.$key", "answer-shape") else null
            }
        if (errors.isNotEmpty()) throw InvalidContent(errors)
        return parts.map { part ->
            val raw =
                when (val input = answers[part.key]) {
                    is AnswerInput.Text -> {
                        RawInput.Text(input.value)
                    }

                    is AnswerInput.Keys -> {
                        RawInput.Selection(input.keys)
                    }

                    null -> {
                        if (part.spec.type.name
                                .startsWith("CHOICE")
                        ) {
                            RawInput.Selection(emptyList())
                        } else {
                            RawInput.Text("")
                        }
                    }
                }
            PartCheck(part.key, AnswerChecker.check(part.spec.toChecker(), raw))
        }
    }

    fun locales(): String = properties.taskLocales.joinToString(", ").ifEmpty { "none" }

    private fun validate(draft: TaskDraft): TaskContent {
        val source =
            draft.sourceRef
                .orNull()
                ?.sourceId
                ?.orNull()
                ?.let(sources::find)
        val topic = draft.topicId.orNull()?.let(topics::find)
        return when (val result = TaskRules.validate(draft, TaskContext(source, topic, properties.grades, properties.taskLocales))) {
            is Validation.Invalid -> throw InvalidContent(result.errors)
            is Validation.Valid -> result.value
        }
    }

    private companion object {
        const val HEAD = 16

        /** Normative reason for an unknown id (contract `PublishResult.reason`). */
        const val NOT_FOUND = "not found"
    }
}
