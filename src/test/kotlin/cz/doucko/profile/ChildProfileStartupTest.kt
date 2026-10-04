package cz.doucko.profile

import cz.doucko.profile.application.ChildProfileStore
import cz.doucko.profile.application.ProfileConfiguration
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * child-profile "Single profile created once at first start" and "Display name is validated on first
 * start": the context (and so the process) fails before serving anything, names the variable, never
 * the value, and creates no profile. The real-database variant is `ChildProfileStartupIT`.
 */
@ExtendWith(OutputCaptureExtension::class)
class ChildProfileStartupTest {
    private fun start(
        store: InMemoryChildProfileStore,
        vararg properties: String,
    ) = ApplicationContextRunner()
        .withUserConfiguration(ProfileConfiguration::class.java)
        .withBean(ChildProfileStore::class.java, { store })
        .withPropertyValues("logging.level.root=TRACE", *properties)

    private fun Throwable.allText(): String = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }

    // @trace FR-43
    @Test
    fun `the first start creates the profile with the trimmed name`() {
        val store = InMemoryChildProfileStore(existing = false)
        start(store, "DOUCKO_CHILD_DISPLAY_NAME=  $FICTIONAL_NAME  ").run { context ->
            context.startupFailure shouldBe null
        }
        store.displayName shouldBe FICTIONAL_NAME
        store.creates shouldBe 1
    }

    // @trace FR-43
    @Test
    fun `an unset variable on an empty database refuses to start and creates nothing`() {
        val store = InMemoryChildProfileStore(existing = false)
        start(store).run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "DOUCKO_CHILD_DISPLAY_NAME"
        }
        store.creates shouldBe 0
    }

    // @trace FR-43
    // @trace NFR-3
    @ParameterizedTest
    @ValueSource(
        strings = ["", "   ", "Hrdinka\nNovak", "Hrdinka\tNovak", "Hrdinka�(", "Hrdinkaxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"],
    )
    fun `a refused name stops the startup without echoing it`(
        name: String,
        output: CapturedOutput,
    ) {
        val store = InMemoryChildProfileStore(existing = false)
        start(store, "DOUCKO_CHILD_DISPLAY_NAME=$name").run { context ->
            val failure = context.startupFailure.shouldNotBeNull().allText()
            failure shouldContain "DOUCKO_CHILD_DISPLAY_NAME"
            if (name.isNotBlank()) failure shouldNotContain FICTIONAL_NAME
            failure shouldNotContain "xxxxxxxxxx"
        }
        store.creates shouldBe 0
        output.all shouldNotContain FICTIONAL_NAME
        output.all shouldNotContain "xxxxxxxxxx"
    }

    // @trace FR-43
    @Test
    fun `the 51-character refusal names the 50-character limit`() {
        val store = InMemoryChildProfileStore(existing = false)
        start(store, "DOUCKO_CHILD_DISPLAY_NAME=${"a".repeat(51)}").run { context ->
            context.startupFailure.shouldNotBeNull().allText() shouldContain "50-character"
        }
    }

    // @trace FR-43
    @ParameterizedTest
    @ValueSource(strings = ["Jiné jméno", "", "Hrdinka\nNovak"])
    fun `an existing profile starts normally whatever the variable says, and is unchanged`(name: String) {
        val store = InMemoryChildProfileStore(existing = true)
        start(store, "DOUCKO_CHILD_DISPLAY_NAME=$name").run { context -> context.startupFailure shouldBe null }
        start(store).run { context -> context.startupFailure shouldBe null }
        store.displayName shouldBe FICTIONAL_NAME
        store.creates shouldBe 0
    }

    // @trace NFR-3
    @Test
    fun `a successful first start logs no display name`(output: CapturedOutput) {
        start(InMemoryChildProfileStore(existing = false), "DOUCKO_CHILD_DISPLAY_NAME=$FICTIONAL_NAME").run { context ->
            context.startupFailure shouldBe null
        }
        output.all shouldNotContain FICTIONAL_NAME
    }
}
