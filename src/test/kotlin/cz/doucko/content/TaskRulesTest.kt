package cz.doucko.content

import cz.doucko.checking.AnswerType
import cz.doucko.content.domain.In
import cz.doucko.content.domain.Modality
import cz.doucko.content.domain.OptionDraft
import cz.doucko.content.domain.PartDraft
import cz.doucko.content.domain.Source
import cz.doucko.content.domain.SourceMetadata
import cz.doucko.content.domain.SourceRefDraft
import cz.doucko.content.domain.SpecDraft
import cz.doucko.content.domain.TaskContent
import cz.doucko.content.domain.TaskContext
import cz.doucko.content.domain.TaskDraft
import cz.doucko.content.domain.TaskRules
import cz.doucko.content.domain.TranslationDraft
import cz.doucko.content.domain.Validation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** content-authoring "Create a task with a source reference", "Field limits", parts, hints, translations (FR-3, FR-5..FR-8). */
class TaskRulesTest {
    private val sourceId = UUID.randomUUID()
    private val source = Source(sourceId, SourceMetadata("Sešit", null, "1", 6, "math"), 120, "sources/x.pdf")
    private val context = TaskContext(source, Topics.seeded[1], listOf(6), listOf("uk"))

    private fun ok(value: String) = In.Ok(value)

    private fun part(
        key: String,
        label: String = key,
        spec: In<SpecDraft> = In.Ok(SpecDraft(In.Ok(AnswerType.INTEGER), In.Ok(listOf(ok("12"))))),
    ) = In.Ok(PartDraft(ok(key), ok(label), spec))

    private fun draft(
        prompt: In<String> = ok("  Vypočítej obvod obdélníku.  "),
        grade: In<Int> = In.Ok(6),
        difficulty: In<Int> = In.Ok(2),
        modality: In<Modality> = In.Ok(Modality.TEXT),
        page: In<Int> = In.Ok(57),
        taskNo: In<String> = ok("4"),
        subPart: In<String> = ok("b"),
        parts: List<In<PartDraft>> = listOf(part("a")),
        hints: List<In<String>> = emptyList(),
        translations: In<Map<String, In<TranslationDraft>>> = In.Missing,
        topicId: In<UUID> = In.Ok(Topics.DECIMALS),
        sourceRefId: In<UUID> = In.Ok(sourceId),
    ) = TaskDraft(
        prompt = prompt,
        topicId = topicId,
        grade = grade,
        difficulty = difficulty,
        modality = modality,
        sourceRef = In.Ok(SourceRefDraft(sourceRefId, page, taskNo, subPart)),
        parts = In.Ok(parts),
        hints = In.Ok(hints),
        translations = translations,
    )

    private fun valid(
        draft: TaskDraft,
        ctx: TaskContext = context,
    ): TaskContent {
        val result = TaskRules.validate(draft, ctx)
        result.shouldBeInstanceOf<Validation.Valid<TaskContent>>()
        return result.value
    }

    private fun errors(
        draft: TaskDraft,
        ctx: TaskContext = context,
    ): List<Pair<String, String>> {
        val result = TaskRules.validate(draft, ctx)
        result.shouldBeInstanceOf<Validation.Invalid>()
        return result.errors.map { it.field to it.code }
    }

    // @trace FR-3
    @Test
    fun `a complete task is valid and trimmed`() {
        val task = valid(draft())
        task.prompt shouldBe "Vypočítej obvod obdélníku."
        task.sourceRef.page shouldBe 57
        task.sourceRef.taskNo shouldBe "4"
        task.sourceRef.subPart shouldBe "b"
        task.difficulty shouldBe 2
        task.parts
            .single()
            .spec.accepted shouldContainExactly listOf("12")
    }

    // @trace FR-3
    @Test
    fun `prompt limit is 4000 code points after trimming`() {
        valid(draft(prompt = ok(" " + "ž".repeat(4000) + " "))).prompt.length shouldBe 4000
        errors(draft(prompt = ok("a".repeat(4001)))) shouldContainExactly listOf("prompt" to "too-long")
        errors(draft(prompt = ok("   "))) shouldContainExactly listOf("prompt" to "required")
        errors(draft(prompt = In.Missing)) shouldContainExactly listOf("prompt" to "required")
        errors(draft(prompt = In.Invalid)) shouldContainExactly listOf("prompt" to "wrong-type")
    }

    // @trace FR-3
    @Test
    fun `difficulty must be a whole number from 1 to 3`() {
        for (value in listOf(In.Ok(4), In.Ok(0), In.Invalid, In.Missing)) {
            errors(draft(difficulty = value)) shouldContainExactly listOf("difficulty" to "difficulty")
        }
    }

    // @trace FR-3
    @Test
    fun `page must lie within the source's page count`() {
        for (value in listOf(In.Missing, In.Ok(121), In.Ok(0), In.Invalid)) {
            val result = TaskRules.validate(draft(page = value), context) as Validation.Invalid
            result.errors.map { it.field to it.code } shouldContainExactly listOf("sourceRef.page" to "page-range")
            result.errors.single().args shouldContainExactly listOf("1–120")
        }
        valid(draft(page = In.Ok(120)))
    }

    // @trace FR-3
    @Test
    fun `grade must be configured and equal the source grade`() {
        errors(draft(grade = In.Ok(7))) shouldContainExactly listOf("grade" to "grade-allowed")
        errors(draft(grade = In.Invalid)) shouldContainExactly listOf("grade" to "grade-allowed")
        errors(draft(grade = In.Ok(7)), context.copy(grades = listOf(6, 7))) shouldContainExactly listOf("grade" to "grade-source")
    }

    // @trace FR-3
    @Test
    fun `topic must be seeded and belong to the task's grade`() {
        errors(draft(topicId = In.Ok(Topics.GRADE7)), context.copy(topic = Topics.grade7, grades = listOf(6, 7))) shouldContainExactly
            listOf("topicId" to "topic-grade")
        errors(draft(topicId = In.Ok(UUID.randomUUID())), context.copy(topic = null)) shouldContainExactly
            listOf("topicId" to "topic-unknown")
        errors(draft(topicId = In.Invalid), context.copy(topic = null)) shouldContainExactly listOf("topicId" to "not-uuid")
    }

    // @trace FR-3
    @Test
    fun `source must exist, task number and sub-part obey their limits`() {
        errors(draft(), context.copy(source = null)) shouldContainExactly listOf("sourceRef.sourceId" to "source-unknown")
        errors(draft(taskNo = ok("1".repeat(21)))) shouldContainExactly listOf("sourceRef.taskNo" to "too-long")
        errors(draft(taskNo = ok(" "))) shouldContainExactly listOf("sourceRef.taskNo" to "required")
        errors(draft(subPart = ok("b".repeat(11)))) shouldContainExactly listOf("sourceRef.subPart" to "too-long")
        valid(draft(subPart = ok("  "))).sourceRef.subPart.shouldBeNull()
        valid(draft(subPart = In.Missing)).sourceRef.subPart.shouldBeNull()
    }

    // @trace FR-3
    @Test
    fun `a source id that is not a UUID is named as such, and without a source a page must be a whole number from 1`() {
        errors(draft(sourceRefId = In.Invalid), context.copy(source = null)) shouldContainExactly
            listOf("sourceRef.sourceId" to "not-uuid")
        errors(draft(sourceRefId = In.Missing, page = In.Ok(0)), context.copy(source = null)) shouldContainExactly
            listOf("sourceRef.sourceId" to "required", "sourceRef.page" to "page-whole")
        errors(draft(page = In.Invalid), context.copy(source = null)) shouldContainExactly
            listOf("sourceRef.sourceId" to "source-unknown", "sourceRef.page" to "page-whole")
        errors(draft(page = In.Ok(121))) shouldContainExactly listOf("sourceRef.page" to "page-range")
    }

    // @trace FR-5
    @Test
    fun `modality is required`() {
        errors(draft(modality = In.Missing)) shouldContainExactly listOf("modality" to "modality")
        errors(draft(modality = In.Invalid)) shouldContainExactly listOf("modality" to "modality")
    }

    // @trace FR-5
    // @trace FR-6
    @Test
    fun `a text task needs a part, a graphic task may have none`() {
        errors(draft(parts = emptyList())) shouldContainExactly listOf("parts" to "parts-text-min")
        valid(draft(modality = In.Ok(Modality.GRAPHIC), parts = emptyList())).parts shouldBe emptyList()
    }

    // @trace FR-6
    @Test
    fun `parts are 1 to 10 with unique keys and labels`() {
        errors(draft(parts = (1..11).map { part("p$it") })) shouldContainExactly listOf("parts" to "too-many")
        errors(draft(parts = listOf(part("a"), part("b", label = "a")))) shouldContainExactly listOf("parts[1].label" to "duplicate")
        errors(draft(parts = listOf(part("a"), part("a", label = "b")))) shouldContainExactly listOf("parts[1].key" to "duplicate")
        errors(draft(parts = listOf(part("A")))) shouldContainExactly listOf("parts[0].key" to "part-key")
        errors(draft(parts = listOf(part("a", label = " ")))) shouldContainExactly listOf("parts[0].label" to "required")
        errors(draft(parts = listOf(part("a", label = "a".repeat(11))))) shouldContainExactly listOf("parts[0].label" to "too-long")
        errors(draft(parts = listOf(part("a", spec = In.Missing)))) shouldContainExactly listOf("parts[0].spec" to "required")
        valid(draft(parts = (1..10).map { part("p$it") })).parts.map { it.key } shouldBe (1..10).map { "p$it" }
    }

    // @trace FR-6
    @Test
    fun `a half-filled part is refused on its missing field`() {
        val noValue = part("b", spec = In.Ok(SpecDraft(type = In.Ok(AnswerType.DECIMAL))))
        errors(draft(parts = listOf(part("a"), noValue))) shouldContainExactly listOf("parts[1].spec.accepted" to "accepted-count")
        val noType = part("b", spec = In.Ok(SpecDraft(accepted = In.Ok(listOf(ok("1,5"))))))
        errors(draft(parts = listOf(part("a"), noType))) shouldContainExactly listOf("parts[1].spec.type" to "spec-type")
    }

    // @trace FR-7
    @Test
    fun `hints are ordered, non-empty, at most 10 of at most 1000 code points`() {
        valid(draft(hints = listOf(ok(" třetí "), ok("první")))).hints shouldContainExactly listOf("třetí", "první")
        errors(draft(hints = listOf(ok("   ")))) shouldContainExactly listOf("hints[0]" to "required")
        errors(draft(hints = listOf(ok("x"), ok("a".repeat(1001))))) shouldContainExactly listOf("hints[1]" to "too-long")
        errors(draft(hints = (1..11).map { ok("h$it") })) shouldContainExactly listOf("hints" to "too-many")
    }

    private fun uk(
        prompt: In<String> = In.Missing,
        labels: Map<String, String> = emptyMap(),
        hints: List<String?> = emptyList(),
    ) = In.Ok(
        mapOf(
            "uk" to
                In.Ok(
                    TranslationDraft(
                        prompt,
                        In.Ok(labels.mapValues { ok(it.value) }),
                        In.Ok(hints.map { it?.let(::ok) ?: In.Missing }),
                    ),
                ),
        ),
    )

    // @trace FR-8
    @Test
    fun `translations are optional, partial, and obey the Czech limits`() {
        val partial = valid(draft(hints = listOf(ok("h1"), ok("h2")), translations = uk(prompt = ok("Обчисли периметр."))))
        partial.translations.getValue("uk").prompt shouldBe "Обчисли периметр."
        partial.translations.getValue("uk").hints shouldBe emptyList()
        valid(draft(translations = uk())).translations shouldBe emptyMap()
        errors(draft(translations = uk(prompt = ok("a".repeat(4001))))) shouldContainExactly
            listOf("translations.uk.prompt" to "too-long")
        errors(draft(translations = uk(labels = mapOf("z" to "а")))) shouldContainExactly
            listOf("translations.uk.partLabels.z" to "translation-key")
        errors(draft(translations = uk(labels = mapOf("a" to "а".repeat(11))))) shouldContainExactly
            listOf("translations.uk.partLabels.a" to "too-long")
        errors(draft(hints = listOf(ok("h1")), translations = uk(hints = listOf("x", "y")))) shouldContainExactly
            listOf("translations.uk.hints" to "translation-hints")
        valid(draft(hints = listOf(ok("h1"), ok("h2")), translations = uk(hints = listOf(null, "друга"))))
            .translations
            .getValue("uk")
            .hints shouldContainExactly listOf(null, "друга")
    }

    // @trace FR-8
    @Test
    fun `an unsupported locale is refused`() {
        val de = In.Ok(mapOf("de" to In.Ok(TranslationDraft(prompt = ok("Berechne.")))))
        val result = TaskRules.validate(draft(translations = de), context) as Validation.Invalid
        result.errors.map { it.field to it.code } shouldContainExactly listOf("translations.de" to "locale")
        result.errors.single().args shouldContainExactly listOf("uk")
    }

    // @trace FR-11
    @Test
    fun `a search query is trimmed, blank means no filter, over 200 is refused`() {
        TaskRules.query(" obdelnik ") shouldBe Validation.Valid("obdelnik")
        TaskRules.query("   ") shouldBe Validation.Valid(null)
        TaskRules.query(null) shouldBe Validation.Valid(null)
        TaskRules.query(" " + "a".repeat(200) + " ") shouldBe Validation.Valid("a".repeat(200))
        (TaskRules.query("a".repeat(201)) as Validation.Invalid).errors.map { it.field to it.code } shouldContainExactly
            listOf("q" to "too-long")
    }

    // @trace FR-3
    // @trace FR-6
    // @trace FR-7
    // @trace FR-8
    @Test
    fun `wrong JSON types and missing structures are refused on their path`() {
        errors(draft().copy(sourceRef = In.Missing)) shouldContainExactly listOf("sourceRef" to "required")
        errors(draft().copy(sourceRef = In.Invalid)) shouldContainExactly listOf("sourceRef" to "wrong-type")
        val noSourceId = draft().copy(sourceRef = In.Ok(SourceRefDraft(In.Missing, In.Ok(1), ok("1"), In.Missing)))
        errors(noSourceId) shouldContainExactly listOf("sourceRef.sourceId" to "required")
        errors(draft(topicId = In.Missing)) shouldContainExactly listOf("topicId" to "required")
        errors(draft().copy(parts = In.Missing)) shouldContainExactly listOf("parts" to "required")
        errors(draft().copy(parts = In.Invalid)) shouldContainExactly listOf("parts" to "wrong-type")
        errors(draft(parts = listOf(In.Invalid))) shouldContainExactly listOf("parts[0]" to "wrong-type")
        errors(draft(parts = listOf(part("a", spec = In.Invalid)))) shouldContainExactly listOf("parts[0].spec" to "wrong-type")
        errors(draft().copy(hints = In.Missing)) shouldContainExactly listOf("hints" to "required")
        errors(draft().copy(hints = In.Invalid)) shouldContainExactly listOf("hints" to "wrong-type")
        errors(draft(hints = listOf(In.Invalid))) shouldContainExactly listOf("hints[0]" to "wrong-type")
        errors(draft(translations = In.Invalid)) shouldContainExactly listOf("translations" to "wrong-type")
        errors(draft(translations = In.Ok(mapOf("uk" to In.Invalid)))) shouldContainExactly listOf("translations.uk" to "wrong-type")
        valid(draft(translations = In.Ok(mapOf("uk" to In.Missing)))).translations shouldBe emptyMap()
        val badMaps = TranslationDraft(ok("x"), In.Invalid, In.Invalid, In.Invalid)
        errors(draft(translations = In.Ok(mapOf("uk" to In.Ok(badMaps))))) shouldContainExactly
            listOf(
                "translations.uk.partLabels" to "wrong-type",
                "translations.uk.options" to "wrong-type",
                "translations.uk.hints" to "wrong-type",
            )
        val noLocales = TaskContext(source, Topics.seeded[1], listOf(6), emptyList())
        val refused = TaskRules.validate(draft(translations = uk(prompt = ok("x"))), noLocales) as Validation.Invalid
        refused.errors.single().args shouldContainExactly listOf("none")
    }

    // @trace FR-8
    @Test
    fun `translated option texts are keyed by part and option`() {
        val options =
            listOf(In.Ok(OptionDraft(ok("A"), ok("tři"))), In.Ok(OptionDraft(ok("B"), ok("čtyři"))))
        val choice =
            part("a", spec = In.Ok(SpecDraft(In.Ok(AnswerType.CHOICE_SINGLE), In.Ok(listOf(ok("A"))), options = In.Ok(options))))

        fun withOptions(texts: Map<String, String>) =
            draft(
                parts = listOf(choice),
                translations = In.Ok(mapOf("uk" to In.Ok(TranslationDraft(options = In.Ok(texts.mapValues { ok(it.value) }))))),
            )
        valid(withOptions(mapOf("a.B" to "чотири"))).translations.getValue("uk").options shouldBe mapOf("a.B" to "чотири")
        errors(withOptions(mapOf("a.C" to "x"))) shouldContainExactly listOf("translations.uk.options.a.C" to "translation-key")
        errors(withOptions(mapOf("a.A" to "x".repeat(201)))) shouldContainExactly listOf("translations.uk.options.a.A" to "too-long")
    }
}
