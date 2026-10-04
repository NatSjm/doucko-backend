package cz.doucko.content

import cz.doucko.checking.AnswerType
import cz.doucko.content.domain.Blockers
import cz.doucko.content.domain.FieldError
import cz.doucko.content.domain.In
import cz.doucko.content.domain.Mission
import cz.doucko.content.domain.MissionContent
import cz.doucko.content.domain.MissionContext
import cz.doucko.content.domain.MissionDraft
import cz.doucko.content.domain.MissionRules
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.Part
import cz.doucko.content.domain.PublishStatus
import cz.doucko.content.domain.Refusal
import cz.doucko.content.domain.SourceRef
import cz.doucko.content.domain.Spec
import cz.doucko.content.domain.Task
import cz.doucko.content.domain.TaskContent
import cz.doucko.content.domain.Validation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Mission field rules and the state rules that keep published missions valid (content-authoring
 * "Missions", "Mission publishing rules", "Delete only unused content"; FR-9, FR-56, FR-57).
 */
class MissionRulesTest {
    private val decimals = Topics.seeded.first { it.id == Topics.DECIMALS }
    private val angles = Topics.seeded.first { it.id == Topics.ANGLES }
    private val topics = (Topics.seeded + Topics.grade7).associateBy { it.id }

    private fun task(
        topic: UUID = Topics.DECIMALS,
        status: PublishStatus = PublishStatus.PUBLISHED,
        modality: Modality = Modality.TEXT,
        grade: Int = 6,
    ) = Task(
        UUID.randomUUID(),
        TaskContent(
            prompt = "Sečti.",
            topicId = topic,
            grade = grade,
            difficulty = 1,
            modality = modality,
            sourceRef = SourceRef(UUID.randomUUID(), 57, "4", "b"),
            parts = listOf(Part("a", "a", Spec(AnswerType.INTEGER, listOf("12")))),
            hints = emptyList(),
            translations = emptyMap(),
        ),
        status,
        emptyList(),
    )

    private fun draft(
        topic: In<UUID> = In.Ok(Topics.DECIMALS),
        position: In<Int> = In.Ok(1),
        ids: List<In<UUID>> = emptyList(),
    ) = MissionDraft(topic, position, In.Ok(ids))

    private fun context(
        tasks: List<Task> = emptyList(),
        taken: Set<Int> = emptySet(),
        topic: UUID? = Topics.DECIMALS,
    ) = MissionContext(topic?.let(topics::get), taken, tasks.associateBy { it.id }, topics)

    private fun errors(
        draft: MissionDraft,
        context: MissionContext,
    ): List<FieldError> = (MissionRules.validate(draft, context) as Validation.Invalid).errors

    private fun mission(
        tasks: List<Task>,
        status: PublishStatus = PublishStatus.DRAFT,
        topic: UUID = Topics.DECIMALS,
    ) = Mission(UUID.randomUUID(), MissionContent(topic, 1, tasks.map { it.id }), status)

    // @trace FR-9
    @Test
    fun `a mission keeps its topic, position and task order`() {
        val tasks = List(10) { task() }
        val result = MissionRules.validate(draft(ids = tasks.reversed().map { In.Ok(it.id) }), context(tasks))
        result shouldBe Validation.Valid(MissionContent(Topics.DECIMALS, 1, tasks.reversed().map { it.id }))
    }

    // @trace FR-9
    @Test
    fun `any count from 0 to 50 is valid, 51 is refused naming the maximum`() {
        for (n in listOf(0, 8, 12, 50)) {
            val tasks = List(n) { task() }
            MissionRules.validate(draft(ids = tasks.map { In.Ok(it.id) }), context(tasks)).shouldBeInstanceOf<Validation.Valid<*>>()
        }
        val tasks = List(51) { task() }
        errors(draft(ids = tasks.map { In.Ok(it.id) }), context(tasks)) shouldBe
            listOf(FieldError("taskIds", "mission-tasks-max", listOf(50)))
    }

    // @trace FR-9
    @Test
    fun `position is a whole number from 1 to 999, unique within the topic`() {
        for (position in listOf<In<Int>>(In.Missing, In.Invalid, In.Ok(0), In.Ok(1000), In.Ok(-1))) {
            errors(draft(position = position), context()) shouldBe listOf(FieldError("position", "position"))
        }
        errors(draft(position = In.Ok(1)), context(taken = setOf(1))) shouldBe listOf(FieldError("position", "position-taken"))
        MissionRules.validate(draft(position = In.Ok(999)), context(taken = setOf(1))).shouldBeInstanceOf<Validation.Valid<*>>()
    }

    // @trace FR-9
    @Test
    fun `the topic is required and must exist`() {
        errors(draft(topic = In.Missing), context(topic = null)) shouldBe listOf(FieldError("topicId", "required"))
        errors(draft(topic = In.Invalid), context(topic = null)) shouldBe listOf(FieldError("topicId", "not-uuid"))
        errors(draft(topic = In.Ok(UUID.randomUUID())), context(topic = null)) shouldBe listOf(FieldError("topicId", "topic-unknown"))
    }

    // @trace FR-9
    @Test
    fun `task ids must be well formed, known, of the mission topic and listed once`() {
        val t = task()
        val other = task(topic = Topics.ANGLES)
        val unknown = UUID.randomUUID()
        errors(
            draft(ids = listOf(In.Ok(t.id), In.Invalid, In.Ok(unknown), In.Ok(t.id), In.Ok(other.id))),
            context(listOf(t, other)),
        ) shouldBe
            listOf(
                FieldError("taskIds[1]", "not-uuid"),
                FieldError("taskIds[2]", "task-unknown"),
                FieldError("taskIds[3]", "task-repeated"),
                FieldError("taskIds[4]", "task-topic", listOf(angles.name, decimals.name)),
            )
        errors(MissionDraft(In.Ok(Topics.DECIMALS), In.Ok(1), In.Missing), context()) shouldBe listOf(FieldError("taskIds", "required"))
        errors(MissionDraft(In.Ok(Topics.DECIMALS), In.Ok(1), In.Invalid), context()) shouldBe listOf(FieldError("taskIds", "wrong-type"))
    }

    // @trace FR-9
    @Test
    fun `every invalid field is reported at once`() {
        errors(MissionDraft(In.Missing, In.Ok(0), In.Missing), context(topic = null)).map { it.field } shouldBe
            listOf("topicId", "position", "taskIds")
    }

    // @trace FR-57
    @Test
    fun `blockers are the draft and the graphic tasks`() {
        val ok = task()
        val draft = task(status = PublishStatus.DRAFT)
        val graphic = task(modality = Modality.GRAPHIC)
        val both = task(status = PublishStatus.DRAFT, modality = Modality.GRAPHIC)
        MissionRules.blockers(listOf(ok, draft, graphic, both)) shouldBe Blockers(listOf(draft, both), listOf(graphic, both))
        MissionRules.blockers(listOf(ok)).isEmpty() shouldBe true
    }

    // @trace FR-57
    @Test
    fun `only a non-empty mission of published text tasks can be published`() {
        MissionRules.publish(emptyList()) shouldBe Refusal.EmptyMission
        val draft = task(status = PublishStatus.DRAFT)
        MissionRules.publish(List(9) { task() } + draft) shouldBe Refusal.Unpublishable(Blockers(listOf(draft), emptyList()))
        val graphic = task(modality = Modality.GRAPHIC)
        MissionRules.publish(listOf(task(), graphic)) shouldBe Refusal.Unpublishable(Blockers(emptyList(), listOf(graphic)))
        MissionRules.publish(List(10) { task() }) shouldBe null
    }

    // @trace FR-57
    @Test
    fun `the topic of a mission changes only while it is empty`() {
        val t = task()
        MissionRules.topicChange(mission(listOf(t)), Topics.ANGLES) shouldBe Refusal.TopicLocked
        MissionRules.topicChange(mission(listOf(t), PublishStatus.PUBLISHED), Topics.ANGLES) shouldBe Refusal.TopicLocked
        MissionRules.topicChange(mission(listOf(t)), Topics.DECIMALS) shouldBe null
        MissionRules.topicChange(mission(emptyList()), Topics.ANGLES) shouldBe null
    }

    // @trace FR-57
    @Test
    fun `a published mission keeps at least one task and only admits published text tasks`() {
        val kept = List(3) { task() }
        val published = mission(kept, PublishStatus.PUBLISHED)
        val draft = task(status = PublishStatus.DRAFT)
        val graphic = task(modality = Modality.GRAPHIC)
        val valid = task()
        val all = (kept + draft + graphic + valid).associateBy { it.id }

        fun edit(ids: List<Task>) = MissionRules.edit(published, published.content.copy(taskIds = ids.map { it.id }), all)
        edit(emptyList()) shouldBe Refusal.LastTask
        edit(kept + draft) shouldBe Refusal.CannotJoin(Blockers(listOf(draft), emptyList()))
        edit(kept + graphic) shouldBe Refusal.CannotJoin(Blockers(emptyList(), listOf(graphic)))
        edit(kept + valid) shouldBe null
        edit(kept.reversed()) shouldBe null
        edit(kept.drop(2)) shouldBe null
        val draftMission = mission(kept)
        MissionRules.edit(draftMission, draftMission.content.copy(taskIds = listOf(draft.id, graphic.id)), all) shouldBe null
        MissionRules.edit(draftMission, draftMission.content.copy(taskIds = emptyList()), all) shouldBe null
    }

    // @trace FR-57
    @Test
    fun `a task in a published mission cannot return to draft`() {
        val t = task()
        val a = mission(listOf(t))
        val b = mission(listOf(t), PublishStatus.PUBLISHED)
        MissionRules.taskStatus(PublishStatus.DRAFT, listOf(a, b)) shouldBe Refusal.TaskDraftInPublished(listOf(b))
        MissionRules.taskStatus(PublishStatus.DRAFT, listOf(a)) shouldBe null
        MissionRules.taskStatus(PublishStatus.PUBLISHED, listOf(a, b)) shouldBe null
        MissionRules.taskStatus(PublishStatus.DRAFT, emptyList()) shouldBe null
    }

    // @trace FR-57
    @Test
    fun `a task in a mission keeps its topic and grade, and in a published mission stays text`() {
        val t = task()
        val a = mission(listOf(t))
        val b = mission(listOf(t), PublishStatus.PUBLISHED)
        val c = t.content
        MissionRules.taskEdit(c, c.copy(modality = Modality.GRAPHIC, parts = emptyList()), listOf(a, b)) shouldBe
            Refusal.TaskGraphicInPublished(listOf(b))
        MissionRules.taskEdit(c, c.copy(modality = Modality.GRAPHIC), listOf(a)) shouldBe null
        MissionRules.taskEdit(c, c.copy(topicId = Topics.ANGLES), listOf(a, b)) shouldBe Refusal.TaskTopicInMissions(listOf(a, b))
        MissionRules.taskEdit(c, c.copy(topicId = Topics.ANGLES), listOf(a)) shouldBe Refusal.TaskTopicInMissions(listOf(a))
        MissionRules.taskEdit(c, c.copy(grade = 7, topicId = Topics.GRADE7), listOf(a)) shouldBe Refusal.TaskTopicInMissions(listOf(a))
        MissionRules.taskEdit(c, c.copy(topicId = Topics.ANGLES, modality = Modality.GRAPHIC), emptyList()) shouldBe null
        MissionRules.taskEdit(c, c.copy(prompt = "Odečti.", difficulty = 3), listOf(a, b)) shouldBe null
    }

    // @trace FR-56
    @Test
    fun `only an unused task outside published missions can be deleted`() {
        val t = task()
        val a = mission(listOf(t))
        val b = mission(listOf(t), PublishStatus.PUBLISHED)
        MissionRules.taskDelete(t.id, listOf(a, b), used = false) shouldBe Refusal.TaskDeleteInPublished(listOf(b))
        MissionRules.taskDelete(t.id, listOf(a), used = true) shouldBe Refusal.Used(listOf(t.id))
        MissionRules.taskDelete(t.id, listOf(a), used = false) shouldBe null
        MissionRules.taskDelete(t.id, emptyList(), used = false) shouldBe null
    }

    // @trace FR-56
    @Test
    fun `a mission with a used task cannot be deleted`() {
        val used = UUID.randomUUID()
        MissionRules.missionDelete(setOf(used)) shouldBe Refusal.Used(listOf(used))
        MissionRules.missionDelete(emptySet()) shouldBe null
    }
}
