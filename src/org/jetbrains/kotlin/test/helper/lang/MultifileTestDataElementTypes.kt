package org.jetbrains.kotlin.test.helper.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

internal class MultifileTestDataTokenType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage)

internal class MultifileTestDataElementType(debugName: String) : IElementType(debugName, MultifileTestDataLanguage)

internal val MULTIFILE_TEXT_FILE: IFileElementType = IFileElementType(MultifileTestDataLanguage)

internal val MULTIFILE_PREAMBLE_TEXT = MultifileTestDataTokenType("MULTIFILE_PREAMBLE_TEXT")
internal val MULTIFILE_MODULE_LINE = MultifileTestDataTokenType("MULTIFILE_MODULE_LINE")
internal val MULTIFILE_FILE_LINE = MultifileTestDataTokenType("MULTIFILE_FILE_LINE")
internal val MULTIFILE_CONTENT_TEXT = MultifileTestDataTokenType("MULTIFILE_CONTENT_TEXT")

internal val MULTIFILE_PREAMBLE = MultifileTestDataElementType("MULTIFILE_PREAMBLE")
internal val MULTIFILE_ENTRY = MultifileTestDataElementType("MULTIFILE_ENTRY")
internal val MULTIFILE_MODULE_HEADER = MultifileTestDataElementType("MULTIFILE_MODULE_HEADER")
internal val MULTIFILE_FILE_HEADER = MultifileTestDataElementType("MULTIFILE_FILE_HEADER")
internal val MULTIFILE_FILE_CONTENT = MultifileTestDataElementType("MULTIFILE_FILE_CONTENT")

internal val MULTIFILE_WHITE_SPACES: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
internal val MULTIFILE_COMMENT_TOKENS: TokenSet = TokenSet.EMPTY
