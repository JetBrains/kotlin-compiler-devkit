package org.jetbrains.kotlin.test.helper.lang

import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.tree.ILeafElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

internal class MultifileTestDataTokenType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage)

internal class MultifileTestDataCommentTokenType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage), ILeafElementType {
    override fun createLeafNode(leafText: CharSequence): LeafElement = PsiCommentImpl(this, leafText)
}

internal class MultifileTestDataWhiteSpaceTokenType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage), ILeafElementType {
    override fun createLeafNode(leafText: CharSequence): LeafElement = PsiWhiteSpaceImpl(leafText)
}

internal class MultifileTestDataElementType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage)

internal val MULTIFILE_TEXT_FILE: IFileElementType = IFileElementType(MultifileTestDataLanguage)

internal val MULTIFILE_COMMENT_LINE = MultifileTestDataCommentTokenType("MULTIFILE_COMMENT_LINE")
internal val MULTIFILE_MODULE_LINE = MultifileTestDataCommentTokenType("MULTIFILE_MODULE_LINE")
internal val MULTIFILE_FILE_LINE = MultifileTestDataCommentTokenType("MULTIFILE_FILE_LINE")
internal val MULTIFILE_TEXT_BLOCK = MultifileTestDataTokenType("MULTIFILE_TEXT_BLOCK")
internal val MULTIFILE_NEW_LINE = MultifileTestDataWhiteSpaceTokenType("MULTIFILE_NEW_LINE")

internal val MULTIFILE_PREAMBLE = MultifileTestDataElementType("MULTIFILE_PREAMBLE")
internal val MULTIFILE_ENTRY = MultifileTestDataElementType("MULTIFILE_ENTRY")
internal val MULTIFILE_MODULE_HEADER = MultifileTestDataElementType("MULTIFILE_MODULE_HEADER")
internal val MULTIFILE_FILE_HEADER = MultifileTestDataElementType("MULTIFILE_FILE_HEADER")
internal val MULTIFILE_FILE_CONTENT = MultifileTestDataElementType("MULTIFILE_FILE_CONTENT")
internal val MULTIFILE_TEXT_BLOCK_ELEMENT = MultifileTestDataElementType("MULTIFILE_TEXT_BLOCK_ELEMENT")

internal val MULTIFILE_WHITE_SPACES: TokenSet = TokenSet.EMPTY
internal val MULTIFILE_COMMENT_TOKENS: TokenSet = TokenSet.EMPTY
