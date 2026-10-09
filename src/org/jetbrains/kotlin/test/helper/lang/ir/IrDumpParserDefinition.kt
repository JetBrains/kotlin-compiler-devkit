package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * IR dumps are only highlighted, so the PSI is a flat list of tokens.
 */
class IrDumpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = IrDumpLexer()

    override fun createParser(project: Project?): PsiParser = IrDumpParser()

    override fun getFileNodeType(): IFileElementType = IrDumpTokenTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = IrDumpTokenTypes.WHITE_SPACES

    override fun getCommentTokens(): TokenSet = IrDumpTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = IrDumpTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = IrDumpFile(viewProvider)
}

private class IrDumpParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        marker.done(root)
        return builder.treeBuilt
    }
}

class IrDumpFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, IrDumpLanguage) {
    override fun getFileType(): FileType = IrDumpFileType

    override fun toString(): String = "Kotlin IR dump file"
}
