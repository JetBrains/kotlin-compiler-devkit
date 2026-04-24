package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lang.Language
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil

internal abstract class InjectableLanguageInjectionHost(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost = this

    override fun createLiteralTextEscaper(): LiteralTextEscaper<PsiLanguageInjectionHost> =
        object : LiteralTextEscaper<PsiLanguageInjectionHost>(this) {
            override fun decode(
                rangeInsideHost: TextRange,
                outChars: StringBuilder,
            ): Boolean {
                outChars.append(rangeInsideHost.substring(myHost.text))
                return true
            }

            override fun getOffsetInHost(
                offsetInDecoded: Int,
                rangeInsideHost: TextRange,
            ): Int = (rangeInsideHost.startOffset + offsetInDecoded).coerceAtMost(rangeInsideHost.endOffset)

            override fun isOneLine(): Boolean = false
        }
}

internal class MultifileTestDataPreamble(node: ASTNode) : InjectableLanguageInjectionHost(node)

internal class MultifileTestDataEntry(node: ASTNode) : ASTWrapperPsiElement(node) {
    val moduleHeader: MultifileTestDataModuleHeader?
        get() = PsiTreeUtil.getChildOfType(this, MultifileTestDataModuleHeader::class.java)

    val fileHeader: MultifileTestDataFileHeader?
        get() = PsiTreeUtil.getChildOfType(this, MultifileTestDataFileHeader::class.java)

    val content: MultifileTestDataFileContent?
        get() = PsiTreeUtil.getChildOfType(this, MultifileTestDataFileContent::class.java)

    val placeholderText: String
        get() = buildString {
            append("// ")
            val moduleName = moduleHeader?.moduleName?.takeIf(String::isNotBlank)
            moduleName?.let {
                append("MODULE: ")
                append(it)
            }
            fileHeader?.fileName?.takeIf(String::isNotBlank)?.let {
                if (moduleName != null) append(" ")
                append("FILE: ")
                append(it)
            }
        }
}

internal abstract class MultifileTestDataDirective(protected val directiveName: String, node: ASTNode) : ASTWrapperPsiElement(node) {
    protected fun directiveValue(): String =
        this.text.lineSequence().firstOrNull().orEmpty()
            .removePrefix("//")
            .trimStart()
            .removePrefix("$directiveName:")
            .trim()

    val placeholderText: String
        get() = buildString {
            append("// ")
            append(directiveName)
            append(": ")
            append(directiveValue())
        }
}

internal class MultifileTestDataModuleHeader(node: ASTNode) : MultifileTestDataDirective("MODULE", node) {
    val moduleName: String
        get() = directiveValue()
}

internal class MultifileTestDataFileHeader(node: ASTNode) : MultifileTestDataDirective("FILE", node) {
    val fileName: String
        get() = directiveValue()

    val injectedLanguage: Language
        get() = FileTypeRegistry.getInstance()
            .getFileTypeByFileName(fileName)
            .let { fileType -> (fileType as? LanguageFileType)?.language ?: PlainTextLanguage.INSTANCE }
}

internal class MultifileTestDataFileContent(node: ASTNode) : InjectableLanguageInjectionHost(node) {
    val entry: MultifileTestDataEntry?
        get() = parent as? MultifileTestDataEntry
}