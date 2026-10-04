package cz.doucko.profile

import cz.doucko.profile.domain.AnswerMode
import cz.doucko.profile.domain.Change
import cz.doucko.profile.domain.FieldError
import cz.doucko.profile.domain.FieldProblem
import cz.doucko.profile.domain.JsonField
import cz.doucko.profile.domain.ProfileSettings
import cz.doucko.profile.domain.SettingsInput
import cz.doucko.profile.domain.SettingsPatch
import cz.doucko.profile.domain.SettingsValidator
import cz.doucko.profile.domain.Validation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/** child-profile partial-update semantics and "Invalid settings are refused without side effects". */
class SettingsValidatorTest {
    private val validator = SettingsValidator(setOf("uk"))

    private fun valid(input: SettingsInput) = (validator.validate(input) as Validation.Valid).patch

    private fun errors(input: SettingsInput) = (validator.validate(input) as Validation.Invalid).errors

    private val locale = "secondaryTaskLocale"
    private val mode = "answerMode"

    // @trace FR-43
    @Test
    fun `absent fields keep, null clears the locale, values set`() {
        valid(SettingsInput()) shouldBe SettingsPatch(Change.Keep, Change.Keep)
        valid(SettingsInput(secondaryTaskLocale = JsonField.Null)) shouldBe SettingsPatch(Change.To(null), Change.Keep)
        valid(SettingsInput(JsonField.Text("uk"), JsonField.Text("DIRECT"))) shouldBe
            SettingsPatch(Change.To("uk"), Change.To(AnswerMode.DIRECT))
        valid(SettingsInput(answerMode = JsonField.Text("GUIDED"))) shouldBe SettingsPatch(Change.Keep, Change.To(AnswerMode.GUIDED))
    }

    // @trace FR-43
    @Test
    fun `a patch changes only what it sets`() {
        val stored = ProfileSettings("uk", AnswerMode.DIRECT)
        SettingsPatch().applyTo(stored) shouldBe stored
        SettingsPatch(secondaryTaskLocale = Change.To(null)).applyTo(stored) shouldBe ProfileSettings(null, AnswerMode.DIRECT)
        SettingsPatch(answerMode = Change.To(AnswerMode.DIRECT)).applyTo(stored) shouldBe stored
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["ru", "UK", "uk-UA", " uk", "uk ", ""])
    fun `a locale is matched exactly against the configured ones`(value: String) {
        errors(SettingsInput(secondaryTaskLocale = JsonField.Text(value))) shouldBe
            listOf(FieldError(locale, FieldProblem.UNSUPPORTED_LOCALE))
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["SOCRATIC", "direct", "Direct", ""])
    fun `an answer mode outside the enum is refused`(value: String) {
        errors(SettingsInput(answerMode = JsonField.Text(value))) shouldBe listOf(FieldError(mode, FieldProblem.UNKNOWN_VALUE))
    }

    // @trace FR-43
    @Test
    fun `answerMode null is refused, not a no-op`() {
        errors(SettingsInput(answerMode = JsonField.Null)) shouldBe listOf(FieldError(mode, FieldProblem.NULL_NOT_ALLOWED))
    }

    // @trace FR-43
    @Test
    fun `wrong JSON types are refused per field`() {
        errors(SettingsInput(JsonField.WrongType, JsonField.WrongType)) shouldBe
            listOf(FieldError(locale, FieldProblem.WRONG_TYPE), FieldError(mode, FieldProblem.WRONG_TYPE))
    }

    // @trace FR-43
    @Test
    fun `every invalid field is named, and GUIDED does not hide a validation error`() {
        errors(SettingsInput(JsonField.Text("ru"), JsonField.Null)) shouldBe
            listOf(FieldError(locale, FieldProblem.UNSUPPORTED_LOCALE), FieldError(mode, FieldProblem.NULL_NOT_ALLOWED))
        errors(SettingsInput(JsonField.Text("uk"), JsonField.Null)) shouldBe listOf(FieldError(mode, FieldProblem.NULL_NOT_ALLOWED))
        errors(SettingsInput(JsonField.Text("ru"), JsonField.Text("GUIDED"))) shouldBe
            listOf(FieldError(locale, FieldProblem.UNSUPPORTED_LOCALE))
    }

    // @trace FR-43
    @Test
    fun `an empty configuration refuses every locale but still clears`() {
        val none = SettingsValidator(emptySet())
        (none.validate(SettingsInput(secondaryTaskLocale = JsonField.Text("uk"))) is Validation.Invalid) shouldBe true
        (none.validate(SettingsInput(secondaryTaskLocale = JsonField.Null)) as Validation.Valid).patch.secondaryTaskLocale shouldBe
            Change.To(null)
    }

    // @trace FR-43
    @Test
    fun `no random string other than a configured locale is accepted`() {
        val random = Random(43)
        repeat(1_000) {
            val value = String(CharArray(random.nextInt(0, 12)) { random.nextInt(0x20, 0x250).toChar() })
            (validator.validate(SettingsInput(secondaryTaskLocale = JsonField.Text(value))) is Validation.Valid) shouldBe (value == "uk")
        }
    }

    // @trace NFR-3
    @Test
    fun `a submitted text is never printed by the input`() {
        JsonField.Text("PII-SENTINEL").toString() shouldNotContain "PII-SENTINEL"
    }
}
