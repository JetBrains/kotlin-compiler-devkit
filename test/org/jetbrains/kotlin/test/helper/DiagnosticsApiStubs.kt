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
        import kotlin.reflect.KProperty

        enum class Severity { INFO, ERROR, WARNING, FIXED_WARNING, STRONG_WARNING }

        class SourceElementPositioningStrategy

        object SourceElementPositioningStrategies {
            val DEFAULT = SourceElementPositioningStrategy()
            val DECLARATION_NAME = SourceElementPositioningStrategy()
            val VISIBILITY_MODIFIER = SourceElementPositioningStrategy()
        }

        abstract class KtDiagnosticsContainer {
            fun getRendererFactory(): Any? = null
        }

        class KtDiagnosticFactory0(name: String, severity: Severity, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)
        class KtDiagnosticFactory1<A>(name: String, severity: Severity, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)
        class KtDiagnosticFactoryForDeprecation0(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)
        class KtDiagnosticFactoryForDeprecation1<A>(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)

        class KtSourcelessDiagnosticFactory(name: String, severity: Severity, rendererFactory: Any?)

        class DiagnosticFactoryDelegateProvider {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtSourcelessDiagnosticFactory =
                KtSourcelessDiagnosticFactory(property.name, Severity.ERROR, null)
        }

        fun KtDiagnosticsContainer.warning0(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        fun <A> KtDiagnosticsContainer.error1(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        fun <A, B> KtDiagnosticsContainer.strongWarning2(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        fun <A> KtDiagnosticsContainer.deprecationError1(featureForError: LanguageFeature, positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        fun KtDiagnosticsContainer.strongWarningWithoutSource() = DiagnosticFactoryDelegateProvider()
        fun KtDiagnosticsContainer.infoWithoutSource() = DiagnosticFactoryDelegateProvider()
        // Arities that don't exist (yet) must be supported too.
        fun <A, B, C, D, E> KtDiagnosticsContainer.error5(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        // Not a delegate kind, even though it is in the diagnostics package.
        fun <A> KtDiagnosticsContainer.errorX() = DiagnosticFactoryDelegateProvider()
    """.trimIndent()
}
