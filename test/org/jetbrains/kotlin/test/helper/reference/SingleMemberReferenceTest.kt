package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.test.helper.DiagnosticsApiStubs.DIAGNOSTICS_STUB
import org.jetbrains.kotlin.test.helper.DiagnosticsApiStubs.LANGUAGE_FEATURE_STUB
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration


class SingleMemberReferenceTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("stubs/LanguageFeature.kt", LANGUAGE_FEATURE_STUB)
        myFixture.addFileToProject("stubs/Diagnostics.kt", DIAGNOSTICS_STUB)
    }

    private lateinit var properties: Map<String, KtProperty>
    private lateinit var host: PsiElement

    private fun configure(text: String) {
        val file = myFixture.configureByText("Container.kt", text.trimIndent())
        properties = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java).associateBy { it.name!! }
        host = file
    }

    private fun assertResolvesTo(name: String, target: String) {
        val resolved = reference(name).resolve()
        assertEquals(name, properties.getValue(target), resolved)
    }

    private fun assertUnresolved(name: String) {
        assertNull(name, reference(name).resolve())
    }

    private fun reference(name: String): SingleMemberReference =
        SingleMemberReference(host, TextRange(0, name.length), name, ::getFirErrorClasses, resolveDeprecationSuffixes = true)

    fun testDeprecationSuffixes() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val DEPRECATED: KtDiagnosticFactoryForDeprecation1<String> = KtDiagnosticFactoryForDeprecation1("DEPRECATED", LanguageFeature.ForbidSomething, SourceElementPositioningStrategies.DEFAULT, getRendererFactory())
                val DEPRECATED_FACTORY: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("DEPRECATED_FACTORY", LanguageFeature.ForbidSomething, SourceElementPositioningStrategies.DEFAULT, getRendererFactory())
            }
            """,
        )

        // The base names resolve directly, as before.
        assertResolvesTo("DEPRECATED", "DEPRECATED")
        assertResolvesTo("DEPRECATED_FACTORY", "DEPRECATED_FACTORY")

        // The suffixed variants of deprecation diagnostics resolve to the base declaration.
        assertResolvesTo("DEPRECATED_ERROR", "DEPRECATED")
        assertResolvesTo("DEPRECATED_WARNING", "DEPRECATED")
        assertResolvesTo("DEPRECATED_FACTORY_ERROR", "DEPRECATED_FACTORY")
        assertResolvesTo("DEPRECATED_FACTORY_WARNING", "DEPRECATED_FACTORY")

        // The suffix resolution is opt-in: disabled by default.
        val disabled = SingleMemberReference(host, TextRange(0, "DEPRECATED_ERROR".length), "DEPRECATED_ERROR", ::getFirErrorClasses)
        assertNull(disabled.resolve())
    }

    fun testSuffixResolutionRequiresDeprecation() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val REGULAR: KtDiagnosticFactory1<String> = KtDiagnosticFactory1("REGULAR", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT, getRendererFactory())
            }
            """,
        )

        // The base name exists, but it is not a deprecation.
        assertUnresolved("REGULAR_ERROR")
        assertUnresolved("REGULAR_WARNING")

        // Unknown diagnostics.
        assertUnresolved("UNKNOWN")
        assertUnresolved("UNKNOWN_ERROR")
        assertUnresolved("UNKNOWN_WARNING")
        // The suffix alone is not a name.
        assertUnresolved("_ERROR")
    }

    fun testDelegatedDiagnostic() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val DELEGATED by error1<String>()
                val DELEGATED_WARNING by warning0()
                val DELEGATED_DEPRECATED by deprecationError1<String>(LanguageFeature.ForbidSomething)
            }
            """,
        )
        assertResolvesTo("DELEGATED", "DELEGATED")
        assertResolvesTo("DELEGATED_WARNING", "DELEGATED_WARNING")
        assertResolvesTo("DELEGATED_DEPRECATED", "DELEGATED_DEPRECATED")
        assertResolvesTo("DELEGATED_DEPRECATED_ERROR", "DELEGATED_DEPRECATED")

        // The suffix resolution only applies to deprecation diagnostics.
        assertUnresolved("DELEGATED_ERROR")
        assertUnresolved("DELEGATED_WARNING_ERROR")
    }

    fun testExactNameWins() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val REAL_SUFFIX_ERROR: KtDiagnosticFactory1<String> = KtDiagnosticFactory1("REAL_SUFFIX_ERROR", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT, getRendererFactory())
            }
            """,
        )

        assertResolvesTo("REAL_SUFFIX_ERROR", "REAL_SUFFIX_ERROR")
    }

    fun testReportedErrorMarkers() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val DEPRECATED: KtDiagnosticFactoryForDeprecation1<String> = KtDiagnosticFactoryForDeprecation1("DEPRECATED", LanguageFeature.ForbidSomething, SourceElementPositioningStrategies.DEFAULT, getRendererFactory())
            }
            """,
        )

        val virtualFile = myFixture.tempDirFixture.createFile("testdata/multifile.test", "<!DEPRECATED_ERROR!>foo()<!>")
        TestDataPathsConfiguration.getInstance(project).testDataDirectories = arrayOf(virtualFile.parent.path)
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val testFile = myFixture.file ?: error("The test data file wasn't configured")

        val references = testFile.references.filterIsInstance<SingleMemberReference>()
        assertEquals(references.toString(), 1, references.size)
        val reference = references.single()
        assertEquals("DEPRECATED_ERROR", reference.canonicalText)
        assertEquals(properties.getValue("DEPRECATED"), reference.resolve())
    }
}
