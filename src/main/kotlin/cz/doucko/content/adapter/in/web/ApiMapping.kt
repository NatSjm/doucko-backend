package cz.doucko.content.adapter.`in`.web

import cz.doucko.api.model.AdminMission
import cz.doucko.api.model.AdminTask
import cz.doucko.api.model.AnswerSpec
import cz.doucko.api.model.AnswerSpecOptionsInner
import cz.doucko.api.model.AnswerType
import cz.doucko.api.model.HintState
import cz.doucko.api.model.LocalizedText
import cz.doucko.api.model.LocalizedTextSecondary
import cz.doucko.api.model.Modality
import cz.doucko.api.model.OpenedHint
import cz.doucko.api.model.PartInput
import cz.doucko.api.model.PartView
import cz.doucko.api.model.PartViewOptionsInner
import cz.doucko.api.model.Provenance
import cz.doucko.api.model.PublishStatus
import cz.doucko.api.model.TaskState
import cz.doucko.api.model.TaskSummary
import cz.doucko.api.model.TaskView
import cz.doucko.api.model.TaskViewImagesInner
import cz.doucko.api.model.Translation
import cz.doucko.content.application.MissionView
import cz.doucko.content.application.TaskFacts
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.SpecRules
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.Text
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import cz.doucko.api.model.TaskImage as ApiTaskImage
import cz.doucko.content.domain.Part as DomainPart
import cz.doucko.content.domain.TaskImage as DomainTaskImage

internal object Requests {
    fun current(): HttpServletRequest = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
}

/** Domain → contract models of the task operations. */
internal object ApiMapping {
    /** Length of `TaskSummary.promptExcerpt` in code points. */
    private const val EXCERPT = 120

    fun imageUrl(
        taskId: java.util.UUID,
        imageId: java.util.UUID,
    ) = "/api/admin/tasks/$taskId/images/$imageId"

    fun image(
        taskId: java.util.UUID,
        image: DomainTaskImage,
    ) = ApiTaskImage(image.id, image.position, imageUrl(taskId, image.id))

    fun provenance(
        task: Task,
        source: Source?,
    ) = Provenance(
        sourceId = task.content.sourceRef.sourceId,
        sourceTitle = source?.metadata?.title.orEmpty(),
        page = task.content.sourceRef.page,
        taskNo = task.content.sourceRef.taskNo,
        edition = source?.metadata?.edition,
        subPart = task.content.sourceRef.subPart,
    )

    fun adminTask(
        task: Task,
        source: Source?,
        facts: TaskFacts,
    ): AdminTask {
        val c = task.content
        return AdminTask(
            prompt = c.prompt,
            topicId = c.topicId,
            grade = c.grade,
            difficulty = c.difficulty,
            modality = Modality.forValue(c.modality.name),
            sourceRef =
                cz.doucko.api.model
                    .SourceRef(c.sourceRef.sourceId, c.sourceRef.page, c.sourceRef.taskNo, c.sourceRef.subPart),
            parts = c.parts.map(::part),
            hints = c.hints,
            deletable = facts.deletable,
            taskId = task.id,
            status = PublishStatus.forValue(task.status.name),
            images = task.images.map { image(task.id, it) },
            provenance = provenance(task, source),
            attemptCount = facts.attemptCount,
            missionIds = facts.missionIds,
            translations = c.translations.mapValues { (_, t) -> Translation(t.prompt, t.partLabels, t.hints, t.options) },
        )
    }

    fun adminMission(view: MissionView): AdminMission {
        val m = view.mission
        return AdminMission(
            topicId = m.content.topicId,
            position = m.content.position,
            taskIds = m.content.taskIds,
            deletable = view.deletable,
            missionId = m.id,
            status = PublishStatus.forValue(m.status.name),
            hasAttempts = view.hasAttempts,
        )
    }

    private fun part(part: DomainPart): PartInput {
        val s = part.spec
        return PartInput(
            key = part.key,
            label = part.label,
            spec =
                AnswerSpec(
                    type = AnswerType.forValue(s.type.name),
                    accepted = s.accepted,
                    tolerance = s.tolerance,
                    exactDecimals = s.exactDecimals,
                    allowEquivalent = s.allowEquivalent,
                    requireLowestTerms = s.requireLowestTerms,
                    unit = SpecRules.unit(s),
                    unitRequired = s.unitRequired,
                    diacriticsStrict = s.diacriticsStrict,
                    options = s.options.map { AnswerSpecOptionsInner(it.key, it.text) }.ifEmpty { null },
                    acceptedNormalized = SpecRules.normalized(s),
                ),
        )
    }

    fun summary(
        task: Task,
        source: Source?,
    ): TaskSummary {
        val prompt = task.content.prompt
        val excerpt =
            if (Text.codePoints(prompt) <= EXCERPT) prompt else prompt.substring(0, prompt.offsetByCodePoints(0, EXCERPT)) + "…"
        return TaskSummary(
            taskId = task.id,
            promptExcerpt = excerpt,
            topicId = task.content.topicId,
            grade = task.content.grade,
            difficulty = task.content.difficulty,
            modality = Modality.forValue(task.content.modality.name),
            status = PublishStatus.forValue(task.status.name),
            provenance = provenance(task, source),
        )
    }

    /**
     * The child's view of the task (contract `TaskView`) for the admin preview (FR-10): no source
     * reference, every hint opened, outside a mission. [locale] adds the translated text where one exists
     * (a missing translated text falls back to Czech, FR-8).
     */
    fun preview(
        task: Task,
        locale: String?,
        configuredLocales: List<String>,
    ): TaskView {
        val c = task.content
        val translation = locale?.let { c.translations[it] }

        fun text(
            cs: String,
            secondary: String?,
        ) = LocalizedText(cs, if (locale != null && secondary != null) LocalizedTextSecondary(locale, secondary) else null)
        val available =
            if (locale != null) {
                translation != null && !translation.isEmpty()
            } else {
                c.translations.any { (l, t) -> l in configuredLocales && !t.isEmpty() }
            }
        return TaskView(
            taskId = task.id,
            missionId = null,
            position = 1,
            prompt = text(c.prompt, translation?.prompt),
            translationAvailable = available,
            images = task.images.map { TaskViewImagesInner(it.id, imageUrl(task.id, it.id)) },
            parts =
                c.parts.map { part ->
                    PartView(
                        key = part.key,
                        label = text(part.label, translation?.partLabels?.get(part.key)),
                        type = AnswerType.forValue(part.spec.type.name),
                        unit = SpecRules.unit(part.spec),
                        options =
                            part.spec.options
                                .map { PartViewOptionsInner(it.key, text(it.text, translation?.options?.get("${part.key}.${it.key}"))) }
                                .ifEmpty { null },
                    )
                },
            hints =
                HintState(
                    total = c.hints.size,
                    opened =
                        c.hints.mapIndexed { index, hint ->
                            OpenedHint(index + 1, text(hint, translation?.hints?.getOrNull(index)), c.hints.size - index - 1)
                        },
                ),
            maxAttempts = TaskView.MaxAttempts._2,
            attemptsUsed = 0,
            state = TaskState.UNRESOLVED,
            lastFeedback = null,
        )
    }
}
