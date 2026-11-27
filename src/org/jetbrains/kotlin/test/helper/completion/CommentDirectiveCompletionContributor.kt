package org.jetbrains.kotlin.test.helper.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.idea.completion.or
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.test.helper.lang.MULTIFILE_COMMENT_LINE
import org.jetbrains.kotlin.test.helper.reference.fieldLikeMembers
import org.jetbrains.kotlin.test.helper.reference.getEnumClassesByDirective
import org.jetbrains.kotlin.test.helper.reference.getFirErrorClasses
import org.jetbrains.kotlin.test.helper.reference.getInheritors
import org.jetbrains.kotlin.test.helper.reference.getLanguageFeatureClasses
import org.jetbrains.kotlin.test.helper.reference.isDirective

class CommentDirectiveCompletionContributor : CompletionContributor() {
    init {
        val ktComment = PlatformPatterns.psiElement().withElementType(KtTokens.EOL_COMMENT)
        val multifileComment = PlatformPatterns.psiElement().withElementType(MULTIFILE_COMMENT_LINE)
        extend(
            CompletionType.BASIC,
            ktComment or multifileComment,
            CommentDirectiveCompletionProvider()
        )
    }
}

private const val DIRECTIVES_CONTAINER_FQ_NAME = "org.jetbrains.kotlin.test.directives.model.DirectivesContainer"

class CommentDirectiveCompletionProvider : CompletionProvider<CompletionParameters>() {
    private val regex = Regex("// ([A-Z0-9_]+):")

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet
    ) {
        val project = parameters.position.project

        if (parameters.originalFile.virtualFile.isUnderSourceRoot(project)) {
            return
        }

        // Completion should only be performed inside comments
        val text = (parameters.position as? PsiComment)?.text ?: return
        when {
            text.startsWith("// LANGUAGE") ->
                completeToFieldMembers(getLanguageFeatureClasses(project), resultSet)
            text.startsWith("// DIAGNOSTICS") ->
                completeToFieldMembers(getFirErrorClasses(project), resultSet)
            else -> {
                val result = regex.matchAt(text, 0)
                result?.groupValues?.elementAtOrNull(1)?.let {
                    completeToFieldMembers(getEnumClassesByDirective(it, project), resultSet)
                    return
                }
                completeDirectives(project, resultSet)
            }
        }
    }

    private fun completeToFieldMembers(classes: Collection<KtClassOrObject>, resultSet: CompletionResultSet) {
        classes.flatMap { it.fieldLikeMembers }
            .mapNotNullTo(mutableSetOf()) { it.name }
            .forEach { resultSet.addElement(LookupElementBuilder.create(it)) }
    }

    private fun completeDirectives(
        project: Project,
        resultSet: CompletionResultSet
    ) {
        val inheritors = getInheritors(project, DIRECTIVES_CONTAINER_FQ_NAME)

        for (clazz in inheritors) {
            clazz.declarations
                .filterIsInstance<KtProperty>()
                .filter { it.isDirective() }
                .forEach { directive ->
                    resultSet.addElement(LookupElementBuilder.create(directive.name ?: return@forEach))
                }
        }
    }

    private fun VirtualFile.isUnderSourceRoot(project: Project): Boolean {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        return fileIndex.isInSourceContent(this)
    }
}

