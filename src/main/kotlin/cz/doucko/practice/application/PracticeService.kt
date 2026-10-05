package cz.doucko.practice.application

import cz.doucko.checking.Verdict
import cz.doucko.content.ServedContent
import cz.doucko.content.ServedImage
import cz.doucko.content.ServedMission
import cz.doucko.content.ServedTask
import cz.doucko.practice.domain.AttemptRecord
import cz.doucko.practice.domain.GradablePart
import cz.doucko.practice.domain.Grade
import cz.doucko.practice.domain.Grading
import cz.doucko.practice.domain.HintRecord
import cz.doucko.practice.domain.MissionProgress
import cz.doucko.practice.domain.MissionState
import cz.doucko.practice.domain.PlaceholderLadder
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.ReportInput
import cz.doucko.practice.domain.ReportRecord
import cz.doucko.practice.domain.RunRecord
import cz.doucko.practice.domain.Scope
import cz.doucko.practice.domain.TaskProgress
import cz.doucko.practice.domain.TaskState
import cz.doucko.profile.ChildProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The child's practice (store spec practice-flow; FR-22, FR-24..FR-31, FR-42, FR-44). Content is read only
 * through `ServedContent` (FR-12): a mission that is not served, or a task that is not served or not in the
 * mission of the URL, is [PracticeNotFound] and nothing is stored. State lives per task and scope (design
 * decisions 1 and 2); every write takes the practice lock first and re-reads the state under it.
 */
@Service
@Transactional(readOnly = true)
class PracticeService(
    private val served: ServedContent,
    private val profile: ChildProfile,
    private val store: PracticeStore,
    private val tutor: TutorPort,
) {
    /** The scope a mission is practised in now: an unfinished replay run, else the primary scope. */
    private data class Run(
        val scope: UUID,
        val replay: Boolean,
    )

    fun hq(): HqData {
        val missions = served.missions()
        val taskIds = missions.flatMap { it.taskIds }.distinct()
        val attempts = store.attempts(Scope.PRIMARY, taskIds)
        val hints = store.hints(Scope.PRIMARY, taskIds)
        val states = attempts.groupBy { it.taskId }.mapValues { (_, list) -> TaskProgress(list).state }
        val active = attempts.map { it.taskId }.toSet() + hints.map { it.taskId }
        val lastActivity =
            (attempts.map { it.missionId to it.createdAt } + hints.map { it.missionId to it.openedAt })
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, times) -> times.max() }
        val progress = missions.associate { it.missionId to MissionProgress.of(it.taskIds, states, active) }
        val target =
            missions
                .filter { progress.getValue(it.missionId).state == MissionState.IN_PROGRESS }
                .sortedWith(compareByDescending<ServedMission, Instant?>(nullsFirst()) { lastActivity[it.missionId] })
                .firstOrNull()
                ?.let { it.missionId to progress.getValue(it.missionId).firstUnresolved!! }
        val rank = PlaceholderLadder.rankFor(0)
        return HqData(
            displayName = profile.displayName(),
            totalXp = 0,
            solvedTasks = states.count { it.value.resolved },
            rank = rank,
            nextRank = PlaceholderLadder.nextAfter(rank),
            continueTarget = target,
            topics =
                missions
                    .groupBy { it.topicId }
                    .map { (topicId, list) ->
                        TopicView(
                            topicId,
                            list.first().topicName,
                            list.sortedBy { it.position }.map { m ->
                                val p = progress.getValue(m.missionId)
                                MissionCardView(m.missionId, m.position, p.taskCount, p.resolvedCount, p.state)
                            },
                        )
                    },
        )
    }

    fun briefing(missionId: UUID): BriefingData {
        val mission = mission(missionId)
        val run = currentRun(mission)
        val states = states(run.scope, mission.taskIds)
        return BriefingData(
            missionId = mission.missionId,
            position = mission.position,
            topicName = mission.topicName,
            state = primaryProgress(mission).state,
            tasks = mission.taskIds.mapIndexed { i, id -> BriefingTask(id, i + 1, states[id] ?: TaskState.UNRESOLVED) },
        )
    }

    fun task(
        missionId: UUID,
        taskId: UUID,
    ): TaskScreen {
        val mission = mission(missionId)
        return screen(mission, task(mission, taskId), currentRun(mission))
    }

    /** The first unresolved task of the mission's current run, or null when every task is resolved (resume, FR-31). */
    fun currentTask(missionId: UUID): TaskScreen? {
        val mission = mission(missionId)
        val run = currentRun(mission)
        val states = states(run.scope, mission.taskIds)
        val next = mission.taskIds.firstOrNull { states[it]?.resolved != true } ?: return null
        return screen(mission, task(mission, next), run)
    }

    /**
     * Grades and stores one attempt (design decision 2). Order: 404, request shape (400), already resolved
     * in the current run (409), any unreadable non-locked part (UNPARSEABLE, nothing stored), else stored.
     */
    @Transactional
    fun submit(
        missionId: UUID,
        taskId: UUID,
        answers: Map<String, RawAnswer>,
    ): AttemptOutcome {
        val mission = mission(missionId)
        val task = task(mission, taskId)
        val parts = task.parts.map { GradablePart(it.key, it.spec) }
        Grading.shapeErrors(parts, answers).takeIf { it.isNotEmpty() }?.let { throw InvalidPracticeInput(it) }
        store.lock()
        val run = currentRun(mission)
        val progress = TaskProgress(store.attempts(run.scope, listOf(taskId)))
        if (progress.state.resolved) throw PracticeConflict("resolved")
        return when (val grade = Grading.grade(parts, answers, progress.locked)) {
            is Grade.Unreadable -> {
                AttemptOutcome(
                    graded = false,
                    attemptNo = null,
                    parts = grade.parts.map { PartOutcome(it.key, Verdict.UNPARSEABLE, null, it.formatTip, false) },
                    state = progress.state,
                    attemptsLeft = progress.attemptsLeft,
                    feedback = null,
                    missionComplete = false,
                )
            }

            is Grade.Graded -> {
                val hintsOpened = store.hints(run.scope, listOf(taskId)).size
                val record =
                    AttemptRecord(
                        id = UUID.randomUUID(),
                        taskId = taskId,
                        missionId = mission.missionId,
                        scope = run.scope,
                        replay = run.replay,
                        attemptNo = progress.attemptsUsed + 1,
                        hintsUsed = hintsOpened,
                        correct = grade.correct,
                        parts = grade.parts,
                        createdAt = Instant.now(),
                    )
                store.insertAttempt(record)
                outcome(mission, task, run, TaskProgress(progress.attempts + record), hintsOpened)
            }
        }
    }

    /** Opens the next hint in order (FR-28), at any point; 409 when none is left. */
    @Transactional
    fun openHint(
        missionId: UUID,
        taskId: UUID,
    ): HintOpened {
        val mission = mission(missionId)
        val task = task(mission, taskId)
        store.lock()
        val run = currentRun(mission)
        val opened = store.hints(run.scope, listOf(taskId)).size
        if (opened >= task.hints.size) throw PracticeConflict("no-hint")
        val index = opened + 1
        store.insertHint(HintRecord(taskId, run.scope, index, mission.missionId, Instant.now()))
        return HintOpened(task, index, task.hints.size - index, profile.secondaryTaskLocale())
    }

    /** Stores a problem report with the latest attempt of the current run attached (FR-30). */
    @Transactional
    fun report(
        missionId: UUID,
        taskId: UUID,
        input: ReportInput,
    ) {
        val mission = mission(missionId)
        task(mission, taskId)
        store.lock()
        val run = currentRun(mission)
        val latest = TaskProgress(store.attempts(run.scope, listOf(taskId))).latest
        store.insertReport(ReportRecord(UUID.randomUUID(), taskId, mission.missionId, input.reason, input.note, latest?.id, Instant.now()))
    }

    /** The image of a served task (FR-26). */
    fun image(imageId: UUID): ServedImage = served.image(imageId) ?: throw PracticeNotFound()

    /**
     * Placeholder replay (slice 8 owns the rules; design decision 7): an unfinished replay run is returned
     * unchanged; a mission complete in the primary scope starts a new run; any other mission is 409.
     */
    @Transactional
    fun replay(missionId: UUID): TaskScreen {
        val mission = mission(missionId)
        store.lock()
        val current = currentRun(mission)
        val run =
            if (current.replay) {
                current
            } else {
                if (primaryProgress(mission).state != MissionState.COMPLETE) throw PracticeConflict("replay-not-complete")
                val created = RunRecord(UUID.randomUUID(), mission.missionId, Instant.now())
                store.insertRun(created)
                Run(created.id, replay = true)
            }
        val states = states(run.scope, mission.taskIds)
        val next = mission.taskIds.first { states[it]?.resolved != true }
        return screen(mission, task(mission, next), run)
    }

    /** Placeholder summary of the latest completed run (slice 8 adds XP and ranks); 409 when no run is complete. */
    fun summary(missionId: UUID): SummaryData {
        val mission = mission(missionId)
        val completedReplay =
            store.runs(mission.missionId).lastOrNull { run ->
                states(run.id, mission.taskIds).let { s -> mission.taskIds.all { s[it]?.resolved == true } }
            }
        val scope =
            completedReplay?.id
                ?: Scope.PRIMARY.takeIf { primaryProgress(mission).state == MissionState.COMPLETE }
                ?: throw PracticeConflict("summary-not-complete")
        val attempts = store.attempts(scope, mission.taskIds)
        val firstTry =
            attempts.count { it.attemptNo == 1 && it.correct && it.missionId == mission.missionId }
        return SummaryData(mission.missionId, firstTry, mission.taskIds.size, completedReplay != null)
    }

    private fun mission(missionId: UUID): ServedMission = served.mission(missionId) ?: throw PracticeNotFound()

    private fun task(
        mission: ServedMission,
        taskId: UUID,
    ): ServedTask {
        if (taskId !in mission.taskIds) throw PracticeNotFound()
        return served.task(taskId) ?: throw PracticeNotFound()
    }

    private fun currentRun(mission: ServedMission): Run {
        val run = store.runs(mission.missionId).lastOrNull() ?: return Run(Scope.PRIMARY, false)
        val states = states(run.id, mission.taskIds)
        return if (mission.taskIds.any { states[it]?.resolved != true }) Run(run.id, true) else Run(Scope.PRIMARY, false)
    }

    private fun states(
        scope: UUID,
        taskIds: List<UUID>,
    ): Map<UUID, TaskState> = store.attempts(scope, taskIds).groupBy { it.taskId }.mapValues { (_, list) -> TaskProgress(list).state }

    private fun primaryProgress(mission: ServedMission): MissionProgress {
        val attempts = store.attempts(Scope.PRIMARY, mission.taskIds)
        val hints = store.hints(Scope.PRIMARY, mission.taskIds)
        val states = attempts.groupBy { it.taskId }.mapValues { (_, list) -> TaskProgress(list).state }
        return MissionProgress.of(mission.taskIds, states, attempts.map { it.taskId }.toSet() + hints.map { it.taskId })
    }

    private fun screen(
        mission: ServedMission,
        task: ServedTask,
        run: Run,
    ): TaskScreen {
        val progress = TaskProgress(store.attempts(run.scope, listOf(task.taskId)))
        val hintsOpened = store.hints(run.scope, listOf(task.taskId)).size
        return TaskScreen(
            task = task,
            missionId = mission.missionId,
            position = mission.taskIds.indexOf(task.taskId) + 1,
            locale = profile.secondaryTaskLocale(),
            hintsOpened = hintsOpened,
            attemptsUsed = progress.attemptsUsed,
            state = progress.state,
            lastResult = if (progress.latest == null) null else outcome(mission, task, run, progress, hintsOpened),
        )
    }

    /** The result of the latest attempt in [progress]: per-part verdicts, state, and feedback from the tutor (FR-44). */
    private fun outcome(
        mission: ServedMission,
        task: ServedTask,
        run: Run,
        progress: TaskProgress,
        hintsOpened: Int,
    ): AttemptOutcome {
        val latest = checkNotNull(progress.latest)
        val complete =
            progress.state.resolved &&
                states(run.scope, mission.taskIds).let { s -> mission.taskIds.all { it == task.taskId || s[it]?.resolved == true } }
        return AttemptOutcome(
            graded = true,
            attemptNo = latest.attemptNo,
            parts = latest.parts.map { PartOutcome(it.key, it.verdict, it.normalized, null, it.locked) },
            state = progress.state,
            attemptsLeft = progress.attemptsLeft,
            feedback = tutor.feedback(TutorInput(task.parts, task.hints.size, hintsOpened, progress.state)),
            missionComplete = complete,
        )
    }
}
