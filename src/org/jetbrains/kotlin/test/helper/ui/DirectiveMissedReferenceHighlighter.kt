package org.jetbrains.kotlin.test.helper.ui

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor
import org.jetbrains.kotlin.test.helper.lang.KotlinTestDataLanguage
import org.jetbrains.kotlin.test.helper.reference.EnumValueReference
import org.jetbrains.kotlin.test.helper.reference.TestDirectiveReference

class DirectiveMissedReferenceHighlighter : HighlightVisitor {
    private var diagnosticsMap: Map<PsiElement, List<HighlightInfo.Builder>> = emptyMap()
    private var holder: HighlightInfoHolder? = null

    override fun analyze(file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable): Boolean {
        this.holder = holder
        try {
            diagnosticsMap = analyzeFile(file)
            action.run()
        } catch (e: Throwable) {
            if (Logger.shouldRethrow(e)) throw e
            throw e
        } finally {
            // do not leak Editor, since KotlinDiagnosticHighlightVisitor is a project-level extension
            this.diagnosticsMap = emptyMap()
            this.holder = null
        }
        return true
    }

    override fun suitableForFile(psiFile: PsiFile): Boolean {
        return psiFile.language is KotlinTestDataLanguage
    }

    override fun visit(element: PsiElement) {
        if (element !is PsiComment) return
        val diagnostics = diagnosticsMap[element] ?: return
        for (builder in diagnostics) {
            val info = builder.create() ?: continue
            holder!!.add(info)
        }
    }

    override fun clone(): HighlightVisitor {
        return DirectiveMissedReferenceHighlighter()
    }

    fun analyzeFile(file: PsiFile): Map<PsiElement, List<HighlightInfo.Builder>> {
        val result = mutableMapOf<PsiElement, MutableList<HighlightInfo.Builder>>()
        val visitor = HighlightingVisitor(result)
        file.accept(visitor)
        return result
    }

    private class HighlightingVisitor(
        val destination: MutableMap<PsiElement, MutableList<HighlightInfo.Builder>>
    ) : PsiRecursiveElementVisitor() {
        override fun visitComment(comment: PsiComment) {
            for (reference in comment.references) {
                if (reference !is TestDirectiveReference && reference !is EnumValueReference) continue
                if (reference.resolve() != null) continue
                val info = HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING)
                    .range(reference.absoluteRange)
                    .description("Cannot resolve reference")
                val container = destination.getOrPut(comment) { mutableListOf() }
                container += info
            }
        }

    }
}

class KotlinTestDataHighlightingPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {
    override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
        registrar.registerTextEditorHighlightingPass(
          /* factory = */ this,
          /* runAfterCompletionOf = */ null,
          /* runAfterStartingOf = */ null,
          /* runIntentionsPassAfter = */ false,
          /* forcedPassId = */ -1
        )
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (file.language !is KotlinTestDataLanguage) return null

        return KotlinTestDataHighlightingPass(file.project, editor.document, file)
    }
}

class KotlinTestDataHighlightingPass(project: Project, document: Document, val file: PsiFile) : TextEditorHighlightingPass(project, document) {
    private val highlighter = DirectiveMissedReferenceHighlighter()
    private var diagnosticsMap: Map<PsiElement, List<HighlightInfo.Builder>> = emptyMap()

    override fun doCollectInformation(p0: ProgressIndicator) {
        diagnosticsMap = highlighter.analyzeFile(file)
    }

    override fun doApplyInformationToEditor() {
        UpdateHighlightersUtil.setHighlightersToEditor(
            file.project,
            document,
            0,
            document.textLength,
            diagnosticsMap.values.flatten().map { it.create() },
            null,
            0,
        )
    }
}
