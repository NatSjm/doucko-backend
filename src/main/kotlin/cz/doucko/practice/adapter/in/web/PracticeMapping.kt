package cz.doucko.practice.adapter.`in`.web

import cz.doucko.api.model.AnswerType
import cz.doucko.api.model.AttemptResult
import cz.doucko.api.model.Feedback
import cz.doucko.api.model.FeedbackRevealedAnswersInner
import cz.doucko.api.model.HintState
import cz.doucko.api.model.HqView
import cz.doucko.api.model.HqViewContinueTarget
import cz.doucko.api.model.HqViewTopicsInner
import cz.doucko.api.model.LocalizedText
import cz.doucko.api.model.LocalizedTextSecondary
import cz.doucko.api.model.MissionBriefing
import cz.doucko.api.model.MissionBriefingTasksInner
import cz.doucko.api.model.MissionCard
import cz.doucko.api.model.MissionState
import cz.doucko.api.model.MissionSummary
import cz.doucko.api.model.OpenedHint
import cz.doucko.api.model.PartResult
import cz.doucko.api.model.PartView
import cz.doucko.api.model.PartViewOptionsInner
import cz.doucko.api.model.Rank
import cz.doucko.api.model.TaskState
import cz.doucko.api.model.TaskView
import cz.doucko.api.model.TaskViewImagesInner
import cz.doucko.api.model.Verdict
import cz.doucko.content.ServedTask
import cz.doucko.practice.application.AttemptOutcome
import cz.doucko.practice.application.BriefingData
import cz.doucko.practice.application.HintOpened
import cz.doucko.practice.application.HqData
import cz.doucko.practice.application.SummaryData
import cz.doucko.practice.application.TaskScreen

/**
 * Practice views → generated contract models (NFR-12). Translated text is added next to the Czech only
 * when the profile's locale is on and the text has a translation (FR-29); otherwise `secondary` is null.
 * Slice-8 fields are the placeholders of design decision 7.
 */
internal object PracticeMapping {
    fun hq(data: HqData) =
        HqView(
            displayName = data.displayName,
            totalXp = data.totalXp,
            solvedTasks = data.solvedTasks,
            rank = rank(data.rank),
            nextRank = data.nextRank?.let(::rank),
            continueTarget = data.continueTarget?.let { (missionId, taskId) -> HqViewContinueTarget(missionId, taskId) },
            topics =
                data.topics.map { topic ->
                    HqViewTopicsInner(
                        topic.topicId,
                        topic.name,
                        topic.missions.map {
                            MissionCard(
                                it.missionId,
                                it.position,
                                it.taskCount,
                                it.resolvedCount,
                                MissionState.forValue(it.state.name),
                            )
                        },
                    )
                },
            pendingRankUp = null,
        )

    fun briefing(data: BriefingData) =
        MissionBriefing(
            missionId = data.missionId,
            position = data.position,
            topicName = data.topicName,
            state = MissionState.forValue(data.state.name),
            xpAvailable = 0,
            tasks = data.tasks.map { MissionBriefingTasksInner(it.taskId, it.position, TaskState.forValue(it.state.name)) },
        )

    fun summary(data: SummaryData) =
        MissionSummary(
            missionId = data.missionId,
            xpEarned = 0,
            firstTryCount = data.firstTryCount,
            taskCount = data.taskCount,
            replay = data.replay,
            rankUp = null,
            rankReachedDuringRun = null,
        )

    fun task(screen: TaskScreen): TaskView {
        val task = screen.task
        val locale = screen.locale
        val translation = locale?.let { task.translations[it] }

        fun text(
            cs: String,
            secondary: String?,
        ) = localized(locale, cs, secondary)
        return TaskView(
            taskId = task.taskId,
            missionId = screen.missionId,
            position = screen.position,
            prompt = text(task.prompt, translation?.prompt),
            translationAvailable = if (locale != null) translation != null else task.translations.isNotEmpty(),
            images = task.imageIds.map { TaskViewImagesInner(it, "/api/child/images/$it") },
            parts =
                task.parts.map { part ->
                    PartView(
                        key = part.key,
                        label = text(part.label, translation?.partLabels?.get(part.key)),
                        type = AnswerType.forValue(part.spec.type.name),
                        unit = part.unit,
                        options =
                            part.options
                                .map { PartViewOptionsInner(it.key, text(it.text, translation?.options?.get("${part.key}.${it.key}"))) }
                                .ifEmpty { null },
                    )
                },
            hints = HintState(total = task.hints.size, opened = (1..screen.hintsOpened).map { hint(task, it, locale) }),
            maxAttempts = TaskView.MaxAttempts._2,
            attemptsUsed = screen.attemptsUsed,
            state = TaskState.forValue(screen.state.name),
            lastFeedback = screen.lastResult?.let(::attempt),
        )
    }

    fun hint(opened: HintOpened) = hint(opened.task, opened.index, opened.locale)

    fun attempt(outcome: AttemptOutcome) =
        AttemptResult(
            status = if (outcome.graded) AttemptResult.Status.GRADED else AttemptResult.Status.UNPARSEABLE,
            attemptNo = outcome.attemptNo,
            parts = outcome.parts.map { PartResult(it.key, Verdict.forValue(it.verdict.name), it.normalized, it.formatTip, it.locked) },
            taskState = TaskState.forValue(outcome.state.name),
            attemptsLeft = outcome.attemptsLeft,
            xpAwarded = 0,
            feedback =
                outcome.feedback?.let { feedback ->
                    Feedback(
                        nextHintIndex = feedback.nextHintIndex,
                        revealedAnswers =
                            feedback.revealedAnswers?.map {
                                FeedbackRevealedAnswersInner(
                                    it.key,
                                    RevealedAnswers.of(it.answer),
                                )
                            },
                    )
                },
            rankUp = null,
            missionComplete = outcome.missionComplete,
            bonusXp = 0,
            completedMissionIds = emptyList(),
        )

    private fun hint(
        task: ServedTask,
        index: Int,
        locale: String?,
    ) = OpenedHint(
        index = index,
        text = localized(locale, task.hints[index - 1], locale?.let { task.translations[it] }?.hints?.getOrNull(index - 1)),
        remaining = task.hints.size - index,
    )

    private fun localized(
        locale: String?,
        cs: String,
        secondary: String?,
    ) = LocalizedText(cs, if (locale != null && secondary != null) LocalizedTextSecondary(locale, secondary) else null)

    private fun rank(rank: cz.doucko.practice.domain.Rank) = Rank(rank.index, rank.name, rank.minXp)
}
