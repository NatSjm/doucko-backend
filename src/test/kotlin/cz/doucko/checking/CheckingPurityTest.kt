package cz.doucko.checking

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * The checker is a pure module (AGENTS.md, answer-checking "Checker share of the round-trip budget"): no
 * Spring, database, network or file dependency. Only the Modulith marker `CheckingModule` names Spring.
 */
class CheckingPurityTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("cz.doucko.checking")

    // @trace FR-23
    @Test
    fun `the checking module depends only on java, kotlin and itself`() {
        classes.shouldNotBeEmpty()
        classes()
            .that(
                DescribedPredicate.describe("are not the module marker") { c: JavaClass ->
                    c.name != CheckingModule::class.java.name
                },
            ).should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java.lang..",
                "java.math..",
                "java.text..",
                "java.util..",
                "kotlin..",
                "org.jetbrains.annotations..",
                "cz.doucko.checking..",
            ).check(classes)
    }
}
