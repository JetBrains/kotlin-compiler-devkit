package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class IrDumpSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = IrDumpLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(ATTRIBUTES[tokenType])

    companion object {
        val KIND = createTextAttributesKey("KOTLIN_IR_DUMP_KIND", DefaultLanguageHighlighterColors.KEYWORD)
        val KIND_QUALIFIER = createTextAttributesKey("KOTLIN_IR_DUMP_KIND_QUALIFIER", DefaultLanguageHighlighterColors.CONSTANT)
        val LABEL = createTextAttributesKey("KOTLIN_IR_DUMP_LABEL", DefaultLanguageHighlighterColors.LABEL)
        val ATTRIBUTE_KEY = createTextAttributesKey("KOTLIN_IR_DUMP_ATTRIBUTE_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val DECLARATION_NAME =
            createTextAttributesKey("KOTLIN_IR_DUMP_DECLARATION_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        val REFERENCE = createTextAttributesKey("KOTLIN_IR_DUMP_REFERENCE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)
        val STRING = createTextAttributesKey("KOTLIN_IR_DUMP_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = createTextAttributesKey("KOTLIN_IR_DUMP_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val LITERAL_KEYWORD = createTextAttributesKey("KOTLIN_IR_DUMP_LITERAL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val FLAGS = createTextAttributesKey("KOTLIN_IR_DUMP_FLAGS", DefaultLanguageHighlighterColors.METADATA)
        val COMMENT = createTextAttributesKey("KOTLIN_IR_DUMP_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)

        private val ATTRIBUTES: Map<IElementType, TextAttributesKey> = mapOf(
            IrDumpTokenTypes.KIND to KIND,
            IrDumpTokenTypes.KIND_QUALIFIER to KIND_QUALIFIER,
            IrDumpTokenTypes.LABEL to LABEL,
            IrDumpTokenTypes.ATTRIBUTE_KEY to ATTRIBUTE_KEY,
            IrDumpTokenTypes.DECLARATION_NAME to DECLARATION_NAME,
            IrDumpTokenTypes.REFERENCE to REFERENCE,
            IrDumpTokenTypes.STRING to STRING,
            IrDumpTokenTypes.NUMBER to NUMBER,
            IrDumpTokenTypes.LITERAL_KEYWORD to LITERAL_KEYWORD,
            IrDumpTokenTypes.FLAGS to FLAGS,
            IrDumpTokenTypes.COMMENT to COMMENT,
        )
    }
}

class IrDumpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = IrDumpSyntaxHighlighter()
}
