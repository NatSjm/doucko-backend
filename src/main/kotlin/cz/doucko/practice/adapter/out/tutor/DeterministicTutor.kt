package cz.doucko.practice.adapter.out.tutor

import cz.doucko.checking.AnswerType
import cz.doucko.practice.application.Feedback
import cz.doucko.practice.application.RevealedAnswer
import cz.doucko.practice.application.TutorInput
import cz.doucko.practice.application.TutorPort
import cz.doucko.practice.domain.RawAnswer
import cz.doucko.practice.domain.TaskState
import org.springframework.stereotype.Component

/**
 * The MVP tutor (FR-44, TC-6): a pure function of the task, its opened hints and its state. It points to
 * the next unopened hint whenever one exists, and once the task is `REVEALED` it reveals every part: the
 * first accepted answer as the admin entered it, or the correct option keys of a `CHOICE_*` part. Same
 * input, same feedback; no ids, no timestamps, no network.
 */
@Component
class DeterministicTutor : TutorPort {
    override fun feedback(input: TutorInput): Feedback =
        Feedback(
            nextHintIndex = if (input.hintsOpened < input.hintsTotal) input.hintsOpened + 1 else null,
            revealedAnswers =
                if (input.state != TaskState.REVEALED) {
                    null
                } else {
                    input.parts.map { part ->
                        val choice = part.spec.type == AnswerType.CHOICE_SINGLE || part.spec.type == AnswerType.CHOICE_MULTI
                        RevealedAnswer(
                            part.key,
                            if (choice) RawAnswer.Keys(part.spec.accepted) else RawAnswer.Text(part.spec.accepted.first()),
                        )
                    }
                },
        )
}
