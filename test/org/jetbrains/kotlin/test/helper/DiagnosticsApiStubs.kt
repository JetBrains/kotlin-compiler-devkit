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
        class KtDiagnosticFactory2<A, B>(name: String, severity: Severity, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)
        class KtDiagnosticFactoryForDeprecation0(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)
        class KtDiagnosticFactoryForDeprecation1<A>(name: String, featureForError: LanguageFeature, defaultPositioningStrategy: SourceElementPositioningStrategy, rendererFactory: Any?)

        class KtSourcelessDiagnosticFactory(name: String, severity: Severity, rendererFactory: Any?)

        // Like the real DSL, each delegate function returns a provider whose type determines
        // the type of the property it's used on, e.g. `val X by deprecationError1<String>(feature)`
        // has the type `KtDiagnosticFactoryForDeprecation1<String>`.
        class DiagnosticFactory0DelegateProvider(
            private val severity: Severity,
            private val positioningStrategy: SourceElementPositioningStrategy,
            private val container: KtDiagnosticsContainer,
        ) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtDiagnosticFactory0 =
                KtDiagnosticFactory0(property.name, severity, positioningStrategy, container.getRendererFactory())
        }

        class DiagnosticFactory1DelegateProvider<A>(
            private val severity: Severity,
            private val positioningStrategy: SourceElementPositioningStrategy,
            private val container: KtDiagnosticsContainer,
        ) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtDiagnosticFactory1<A> =
                KtDiagnosticFactory1(property.name, severity, positioningStrategy, container.getRendererFactory())
        }

        class DiagnosticFactory2DelegateProvider<A, B>(
            private val severity: Severity,
            private val positioningStrategy: SourceElementPositioningStrategy,
            private val container: KtDiagnosticsContainer,
        ) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtDiagnosticFactory2<A, B> =
                KtDiagnosticFactory2(property.name, severity, positioningStrategy, container.getRendererFactory())
        }

        class DeprecationDiagnosticFactory1DelegateProvider<A>(
            private val featureForError: LanguageFeature,
            private val positioningStrategy: SourceElementPositioningStrategy,
            private val container: KtDiagnosticsContainer,
        ) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtDiagnosticFactoryForDeprecation1<A> =
                KtDiagnosticFactoryForDeprecation1(property.name, featureForError, positioningStrategy, container.getRendererFactory())
        }

        class SourcelessDiagnosticFactoryDelegateProvider(
            private val severity: Severity,
            private val container: KtDiagnosticsContainer,
        ) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtSourcelessDiagnosticFactory =
                KtSourcelessDiagnosticFactory(property.name, severity, container.getRendererFactory())
        }

        // For functions that aren't part of the real diagnostics DSL, e.g. plugin-local helpers.
        class DiagnosticFactoryDelegateProvider {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): KtSourcelessDiagnosticFactory =
                KtSourcelessDiagnosticFactory(property.name, Severity.ERROR, null)
        }

        fun KtDiagnosticsContainer.warning0(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) =
            DiagnosticFactory0DelegateProvider(Severity.WARNING, positioningStrategy, this)
        fun <A> KtDiagnosticsContainer.error1(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) =
            DiagnosticFactory1DelegateProvider<A>(Severity.ERROR, positioningStrategy, this)
        fun <A, B> KtDiagnosticsContainer.strongWarning2(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) =
            DiagnosticFactory2DelegateProvider<A, B>(Severity.STRONG_WARNING, positioningStrategy, this)
        fun <A> KtDiagnosticsContainer.deprecationError1(featureForError: LanguageFeature, positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) =
            DeprecationDiagnosticFactory1DelegateProvider<A>(featureForError, positioningStrategy, this)
        fun KtDiagnosticsContainer.strongWarningWithoutSource() = SourcelessDiagnosticFactoryDelegateProvider(Severity.STRONG_WARNING, this)
        fun KtDiagnosticsContainer.infoWithoutSource() = SourcelessDiagnosticFactoryDelegateProvider(Severity.INFO, this)
        // Arities that don't exist (yet) must be supported too.
        fun <A, B, C, D, E> KtDiagnosticsContainer.error5(positioningStrategy: SourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT) = DiagnosticFactoryDelegateProvider()
        // Not a delegate kind, even though it is in the diagnostics package.
        fun <A> KtDiagnosticsContainer.errorX() = DiagnosticFactoryDelegateProvider()
    """.trimIndent()
}
