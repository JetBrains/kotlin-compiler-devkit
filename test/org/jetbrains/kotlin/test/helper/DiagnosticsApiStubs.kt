package org.jetbrains.kotlin.test.helper

/**
 * Simplified stubs of the real compiler APIs (`org.jetbrains.kotlin.diagnostics`, `org.jetbrains.kotlin.config`),
 * added to the fixture project by tests that need the diagnostics API to resolve.
 */
internal object DiagnosticsApiStubs {
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
        class KtDiagnosticFactoryForDeprecation1<A>(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: AbstractSourceElementPositioningStrategy, psiType: KClass<*>, rendererFactory: Any?)

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
