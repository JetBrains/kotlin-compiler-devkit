package org.jetbrains.kotlin.test.helper.documentation

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.kdoc.KDocRenderer.renderKDoc
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import javax.swing.Icon

/**
 * Provides quick documentation for diagnostics declared in `KtDiagnosticsContainer`s, either as factories
 * (e.g. `FirErrors.UNRESOLVED_REFERENCE`) or via delegates (e.g. `val FOO by error1<PsiElement, String>()`),
 * with a severity badge (ERROR / WARNING / ...) in the top right corner.
 *
 * The documentation is rendered by this plugin itself, since there is no public API to decorate the documentation
 * produced by the Kotlin plugin. It's registered with `order="first"` to take precedence over the Kotlin provider.
 */
class DiagnosticSeverityDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? =
        DiagnosticDocumentationTarget.of(element)
}

internal class DiagnosticDocumentationTarget private constructor(
    private val property: KtProperty,
    computeDeclaration: () -> DiagnosticDeclaration?,
) : DocumentationTarget {
    /**
     * Computed lazily, since a [Pointer] may be dereferenced on the EDT (e.g. when a link can't be resolved),
     * where analysis is prohibited. The `compute*` methods are called on a background thread.
     * `null` if the declaration isn't a diagnostic anymore.
     */
    private val declaration: DiagnosticDeclaration? by lazy(computeDeclaration)

    companion object {
        /** Returns `null` if [element] is not a diagnostic declaration or its severity can't be resolved. */
        fun of(element: PsiElement): DiagnosticDocumentationTarget? {
            val property = DiagnosticDeclaration.findSourceProperty(element) ?: return null
            val declaration = DiagnosticDeclaration.of(property) ?: return null
            return DiagnosticDocumentationTarget(property) { declaration }
        }
    }

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val propertyPointer = SmartPointerManager.createPointer(property)
        return Pointer {
            val property = propertyPointer.element ?: return@Pointer null
            DiagnosticDocumentationTarget(property) { DiagnosticDeclaration.of(property) }
        }
    }

    override fun computePresentation(): TargetPresentation {
        // From PSI only, so that the presentation doesn't need analysis.
        val builder = TargetPresentation.builder(property.name.orEmpty())
            .icon(property.getIcon(0))
            .containerText(property.containingClassOrObject?.fqName?.asString())
        // The location is shown at the bottom of the documentation, like the module of the regular Kotlin documentation.
        val (location, locationIcon) = location() ?: return builder.presentation()
        return builder.locationText(location, locationIcon).presentation()
    }

    override val navigatable: Navigatable get() = property

    override fun computeDocumentationHint(): String? {
        val declaration = declaration ?: return null
        return renderDiagnosticDocumentation(declaration, highlightedDefinition(declaration), dark = !JBColor.isBright(), full = false)
    }

    override fun computeDocumentation(): DocumentationResult? = html()?.let { DocumentationResult.documentation(it) }

    /** The HTML of [computeDocumentation]. */
    fun html(): String? {
        val declaration = declaration ?: return null
        val definition = highlightedDefinition(declaration)
        return renderDiagnosticDocumentation(declaration, definition, dark = !JBColor.isBright(), full = true, kdocHtml())
    }

    /**
     * The KDoc of the declaration, if any: the description and the sections for tags (e.g. `@see`),
     * rendered by the Kotlin plugin like in the regular documentation.
     */
    private fun kdocHtml(): String? {
        val kdoc = property.docComment ?: return null
        val hasTags = PsiTreeUtil.findChildrenOfType(kdoc, KDocTag::class.java).any { it !is KDocSection }
        if (kdoc.getDefaultSection().getContent().isBlank() && !hasTags) return null
        return buildString { renderKDoc(kdoc.getDefaultSection(), kdoc.getAllSections()) }
    }

    /** The module or the library containing the declaration. */
    private fun location(): Pair<String, Icon?>? {
        ModuleUtilCore.findModuleForPsiElement(property)?.let { return it.name to ModuleType.get(it).icon }
        val file = property.containingFile.virtualFile ?: return null
        val library = ProjectFileIndex.getInstance(property.project).getOrderEntriesForFile(file)
            .firstNotNullOfOrNull { it as? LibraryOrderEntry }
            ?: return null
        return library.presentableName to AllIcons.Nodes.PpLibFolder
    }

    private fun highlightedDefinition(declaration: DiagnosticDeclaration): String =
        HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
            StringBuilder(),
            property.project,
            KotlinLanguage.INSTANCE,
            declaration.definitionText,
            1.0f,
        ).toString()
}

private enum class DiagnosticSeverityBadge(
    val text: String,
    private val lightBackground: String,
    private val darkBackground: String,
    private val foreground: String,
) {
    ERROR("ERROR", "#E55765", "#DB5C5C", "#FFFFFF"),
    WARNING("WARNING", "#F2C55C", "#D6AE58", "#000000"),
    FIXED_WARNING("FIXED WARNING", "#F2C55C", "#D6AE58", "#000000"),
    STRONG_WARNING("STRONG WARNING", "#E08855", "#C77D55", "#FFFFFF"),
    INFO("INFO", "#8C8C8C", "#6F737A", "#FFFFFF"),
    ;

    companion object {
        /** Returns the badge for the `Severity` entry named [severity]. */
        fun of(severity: Name): DiagnosticSeverityBadge? = entries.firstOrNull { it.name == severity.asString() }
    }

    fun render(dark: Boolean): String {
        val background = if (dark) darkBackground else lightBackground
        return "<td style=\"background-color:$background;color:$foreground;padding:1px 6px;font-weight:bold;\">" +
            "<nobr>$text</nobr></td>"
    }
}

private sealed class DiagnosticSeverityInfo {
    abstract fun renderBadges(dark: Boolean): String

    data class Regular(val badge: DiagnosticSeverityBadge) : DiagnosticSeverityInfo() {
        override fun renderBadges(dark: Boolean): String =
            "<table cellpadding=\"0\" cellspacing=\"0\"><tr>${badge.render(dark)}</tr></table>"
    }

    /**
     * `KtDiagnosticFactoryForDeprecation*`: a warning until [languageFeature] is enabled, then an error.
     * [languageFeature] is `null` if it could not be resolved.
     */
    data class Deprecation(
        val languageFeature: Name?,
        val sinceVersion: SinceVersion = SinceVersion.Unknown,
    ) : DiagnosticSeverityInfo() {
        override fun renderBadges(dark: Boolean): String =
            "<table cellpadding=\"0\" cellspacing=\"0\"><tr>" +
                DiagnosticSeverityBadge.WARNING.render(dark) +
                "<td style=\"padding:0 4px;\">&rarr;</td>" +
                DiagnosticSeverityBadge.ERROR.render(dark) +
                "</tr></table>"

        /** E.g. `ForbidSomething (2.3)`. */
        fun renderFeature(): String = if (languageFeature == null) "???" else when (sinceVersion) {
            is SinceVersion.Version -> "$languageFeature (${sinceVersion.major}.${sinceVersion.minor})"
            SinceVersion.Experimental -> "$languageFeature (experimental)"
            SinceVersion.Unknown -> "$languageFeature (???)"
        }
    }

    /** The `sinceVersion` of a `LanguageFeature`. */
    sealed interface SinceVersion {
        data class Version(val major: Int, val minor: Int) : SinceVersion

        /** `sinceVersion = null`: the feature is not enabled in any version yet. */
        data object Experimental : SinceVersion

        /**
         * The version can't be resolved, e.g. if the sources of `LanguageFeature` are not available
         * or the version isn't a `KOTLIN_X_Y` entry (like `LATEST_STABLE`).
         */
        data object Unknown : SinceVersion
    }
}

/**
 * Renders the documentation: the definition with severity badges in the top right corner and,
 * if [full], the KDoc, sections with diagnostic details and the containing object.
 * [definitionHtml] is the already highlighted and escaped definition, [kdocHtml] is the already rendered KDoc
 * (the content and the sections for its tags).
 */
private fun renderDiagnosticDocumentation(
    declaration: DiagnosticDeclaration,
    definitionHtml: String,
    dark: Boolean,
    full: Boolean,
    kdocHtml: String? = null,
): String = buildString {
    append("<div class='definition'>")
    append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
    append("<td valign=\"top\"><pre>").append(definitionHtml).append("</pre></td>")
    append("<td align=\"right\" valign=\"top\">").append(declaration.severity.renderBadges(dark)).append("</td>")
    append("</tr></table>")
    append("</div>")
    if (!full) return@buildString

    val sections = buildList {
        val severity = declaration.severity
        if (severity is DiagnosticSeverityInfo.Deprecation) add("Error since:" to severity.renderFeature())
        when (val strategy = declaration.positioningStrategy) {
            is DiagnosticDeclaration.PositioningStrategy.Known -> add("Positioning:" to strategy.name.asString())
            DiagnosticDeclaration.PositioningStrategy.Unknown -> add("Positioning:" to "???")
            null -> {}
        }
        when (val psiType = declaration.psiType) {
            is DiagnosticDeclaration.PsiType.Known -> add("PSI type:" to psiType.classId.shortClassName.asString())
            DiagnosticDeclaration.PsiType.Unknown -> add("PSI type:" to "???")
            null -> {}
        }
    }

    // Between the definition and the sections, like in the regular documentation.
    if (kdocHtml != null) {
        append(kdocHtml)
        // Without sections, the separator before "Defined in:" follows anyway.
        if (sections.isNotEmpty()) append("<hr/>")
    }
    appendSections(sections)

    // Separated like the location in the regular documentation; the module is rendered below by the platform from the presentation.
    declaration.containerFqName?.let {
        append("<hr/>")
        appendSections(listOf("Defined in:" to it.asString()))
    }
}

/** Renders [sections] as rows with a title and a value in code style, like the sections of the regular documentation. */
private fun StringBuilder.appendSections(sections: List<Pair<String, String>>) {
    if (sections.isEmpty()) return
    append("<table class='sections'>")
    for ((title, value) in sections) {
        append("<tr><td valign='top' class='section'><p>").append(title).append("</td>")
        append("<td valign='top'><code>").append(value.escapeHtml()).append("</code></td></tr>")
    }
    append("</table>")
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Information extracted from a diagnostic declaration, which is one of:
 * - a factory in a `KtDiagnosticsContainer`: `val X: KtDiagnosticFactory0 = KtDiagnosticFactory0("X", ERROR, ...)`
 *   or `val X = KtSourcelessDiagnosticFactory("X", Severity.INFO, ...)`;
 * - a delegate from `org.jetbrains.kotlin.diagnostics` in a `KtDiagnosticsContainer`:
 *   `val X by error1<PsiElement, String>(SourceElementPositioningStrategies.DEFAULT)`.
 *
 * Declarations are recognized by resolving the calls, so only the real APIs are supported
 * (e.g. a plugin-local `info1` function is not).
 */
private data class DiagnosticDeclaration(
    val definitionText: String,
    val containerFqName: FqName?,
    val severity: DiagnosticSeverityInfo,
    /** `null` if the diagnostic has no positioning strategy, e.g. `strongWarningWithoutSource()`. */
    val positioningStrategy: PositioningStrategy?,
    /** `null` if the diagnostic has no PSI element, e.g. `strongWarningWithoutSource()`. */
    val psiType: PsiType?,
) {
    sealed interface PsiType {
        data class Known(val classId: ClassId) : PsiType

        /** E.g. the class literal is saved to a variable first. */
        data object Unknown : PsiType
    }

    sealed interface PositioningStrategy {
        /** A property of `SourceElementPositioningStrategies`, e.g. `DEFAULT`. */
        data class Known(val name: Name) : PositioningStrategy

        /** Anything else, e.g. a strategy saved to a local variable first. */
        data object Unknown : PositioningStrategy
    }

    /**
     * The kind of delegate functions from `KtDiagnosticFactoryDsl.kt`,
     * e.g. [ERROR] for `error0`, `error1`, ... and `errorWithoutSource`.
     */
    private enum class DelegateKind(functionPrefix: String) {
        ERROR("error"),
        WARNING("warning"),
        STRONG_WARNING("strongWarning"),
        INFO("info"),
        DEPRECATION_ERROR("deprecationError"),
        ;

        private val functionNameRegex = Regex("$functionPrefix(\\d+|WithoutSource)")

        companion object {
            fun of(functionName: Name): DelegateKind? = entries.firstOrNull { it.functionNameRegex.matches(functionName.asString()) }
        }
    }

    companion object {
        private val DIAGNOSTICS_PACKAGE = FqName("org.jetbrains.kotlin.diagnostics")
        private val CONFIG_PACKAGE = FqName("org.jetbrains.kotlin.config")
        private val CONTAINER_CLASS_ID = ClassId(DIAGNOSTICS_PACKAGE, Name.identifier("KtDiagnosticsContainer"))
        private val SEVERITY_CLASS_ID = ClassId(DIAGNOSTICS_PACKAGE, Name.identifier("Severity"))
        private val LANGUAGE_FEATURE_CLASS_ID = ClassId(CONFIG_PACKAGE, Name.identifier("LanguageFeature"))
        private val LANGUAGE_VERSION_CLASS_ID = ClassId(CONFIG_PACKAGE, Name.identifier("LanguageVersion"))
        private val POSITIONING_STRATEGIES_CLASS_ID = ClassId(DIAGNOSTICS_PACKAGE, Name.identifier("SourceElementPositioningStrategies"))
        private val KCLASS_CLASS_ID = ClassId(FqName("kotlin.reflect"), Name.identifier("KClass"))

        private val REGULAR_FACTORY_REGEX = Regex("KtDiagnosticFactory\\d+")
        private val DEPRECATION_FACTORY_REGEX = Regex("KtDiagnosticFactoryForDeprecation\\d+")
        private val SOURCELESS_FACTORY = Name.identifier("KtSourcelessDiagnosticFactory")


        // Parameters of the factory constructors and delegate functions.
        private val SEVERITY = Name.identifier("severity")
        private val FEATURE_FOR_ERROR = Name.identifier("featureForError")
        private val DEFAULT_POSITIONING_STRATEGY = Name.identifier("defaultPositioningStrategy")
        private val POSITIONING_STRATEGY = Name.identifier("positioningStrategy")
        private val PSI_TYPE = Name.identifier("psiType")

        /** The default value of the `positioningStrategy` parameter of the delegate functions. */
        private val DEFAULT_STRATEGY = Name.identifier("DEFAULT")

        // Parameters of the `LanguageFeature` constructors.
        private val SINCE_VERSION = Name.identifier("sinceVersion")

        /** Returns the source declaration, taking attached sources into account for compiled declarations. */
        fun findSourceProperty(element: PsiElement): KtProperty? {
            val property = element as? KtProperty ?: return null
            if (property.hasSource()) return property
            return (property.navigationElement as? KtProperty)?.takeIf { it.hasSource() }
        }

        private fun KtProperty.hasSource(): Boolean = initializer != null || delegateExpression != null

        /**
         * The result of analyzing the declaration itself, with the declarations from other modules
         * which have to be analyzed separately.
         */
        private class DiagnosticDeclarationDraft(
            val declaration: DiagnosticDeclaration,
            /** The `LanguageFeature` entry of a deprecation diagnostic, to find its version. */
            val languageFeature: KtEnumEntry? = null,
        )

        /** Returns `null` if [property] is not a diagnostic, its severity can't be resolved, or during indexing. */
        fun of(property: KtProperty): DiagnosticDeclaration? {
            val name = property.nameAsName ?: return null
            val call = (property.delegateExpression ?: property.initializer) as? KtCallExpression ?: return null
            if (DumbService.isDumb(property.project)) return null
            val draft = analyze(property) { ofCall(property, name, call) } ?: return null
            return complete(draft)
        }

        /** Resolves the version of the language feature, which is declared in another module, so in its own analysis session. */
        private fun complete(draft: DiagnosticDeclarationDraft): DiagnosticDeclaration {
            val declaration = draft.declaration
            val severity = declaration.severity
            if (severity !is DiagnosticSeverityInfo.Deprecation || draft.languageFeature == null) return declaration
            return declaration.copy(severity = severity.copy(sinceVersion = sinceVersion(draft.languageFeature)))
        }

        private fun KaSession.ofCall(property: KtProperty, name: Name, call: KtCallExpression): DiagnosticDeclarationDraft? {
            val functionCall = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
            val symbol = functionCall.partiallyAppliedSymbol.symbol
            val isDelegate = property.delegateExpression != null
            return when {
                !isDelegate && symbol is KaConstructorSymbol -> ofFactory(property, name, functionCall, symbol)
                isDelegate && symbol is KaNamedFunctionSymbol -> ofDelegate(property, name, call, functionCall, symbol)
                else -> null
            }
        }

        private fun KaSession.ofFactory(
            property: KtProperty,
            name: Name,
            functionCall: KaFunctionCall<*>,
            constructor: KaConstructorSymbol,
        ): DiagnosticDeclarationDraft? {
            val factoryClassId = constructor.containingClassId ?: return null
            if (factoryClassId.packageFqName != DIAGNOSTICS_PACKAGE) return null
            val container = property.containingClassOrObject ?: return null
            if (!isDiagnosticsContainer(container)) return null

            val arguments = functionCall.argumentsByParameterName()
            val factoryName = factoryClassId.shortClassName
            val isSourceless = factoryName == SOURCELESS_FACTORY
            var languageFeature: KtEnumEntry? = null
            val severity = when {
                isSourceless || REGULAR_FACTORY_REGEX.matches(factoryName.asString()) -> {
                    val severityEntry = arguments[SEVERITY]?.let { resolveEnumEntry(it, SEVERITY_CLASS_ID) } ?: return null
                    DiagnosticSeverityInfo.Regular(DiagnosticSeverityBadge.of(severityEntry.name) ?: return null)
                }

                DEPRECATION_FACTORY_REGEX.matches(factoryName.asString()) -> {
                    val featureEntry = arguments[FEATURE_FOR_ERROR]?.let { resolveEnumEntry(it, LANGUAGE_FEATURE_CLASS_ID) }
                    languageFeature = featureEntry?.sourceEntry()
                    DiagnosticSeverityInfo.Deprecation(featureEntry?.name)
                }

                else -> {
                    return null
                }
            }

            val type = property.typeReference?.text?.let { ": $it" }.orEmpty()
            val declaration = DiagnosticDeclaration(
                definitionText = "val $name$type",
                containerFqName = container.fqName,
                severity = severity,
                // Sourceless diagnostics have neither a positioning strategy nor a PSI type.
                positioningStrategy = if (isSourceless) {
                    null
                } else {
                    arguments[DEFAULT_POSITIONING_STRATEGY]?.let { resolvePositioningStrategy(it) } ?: PositioningStrategy.Unknown
                },
                psiType = if (isSourceless) null else arguments[PSI_TYPE]?.let { resolveClassLiteralType(it) } ?: PsiType.Unknown,
            )
            return DiagnosticDeclarationDraft(declaration, languageFeature = languageFeature)
        }

        private fun KaSession.ofDelegate(
            property: KtProperty,
            name: Name,
            call: KtCallExpression,
            functionCall: KaFunctionCall<*>,
            function: KaNamedFunctionSymbol,
        ): DiagnosticDeclarationDraft? {
            val callableId = function.callableId ?: return null
            if (callableId.packageName != DIAGNOSTICS_PACKAGE || callableId.classId != null) return null
            val kind = DelegateKind.of(callableId.callableName) ?: return null

            val arguments = functionCall.argumentsByParameterName()
            var languageFeature: KtEnumEntry? = null
            val severity = severityOfDelegate(kind) {
                val featureEntry = arguments[FEATURE_FOR_ERROR]?.let { resolveEnumEntry(it, LANGUAGE_FEATURE_CLASS_ID) }
                languageFeature = featureEntry?.sourceEntry()
                DiagnosticSeverityInfo.Deprecation(featureEntry?.name)
            }

            val positioningStrategyArgument = arguments[POSITIONING_STRATEGY]
            val positioningStrategy = when {
                positioningStrategyArgument != null -> resolvePositioningStrategy(positioningStrategyArgument)
                // The default value of the parameter is always `SourceElementPositioningStrategies.DEFAULT`.
                function.valueParameters.any { it.name == POSITIONING_STRATEGY } -> PositioningStrategy.Known(DEFAULT_STRATEGY)
                // E.g. `strongWarningWithoutSource()`.
                else -> null
            }

            // The PSI type is the first type parameter of the delegate functions, e.g. `P` in `error1<P : PsiElement, A>`.
            // Sourceless delegates, e.g. `strongWarningWithoutSource()`, have no type parameters.
            val psiType = function.typeParameters.firstOrNull()?.let { psiTypeParameter ->
                functionCall.typeArgumentsMapping[psiTypeParameter]?.let { classIdOf(it) }?.let { PsiType.Known(it) } ?: PsiType.Unknown
            }

            val type = property.typeReference?.text?.let { ": $it" }.orEmpty()
            val typeArguments = call.typeArgumentList?.text.orEmpty()
            val valueArguments = if (call.valueArgumentList != null && call.valueArguments.isEmpty()) "()" else ""
            val declaration = DiagnosticDeclaration(
                definitionText = "val $name$type by ${call.calleeExpression?.text}$typeArguments$valueArguments",
                containerFqName = property.containingClassOrObject?.fqName,
                severity = severity,
                positioningStrategy = positioningStrategy,
                psiType = psiType,
            )
            return DiagnosticDeclarationDraft(declaration, languageFeature)
        }

        /** Maps the [kind] of a delegate function to the severity; [deprecation] is only computed for deprecation diagnostics. */
        private fun severityOfDelegate(kind: DelegateKind, deprecation: () -> DiagnosticSeverityInfo.Deprecation): DiagnosticSeverityInfo =
            when (kind) {
                DelegateKind.ERROR -> DiagnosticSeverityInfo.Regular(DiagnosticSeverityBadge.ERROR)
                DelegateKind.WARNING -> DiagnosticSeverityInfo.Regular(DiagnosticSeverityBadge.WARNING)
                DelegateKind.STRONG_WARNING -> DiagnosticSeverityInfo.Regular(DiagnosticSeverityBadge.STRONG_WARNING)
                DelegateKind.INFO -> DiagnosticSeverityInfo.Regular(DiagnosticSeverityBadge.INFO)
                DelegateKind.DEPRECATION_ERROR -> deprecation()
            }

        /** Returns the version in which the `LanguageFeature` [entry] is enabled, by resolving its constructor arguments. */
        private fun sinceVersion(entry: KtEnumEntry): DiagnosticSeverityInfo.SinceVersion = analyze(entry) {
            val version = constructorArguments(entry)?.get(SINCE_VERSION) ?: return@analyze DiagnosticSeverityInfo.SinceVersion.Unknown
            if (version.evaluate() is KaConstantValue.NullValue) return@analyze DiagnosticSeverityInfo.SinceVersion.Experimental
            val versionEntry = resolveEnumEntry(version, LANGUAGE_VERSION_CLASS_ID)
            versionEntry?.let { parseLanguageVersion(it.name) } ?: DiagnosticSeverityInfo.SinceVersion.Unknown
        }

        private val LANGUAGE_VERSION_NAME_REGEX = Regex("KOTLIN_(\\d+)_(\\d+)")

        /** Parses the name of a `LanguageVersion` entry, e.g. `KOTLIN_2_3`. */
        private fun parseLanguageVersion(entryName: Name): DiagnosticSeverityInfo.SinceVersion.Version? {
            val (major, minor) = LANGUAGE_VERSION_NAME_REGEX.matchEntire(entryName.asString())?.destructured ?: return null
            return DiagnosticSeverityInfo.SinceVersion.Version(major.toInt(), minor.toInt())
        }

        private fun KaSession.isDiagnosticsContainer(container: KtClassOrObject): Boolean {
            val containerSymbol = container.classSymbol ?: return false
            val baseSymbol = findClass(CONTAINER_CLASS_ID) ?: return false
            return containerSymbol.isSubClassOf(baseSymbol)
        }

        private fun KaFunctionCall<*>.argumentsByParameterName(): Map<Name, KtExpression> =
            argumentMapping.entries.associate { (argument, parameter) -> parameter.name to argument }

        /** Returns the arguments of the enum entry constructor call, e.g. `KOTLIN_2_3` and `"KT-1"` in `Feature(KOTLIN_2_3, "KT-1")`. */
        private fun KaSession.constructorArguments(entry: KtEnumEntry): Map<Name, KtExpression>? {
            val superTypeCall = entry.initializerList?.initializers?.firstOrNull() as? KtSuperTypeCallEntry ?: return null
            return superTypeCall.resolveToCall()?.successfulFunctionCallOrNull()?.argumentsByParameterName()
        }

        /** Returns the entry of the enum [enumClassId] which [expression] refers to. */
        private fun KaSession.resolveEnumEntry(expression: KtExpression, enumClassId: ClassId): KaEnumEntrySymbol? {
            val symbol = expression.resolveToCall()?.successfulVariableAccessCall()?.symbol as? KaEnumEntrySymbol ?: return null
            return symbol.takeIf { it.callableId?.classId == enumClassId }
        }

        /** Returns the source of an enum entry, taking attached sources into account for compiled declarations. */
        private fun KaEnumEntrySymbol.sourceEntry(): KtEnumEntry? = psi?.navigationElement as? KtEnumEntry

        /** Returns the class referenced by a class literal, e.g. `PsiElement` for `PsiElement::class`. */
        private fun KaSession.resolveClassLiteralType(expression: KtExpression): PsiType {
            // Only class literals, e.g. not a variable which has the `KClass<*>` type.
            if (expression !is KtClassLiteralExpression) return PsiType.Unknown
            val kClassType = expression.expressionType as? KaClassType ?: return PsiType.Unknown
            if (kClassType.classId != KCLASS_CLASS_ID) return PsiType.Unknown
            val classId = kClassType.typeArguments.firstOrNull()?.type?.let { classIdOf(it) } ?: return PsiType.Unknown
            return PsiType.Known(classId)
        }

        private fun classIdOf(type: KaType): ClassId? = (type as? KaClassType)?.classId

        /**
         * Returns the property of `SourceElementPositioningStrategies` which [expression] refers to directly,
         * e.g. `DEFAULT` for `SourceElementPositioningStrategies.DEFAULT`, or [PositioningStrategy.Unknown] for anything else.
         */
        private fun KaSession.resolvePositioningStrategy(expression: KtExpression): PositioningStrategy {
            val symbol = expression.resolveToCall()?.successfulVariableAccessCall()?.symbol as? KaPropertySymbol
                ?: return PositioningStrategy.Unknown
            if (symbol.callableId?.classId != POSITIONING_STRATEGIES_CLASS_ID) return PositioningStrategy.Unknown
            return PositioningStrategy.Known(symbol.name)
        }
    }
}
