package org.jetbrains.kotlin.test.helper.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.ParserDefinition.SpaceRequirements
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class MultifileTestDataParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = MultifileTestDataLexer()

    override fun createParser(project: Project?): PsiParser = MultifileTestDataParser()

    override fun getFileNodeType(): IFileElementType = MULTIFILE_TEXT_FILE

    override fun getWhitespaceTokens(): TokenSet = MULTIFILE_WHITE_SPACES

    override fun getCommentTokens(): TokenSet = MULTIFILE_COMMENT_TOKENS

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement =
        when (node.elementType) {
            MULTIFILE_PREAMBLE -> MultifileTestDataPreamble(node)
            MULTIFILE_ENTRY -> MultifileTestDataEntry(node)
            MULTIFILE_MODULE_HEADER -> MultifileTestDataModuleHeader(node)
            MULTIFILE_FILE_HEADER -> MultifileTestDataFileHeader(node)
            MULTIFILE_FILE_CONTENT -> MultifileTestDataFileContent(node)
            MULTIFILE_TEXT_BLOCK_ELEMENT -> MultifileTestDataTextBlock(node)
            else -> ASTWrapperPsiElement(node)
        }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = MultifileTestDataTextFileImpl(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(
        left: ASTNode,
        right: ASTNode,
    ): SpaceRequirements = SpaceRequirements.MAY
}
