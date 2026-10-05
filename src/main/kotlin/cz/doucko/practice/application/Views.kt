package cz.doucko.practice.application

import cz.doucko.checking.Verdict
import cz.doucko.content.ServedTask
import cz.doucko.practice.domain.MissionState
import cz.doucko.practice.domain.Rank
import cz.doucko.practice.domain.TaskState
import java.util.UUID

/** A mission card on HQ (contract `MissionCard`). */
data class MissionCardView(
    val missionId: UUID,
    val position: Int,
    val taskCount: Int,
    val resolvedCount: Int,
    val state: MissionState,
)

/** A topic heading on HQ with its missions by position. */
data class TopicView(
    val topicId: UUID,
    val name: String,
    val missions: List<MissionCardView>,
)

/** HQ (contract `HqView`); XP and ranks are the slice-8 placeholders (design decision 7). */
data class HqData(
    val displayName: String,
    val totalXp: Int,
    val solvedTasks: Int,
    val rank: Rank,
    val nextRank: Rank?,
    val continueTarget: Pair<UUID, UUID>?,
    val topics: List<TopicView>,
)

/** One task of a briefing: its id, 1-based position and state in the mission's current run. */
data class BriefingTask(
    val taskId: UUID,
    val position: Int,
    val state: TaskState,
)

/** The mission briefing (contract `MissionBriefing`). */
data class BriefingData(
    val missionId: UUID,
    val position: Int,
    val topicName: String,
    val state: MissionState,
    val tasks: List<BriefingTask>,
)

/** One part of an attempt result (contract `PartResult`). */
data class PartOutcome(
    val key: String,
    val verdict: Verdict,
    val normalized: String?,
    val formatTip: String?,
    val locked: Boolean,
)

/** An attempt result (contract `AttemptResult`); [attemptNo] and [feedback] are null exactly when [graded] is false. */
data class AttemptOutcome(
    val graded: Boolean,
    val attemptNo: Int?,
    val parts: List<PartOutcome>,
    val state: TaskState,
    val attemptsLeft: Int,
    val feedback: Feedback?,
    val missionComplete: Boolean,
)

/**
 * The task screen (contract `TaskView`): the served task, its place in the mission, the profile's
 * translation locale (null when off), the scope's opened hints, attempts and the latest graded result.
 */
data class TaskScreen(
    val task: ServedTask,
    val missionId: UUID,
    val position: Int,
    val locale: String?,
    val hintsOpened: Int,
    val attemptsUsed: Int,
    val state: TaskState,
    val lastResult: AttemptOutcome?,
)

/** An opened hint (contract `OpenedHint`) with what the web layer needs to localize it. */
data class HintOpened(
    val task: ServedTask,
    val index: Int,
    val remaining: Int,
    val locale: String?,
)

/** The mission-complete data available before slice 8: counts only, no XP or rank (design decision 7). */
data class SummaryData(
    val missionId: UUID,
    val firstTryCount: Int,
    val taskCount: Int,
    val replay: Boolean,
)
