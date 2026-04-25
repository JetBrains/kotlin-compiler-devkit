package org.jetbrains.kotlin.test.helper.lang

import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.HintedReferenceHost
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceService.Hints
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil

/**
 * Root PSI file for Kotlin multifile test data.
 *
 * Structure:
 * - optional [MultifileTestDataPreamble] containing top-level comments, blank lines, or plain text
 * - zero or more [MultifileTestDataEntry] blocks
 *
 * Each [MultifileTestDataEntry] contains:
 * - optional [MultifileTestDataModuleHeader]
 * - required [MultifileTestDataFileHeader]
 * - optional [MultifileTestDataFileContent]
 *
 * The top-level preamble is intentionally shaped like a content block so files without any `// FILE:`
 * separators can still be represented as a single Kotlin-like text body.
 */
class MultifileTestDataTextFileImpl(viewProvider: FileViewProvider) :
    PsiFileImpl(MULTIFILE_TEXT_FILE, MULTIFILE_TEXT_FILE, viewProvider),
    PsiPlainTextFile,
    HintedReferenceHost {
    override fun accept(visitor: PsiElementVisitor) {
        visitor.visitFile(this)
    }

    override fun toString(): String =
        "PsiFile(multifile test data): $name"

    override fun getFileType(): FileType =
        MultifileTestDataFileType

    override fun getReferences(): Array<PsiReference?> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun getReferences(hints: Hints): Array<PsiReference?> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this, hints)

    override fun shouldAskParentForReferences(hints: Hints): Boolean =
        false

    internal val entries: List<MultifileTestDataEntry>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, MultifileTestDataEntry::class.java)
}
