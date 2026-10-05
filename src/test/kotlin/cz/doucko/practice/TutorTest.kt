package cz.doucko.practice

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import cz.doucko.checking.AnswerSpec
import cz.doucko.checking.AnswerType
import cz.doucko.content.ServedOption
import cz.doucko.content.ServedPart
import cz.doucko.practice.adapter.out.tutor.DeterministicTutor
import cz.doucko.practice.application.Feedback
import cz.doucko.practice.application.PracticeService
import cz.doucko.practice.application.RevealedAnswer
import cz.doucko.practice.application.TutorInput
import cz.doucko.practice.application.TutorPort
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.TaskState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** The tutor seam (FR-44): deterministic MVP feedback, built only by `TutorPort`, no AI anywhere (TC-6). */
class TutorTest {
    private val tutor = DeterministicTutor()

    private val parts =
        listOf(
            ServedPart("a", "a", AnswerSpec(AnswerType.DECIMAL, listOf("0,50", "0.5")), null, emptyList()),
            ServedPart(
                "b",
                "b",
                AnswerSpec(AnswerType.CHOICE_MULTI, listOf("A", "C"), options = listOf("A", "B", "C")),
                null,
                listOf(ServedOption("A", "jedna"), ServedOption("B", "dvě"), ServedOption("C", "tři")),
            ),
        )

    private val main =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("cz.doucko")

    // @trace FR-44
    @Test
    fun `a wrong attempt points to the next unopened hint and reveals nothing`() {
        tutor.feedback(TutorInput(parts, hintsTotal = 2, hintsOpened = 0, state = TaskState.UNRESOLVED)) shouldBe Feedback(1, null)
        tutor.feedback(TutorInput(parts, hintsTotal = 2, hintsOpened = 1, state = TaskState.UNRESOLVED)) shouldBe Feedback(2, null)
        tutor.feedback(TutorInput(parts, hintsTotal = 2, hintsOpened = 2, state = TaskState.UNRESOLVED)).nextHintIndex.shouldBeNull()
        tutor.feedback(TutorInput(parts, hintsTotal = 0, hintsOpened = 0, state = TaskState.CORRECT_FIRST_TRY)) shouldBe
            Feedback(null, null)
    }

    // @trace FR-44
    @Test
    fun `the reveal shows the first accepted answer as entered and the correct option keys`() {
        tutor.feedback(TutorInput(parts, 1, 1, TaskState.REVEALED)).revealedAnswers shouldBe
            listOf(RevealedAnswer("a", RawAnswer.Text("0,50")), RevealedAnswer("b", RawAnswer.Keys(listOf("A", "C"))))
    }

    // @trace FR-44
    @Test
    fun `same input, same feedback`() {
        val input = TutorInput(parts, 2, 1, TaskState.UNRESOLVED)
        tutor.feedback(input) shouldBe tutor.feedback(input.copy())
    }

    // @trace FR-44
    @Test
    fun `feedback is built only by the one TutorPort implementation, and the attempt use case asks TutorPort`() {
        val implementations = main.filter { c -> c.isAssignableTo(TutorPort::class.java) && !c.isInterface }.map(JavaClass::getName)
        implementations shouldContainExactly listOf(DeterministicTutor::class.java.name)
        val builders =
            main
                .flatMap { c -> c.constructorCallsFromSelf.filter { it.targetOwner.isEquivalentTo(Feedback::class.java) }.map { c.name } }
                .distinct()
                // Feedback's own generated copy() is not a builder.
                .filter { it != Feedback::class.java.name }
        builders shouldContainExactly listOf(DeterministicTutor::class.java.name)
        val service = main.get(PracticeService::class.java)
        service.methodCallsFromSelf.any { it.targetOwner.isEquivalentTo(TutorPort::class.java) && it.name == "feedback" } shouldBe true
    }

    // @trace FR-44
    @Test
    fun `no AI or LLM library is a dependency (TC-6)`() {
        val pom = File("pom.xml").readText()
        for (group in listOf("com.openai", "dev.langchain4j", "org.springframework.ai")) {
            pom.contains("<groupId>$group") shouldBe false
            javaClass.classLoader.getResource(group.replace('.', '/')).shouldBeNull()
        }
    }
}
