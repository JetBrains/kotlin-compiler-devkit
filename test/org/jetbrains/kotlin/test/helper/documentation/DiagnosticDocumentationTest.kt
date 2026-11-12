package org.jetbrains.kotlin.test.helper.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Tests the documentation of diagnostics end-to-end: from a property to the rendered documentation.
 *
 * The declarations are recognized by resolve, so simplified stubs of the real APIs
 * (`org.jetbrains.kotlin.diagnostics`, `org.jetbrains.kotlin.config`) are added to the project.
 */
@OptIn(KaAllowAnalysisOnEdt::class)
class DiagnosticDocumentationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("stubs/LanguageFeature.kt", LANGUAGE_FEATURE_STUB)
        myFixture.addFileToProject("stubs/Diagnostics.kt", DIAGNOSTICS_STUB)
    }

    private lateinit var properties: Map<String, KtProperty>

    private fun configure(text: String) {
        val file = myFixture.configureByText("Diagnostics.kt", text.trimIndent())
        properties = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java).associateBy { it.name!! }
    }

    private fun target(name: String): DocumentationTarget? =
        allowAnalysisOnEdt { DiagnosticSeverityDocumentationTargetProvider().documentationTarget(properties.getValue(name), null) }

    private fun documentation(name: String): String =
        allowAnalysisOnEdt { (target(name) as DiagnosticDocumentationTarget).html()!! }

    private fun assertBadges(html: String, vararg badges: String) {
        assertEquals(html, badges.toList(), Regex("<nobr>(.*?)</nobr>").findAll(html).map { it.groupValues[1] }.toList())
    }

    private fun assertRow(html: String, title: String, value: String) {
        assertTrue(html, html.contains("<td valign='top' class='section'><p>$title</td><td valign='top'><code>$value</code></td>"))
    }

    private fun assertNoRow(html: String, title: String) {
        assertFalse(html, html.contains("<p>$title</td>"))
    }

    private fun assertNotDiagnostic(name: String) {
        assertNull(target(name))
    }

    fun testFactories() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.config.LanguageFeature.ForbidSomething
            import org.jetbrains.kotlin.diagnostics.*
            import org.jetbrains.kotlin.diagnostics.Severity.ERROR
            import org.jetbrains.kotlin.diagnostics.KtNamedDeclaration as Named

            object MyErrors : KtDiagnosticsContainer() {
                val ERR: KtDiagnosticFactory0 = KtDiagnosticFactory0("ERR", ERROR, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val STRONG: KtDiagnosticFactory1<String> = KtDiagnosticFactory1("STRONG", Severity.STRONG_WARNING, SourceElementPositioningStrategies.DECLARATION_NAME, Named::class, getRendererFactory())
                val NAMED: KtDiagnosticFactory0 = KtDiagnosticFactory0(name = "NAMED", psiType = PsiElement::class, severity = Severity.INFO, defaultPositioningStrategy = SourceElementPositioningStrategies.DEFAULT, rendererFactory = getRendererFactory())
                val DEPRECATED: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("DEPRECATED", ForbidSomething, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val OTHER: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("OTHER", LanguageFeature.ForbidOther, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val EXPERIMENTAL: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("EXPERIMENTAL", LanguageFeature.NotYetEnabled, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val UNKNOWN_VERSION: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("UNKNOWN_VERSION", LanguageFeature.ForbidLatest, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val UNKNOWN_FEATURE: KtDiagnosticFactoryForDeprecation0 = KtDiagnosticFactoryForDeprecation0("UNKNOWN_FEATURE", LOCAL_FEATURE, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, getRendererFactory())
                val INDIRECT: KtDiagnosticFactory0 = KtDiagnosticFactory0("INDIRECT", ERROR, LOCAL_STRATEGY, LOCAL_PSI_TYPE, getRendererFactory())
                val SOURCELESS: KtSourcelessDiagnosticFactory = KtSourcelessDiagnosticFactory("SOURCELESS", Severity.INFO, getRendererFactory())
                val NOT_A_DIAGNOSTIC: Int = 42
            }

            private val LOCAL_STRATEGY = SourceElementPositioningStrategies.DEFAULT
            private val LOCAL_PSI_TYPE = PsiElement::class
            private val LOCAL_FEATURE = LanguageFeature.ForbidOther
            """,
        )

        val err = documentation("ERR")
        assertTrue(err, err.startsWith("<div class='definition'><table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td valign=\"top\"><pre>"))
        assertTrue(err, err.contains("ERR"))
        assertBadges(err, "ERROR")
        assertRow(err, "Positioning:", "DEFAULT")
        assertRow(err, "PSI type:", "PsiElement")
        assertTrue(err, err.contains("<hr/>"))
        assertRow(err, "Defined in:", "org.example.MyErrors")
        assertNoRow(err, "Error since:")

        val strong = documentation("STRONG")
        assertBadges(strong, "STRONG WARNING")
        assertRow(strong, "Positioning:", "DECLARATION_NAME")
        // Resolved, not taken from the text of the class literal.
        assertRow(strong, "PSI type:", "KtNamedDeclaration")

        assertBadges(documentation("NAMED"), "INFO")

        val deprecated = documentation("DEPRECATED")
        assertBadges(deprecated, "WARNING", "ERROR")
        assertRow(deprecated, "Error since:", "ForbidSomething (2.3)")
        assertRow(documentation("OTHER"), "Error since:", "ForbidOther (2.2)")
        assertRow(documentation("EXPERIMENTAL"), "Error since:", "NotYetEnabled (experimental)")
        assertRow(documentation("UNKNOWN_VERSION"), "Error since:", "ForbidLatest (???)")
        val unknownFeature = documentation("UNKNOWN_FEATURE")
        assertBadges(unknownFeature, "WARNING", "ERROR")
        assertRow(unknownFeature, "Error since:", "???")

        val indirect = documentation("INDIRECT")
        assertRow(indirect, "Positioning:", "???")
        assertRow(indirect, "PSI type:", "???")

        val sourceless = documentation("SOURCELESS")
        assertBadges(sourceless, "INFO")
        assertNoRow(sourceless, "Positioning:")
        assertNoRow(sourceless, "PSI type:")

        assertNotDiagnostic("NOT_A_DIAGNOSTIC")
    }

    fun testFactoriesOutsideOfContainer() {
        configure(
            """
            import org.jetbrains.kotlin.diagnostics.*

            object NotAContainer {
                val ERR: KtDiagnosticFactory0 = KtDiagnosticFactory0("ERR", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, null)
            }
            """,
        )
        assertNotDiagnostic("ERR")
    }

    fun testLocalFactoryClass() {
        configure(
            """
            import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
            import org.jetbrains.kotlin.diagnostics.Severity

            class KtDiagnosticFactory0(name: String, severity: Severity)

            object MyErrors : KtDiagnosticsContainer() {
                val ERR: KtDiagnosticFactory0 = KtDiagnosticFactory0("ERR", Severity.ERROR)
            }
            """,
        )
        assertNotDiagnostic("ERR")
    }

    fun testDelegates() {
        configure(
            """
            package org.example

            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val ERR by error1<KtElement, String>(SourceElementPositioningStrategies.VISIBILITY_MODIFIER)
                val WARN by warning0<PsiElement>()
                val STRONG by strongWarning2<KtNamedDeclaration, String, String>()
                val DEPRECATED by deprecationError1<PsiElement, String>(LanguageFeature.ForbidSomething, SourceElementPositioningStrategies.DECLARATION_NAME)
                val NAMED by error1<KtElement, String>(positioningStrategy = SourceElementPositioningStrategies.DECLARATION_NAME)
                val SOURCELESS: KtSourcelessDiagnosticFactory by strongWarningWithoutSource()
                val INFO_SOURCELESS by infoWithoutSource()
                val ERR5 by error5<PsiElement, String, String, String, String, String>()
                val NOT_A_DELEGATE_KIND by errorX<PsiElement>()
                val LOCAL_INFO by info1<String>(SourceElementPositioningStrategies.DEFAULT)
                val NOT_A_DIAGNOSTIC by lazy { 42 }

                val strategy = SourceElementPositioningStrategies.DECLARATION_NAME
                val LOCAL_VARIABLE by error1<KtElement, String>(strategy)
                val feature = LanguageFeature.ForbidSomething
                val UNKNOWN_FEATURE by deprecationError1<PsiElement, String>(feature)
                val OTHER_OBJECT by error1<KtElement, String>(MyPositioningStrategies.CUSTOM)
            }

            object MyPositioningStrategies {
                val CUSTOM = AbstractSourceElementPositioningStrategy()
            }

            /** A plugin-local helper, like the one in the DataFrame plugin: must not be recognized. */
            fun <A> info1(positioningStrategy: Any): DiagnosticFactoryDelegateProvider = DiagnosticFactoryDelegateProvider()

            fun <T> lazy(init: () -> T): DiagnosticFactoryDelegateProvider = DiagnosticFactoryDelegateProvider()
            """,
        )

        val err = documentation("ERR")
        assertBadges(err, "ERROR")
        assertRow(err, "Positioning:", "VISIBILITY_MODIFIER")
        assertRow(err, "PSI type:", "KtElement")
        assertRow(err, "Defined in:", "org.example.MyErrors")

        val warn = documentation("WARN")
        assertBadges(warn, "WARNING")
        // The argument is omitted.
        assertRow(warn, "Positioning:", "DEFAULT")
        assertRow(warn, "PSI type:", "PsiElement")

        val strong = documentation("STRONG")
        assertBadges(strong, "STRONG WARNING")
        assertRow(strong, "PSI type:", "KtNamedDeclaration")

        val deprecated = documentation("DEPRECATED")
        assertBadges(deprecated, "WARNING", "ERROR")
        assertRow(deprecated, "Error since:", "ForbidSomething (2.3)")
        assertRow(deprecated, "Positioning:", "DECLARATION_NAME")

        val unknownFeature = documentation("UNKNOWN_FEATURE")
        assertBadges(unknownFeature, "WARNING", "ERROR")
        assertRow(unknownFeature, "Error since:", "???")

        assertRow(documentation("NAMED"), "Positioning:", "DECLARATION_NAME")

        val sourceless = documentation("SOURCELESS")
        assertBadges(sourceless, "STRONG WARNING")
        assertNoRow(sourceless, "Positioning:")
        assertNoRow(sourceless, "PSI type:")
        assertBadges(documentation("INFO_SOURCELESS"), "INFO")

        assertRow(documentation("LOCAL_VARIABLE"), "Positioning:", "???")
        assertRow(documentation("OTHER_OBJECT"), "Positioning:", "???")

        assertNotDiagnostic("LOCAL_INFO")
        assertNotDiagnostic("NOT_A_DELEGATE_KIND")

        val err5 = documentation("ERR5")
        assertBadges(err5, "ERROR")
        assertRow(err5, "Positioning:", "DEFAULT")
        assertRow(err5, "PSI type:", "PsiElement")
        assertNotDiagnostic("NOT_A_DIAGNOSTIC")
    }

    fun testAliasedDelegate() {
        configure(
            """
            import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
            import org.jetbrains.kotlin.diagnostics.PsiElement
            import org.jetbrains.kotlin.diagnostics.error1 as anError

            object MyErrors : KtDiagnosticsContainer() {
                val ALIASED by anError<PsiElement, String>()
            }
            """,
        )
        assertBadges(documentation("ALIASED"), "ERROR")
    }

    fun testKDoc() {
        configure(
            """
            import org.jetbrains.kotlin.config.LanguageFeature
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                /**
                 * Reported when `something` is **off**, see [MyErrors].
                 *
                 * @see NOT_DOCUMENTED
                 */
                val DOCUMENTED by deprecationError1<PsiElement, String>(LanguageFeature.ForbidSomething)

                val NOT_DOCUMENTED by warning0<PsiElement>()
            }
            """,
        )
        val documented = documentation("DOCUMENTED")
        val kdoc = Regex("<div class='content'>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(documented)
        assertNotNull(documented, kdoc)
        val kdocHtml = kdoc!!.groupValues[1]
        assertTrue(kdocHtml, kdocHtml.contains("<strong>off</strong>"))
        // Rendered by the Kotlin plugin, so links are links.
        assertTrue(kdocHtml, kdocHtml.contains("<a href=\"psi_element://MyErrors\">"))
        // Tags are rendered as sections of the KDoc.
        val seeAlso = documented.indexOf("<p>See Also:</td>")
        assertTrue(documented, seeAlso > kdoc.range.last)
        assertTrue(documented, documented.contains("<a href=\"psi_element://NOT_DOCUMENTED\">"))
        // The KDoc is between the definition and the diagnostic sections, and separated from them.
        val definitionEnd = documented.indexOf("</table></div>")
        val separator = documented.indexOf("<hr/>")
        val errorSince = documented.indexOf("<p>Error since:</td>")
        assertTrue(documented, definitionEnd in 0..<kdoc.range.first && seeAlso < separator && separator < errorSince)
        assertRow(documented, "Error since:", "ForbidSomething (2.3)")

        // The hint has no KDoc.
        assertFalse(target("DOCUMENTED")!!.computeDocumentationHint()!!.contains("Reported when"))

        val notDocumented = documentation("NOT_DOCUMENTED")
        assertFalse(notDocumented, notDocumented.contains("<div class='content'>"))
        // Only the separator before "Defined in:".
        assertEquals(notDocumented, 1, Regex("<hr/>").findAll(notDocumented).count())
    }

    fun testHintAndPresentation() {
        configure(
            """
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val SOME_WARNING by warning0<PsiElement>()
            }
            """,
        )
        val target = target("SOME_WARNING")!!
        val hint = target.computeDocumentationHint()!!
        assertBadges(hint, "WARNING")
        assertNoRow(hint, "Positioning:")
        assertNoRow(hint, "Defined in:")

        val presentation = target.computePresentation()
        assertEquals("SOME_WARNING", presentation.presentableText)
        // The module is shown at the bottom of the documentation, like for the regular Kotlin documentation.
        assertNotNull(presentation.locationText)
    }

    fun testPointer() {
        configure(
            """
            import org.jetbrains.kotlin.diagnostics.*

            object MyErrors : KtDiagnosticsContainer() {
                val SOME_WARNING by warning0<PsiElement>()
            }
            """,
        )
        val target = target("SOME_WARNING")!!
        val pointer = target.createPointer()
        // Doesn't analyze, so it doesn't fail on the EDT: the platform dereferences pointers there, e.g. when a link can't be resolved.
        val restored = pointer.dereference() as DiagnosticDocumentationTarget
        assertEquals(documentation("SOME_WARNING"), allowAnalysisOnEdt { restored.html() })

        // A declaration that isn't a diagnostic anymore.
        WriteCommandAction.runWriteCommandAction(project) {
            properties.getValue("SOME_WARNING").delegate!!.delete()
        }
        val invalidated = pointer.dereference()!!
        assertNull(allowAnalysisOnEdt { invalidated.computeDocumentation() })
        assertNull(allowAnalysisOnEdt { invalidated.computeDocumentationHint() })
    }

    private companion object {
        val LANGUAGE_FEATURE_STUB = """
            package org.jetbrains.kotlin.config

            enum class LanguageVersion(val major: Int, val minor: Int) {
                KOTLIN_2_2(2, 2),
                KOTLIN_2_3(major = 2, minor = 3),
                LATEST_STABLE(2, 4),
            }

            // Mirrors the constructors of the real `LanguageFeature`.
            enum class LanguageFeature(
                val sinceVersion: LanguageVersion?,
                val issue: String,
                private val enabledInProgressiveMode: Boolean = false,
                val enabledInLatestLVTests: Boolean,
            ) {
                ForbidSomething(LanguageVersion.KOTLIN_2_3, enabledInProgressiveMode = true, "KT-1"),
                ForbidOther(LanguageVersion.KOTLIN_2_2, "KT-2"),
                NotYetEnabled(sinceVersion = null, issue = "KT-3", enabledInLatestLVTests = false),
                ForbidLatest(LanguageVersion.LATEST_STABLE, "KT-4"),
                ;

                constructor(sinceVersion: LanguageVersion, issue: String, enabledInProgressiveMode: Boolean = false) :
                    this(sinceVersion, issue, enabledInProgressiveMode = enabledInProgressiveMode, enabledInLatestLVTests = false)

                constructor(sinceVersion: LanguageVersion, enabledInProgressiveMode: Boolean, issue: String) :
                    this(sinceVersion, issue, enabledInProgressiveMode = enabledInProgressiveMode, enabledInLatestLVTests = false)
            }
        """.trimIndent()

        val DIAGNOSTICS_STUB = """
            package org.jetbrains.kotlin.diagnostics

            import org.jetbrains.kotlin.config.LanguageFeature
            import kotlin.reflect.KClass
            import kotlin.reflect.KProperty

            interface PsiElement
            interface KtElement : PsiElement
            interface KtNamedDeclaration : KtElement

            enum class Severity { INFO, ERROR, WARNING, FIXED_WARNING, STRONG_WARNING }

            open class AbstractSourceElementPositioningStrategy

            object SourceElementPositioningStrategies {
                val DEFAULT = AbstractSourceElementPositioningStrategy()
                val DECLARATION_NAME = AbstractSourceElementPositioningStrategy()
                val VISIBILITY_MODIFIER = AbstractSourceElementPositioningStrategy()
            }

            abstract class KtDiagnosticsContainer {
                fun getRendererFactory(): Any? = null
            }

            class KtDiagnosticFactory0(name: String, severity: Severity, defaultPositioningStrategy: AbstractSourceElementPositioningStrategy, psiType: KClass<*>, rendererFactory: Any?)
            class KtDiagnosticFactory1<A>(name: String, severity: Severity, defaultPositioningStrategy: AbstractSourceElementPositioningStrategy, psiType: KClass<*>, rendererFactory: Any?)
            class KtDiagnosticFactoryForDeprecation0(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: AbstractSourceElementPositioningStrategy, psiType: KClass<*>, rendererFactory: Any?)

            class KtSourcelessDiagnosticFactory(name: String, severity: Severity, rendererFactory: Any?)

            class DiagnosticFactoryDelegateProvider {
                operator fun getValue(thisRef: Any?, property: KProperty<*>): KtSourcelessDiagnosticFactory =
                    KtSourcelessDiagnosticFactory(property.name, Severity.ERROR, null)
            }

            fun <P : PsiElement> KtDiagnosticsContainer.warning0(positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
            fun <P : PsiElement, A> KtDiagnosticsContainer.error1(positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
            fun <P : PsiElement, A, B> KtDiagnosticsContainer.strongWarning2(positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
            fun <P : PsiElement, A> KtDiagnosticsContainer.deprecationError1(featureForError: LanguageFeature, positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
            fun KtDiagnosticsContainer.strongWarningWithoutSource() = DiagnosticFactoryDelegateProvider()
            fun KtDiagnosticsContainer.infoWithoutSource() = DiagnosticFactoryDelegateProvider()
            // Arities that don't exist (yet) must be supported too.
            fun <P : PsiElement, A, B, C, D, E> KtDiagnosticsContainer.error5(positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
            // Not a delegate kind, even though it is in the diagnostics package.
            fun <P : PsiElement> KtDiagnosticsContainer.errorX() = DiagnosticFactoryDelegateProvider()
        """.trimIndent()
    }
}
