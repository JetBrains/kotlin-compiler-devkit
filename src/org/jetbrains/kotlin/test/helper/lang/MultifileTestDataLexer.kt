package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class MultifileTestDataLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var state: Int = BEFORE_FIRST_ENTRY

    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
    ) {
        this.buffer = buffer
        this.endOffset = endOffset
        state = initialState
        tokenStart = startOffset
        locateToken()
    }

    override fun getState(): Int = state

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        tokenStart = tokenEnd
        locateToken()
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    private fun locateToken() {
        if (tokenStart >= endOffset) {
            tokenType = null
            tokenEnd = endOffset
            return
        }

        when (state) {
            BEFORE_FIRST_ENTRY -> locateBeforeFirstEntry()
            AFTER_MODULE_HEADER -> locateAfterModuleHeader()
            AFTER_FILE_HEADER -> locateAfterFileHeader()
            else -> error("Unexpected state: $state")
        }
    }

    private fun locateBeforeFirstEntry() {
        when {
            isModuleHeaderAt(tokenStart) -> setToken(MULTIFILE_MODULE_LINE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            isBlankLine(tokenStart) -> setToken(MULTIFILE_NEW_LINE, lineEnd(tokenStart), BEFORE_FIRST_ENTRY)
            isCommentLine(tokenStart) -> setToken(MULTIFILE_COMMENT_LINE, lineEnd(tokenStart), BEFORE_FIRST_ENTRY)
            else -> setToken(MULTIFILE_TEXT_BLOCK, nextTextBlockEnd(tokenStart), BEFORE_FIRST_ENTRY)
        }
    }

    private fun locateAfterModuleHeader() {
        when {
            isBlankLine(tokenStart) -> setToken(MULTIFILE_NEW_LINE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            isCommentLine(tokenStart) -> setToken(MULTIFILE_COMMENT_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            else -> setToken(MULTIFILE_TEXT_BLOCK, nextTextBlockEnd(tokenStart), AFTER_FILE_HEADER)
        }
    }

    private fun locateAfterFileHeader() {
        when {
            isModuleHeaderAt(tokenStart) -> setToken(MULTIFILE_MODULE_LINE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            isBlankLine(tokenStart) -> setToken(MULTIFILE_NEW_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            isCommentLine(tokenStart) -> setToken(MULTIFILE_COMMENT_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            else -> setToken(MULTIFILE_TEXT_BLOCK, nextTextBlockEnd(tokenStart), AFTER_FILE_HEADER)
        }
    }

    private fun setToken(
        type: IElementType,
        end: Int,
        newState: Int,
    ) {
        tokenType = type
        tokenEnd = end
        state = newState
    }

    private fun isModuleHeaderAt(offset: Int): Boolean = isDirectiveLine(offset, "MODULE")

    private fun nextTextBlockEnd(offset: Int): Int {
        var cursor = offset
        while (cursor < endOffset) {
            if (isModuleHeaderAt(cursor) || isFileHeaderAt(cursor)) {
                return cursor
            }
            cursor = lineEnd(cursor)
        }
        return endOffset
    }

    private fun isFileHeaderAt(offset: Int): Boolean = isDirectiveLine(offset, "FILE")

    private fun isCommentLine(offset: Int): Boolean =
        startsWithDoubleSlash(offset) && !isFileHeaderAt(offset) && !isModuleHeaderAt(offset)

    private fun isDirectiveLine(
        offset: Int,
        directiveName: String,
    ): Boolean {
        if (offset >= endOffset || buffer[offset] != '/' || offset + 1 >= endOffset || buffer[offset + 1] != '/') {
            return false
        }

        var cursor = offset + 2
        while (cursor < endOffset && (buffer[cursor] == ' ' || buffer[cursor] == '\t')) {
            cursor++
        }

        val directivePrefix = "$directiveName:"
        if (cursor + directivePrefix.length > endOffset) {
            return false
        }

        for (index in directivePrefix.indices) {
            if (buffer[cursor + index] != directivePrefix[index]) {
                return false
            }
        }

        return cursor + directivePrefix.length <= lineEndWithoutSeparator(offset)
    }

    private fun startsWithDoubleSlash(offset: Int): Boolean =
        offset + 1 < endOffset && buffer[offset] == '/' && buffer[offset + 1] == '/'

    private fun isBlankLine(offset: Int): Boolean {
        val end = lineEndWithoutSeparator(offset)
        for (index in offset until end) {
            if (!buffer[index].isWhitespace()) {
                return false
            }
        }
        return true
    }

    private fun lineEnd(offset: Int): Int {
        var cursor = offset
        while (cursor < endOffset) {
            val character = buffer[cursor]
            cursor++
            if (character == '\n') {
                return cursor
            }
            if (character == '\r') {
                if (cursor < endOffset && buffer[cursor] == '\n') {
                    cursor++
                }
                return cursor
            }
        }
        return endOffset
    }

    private fun lineEndWithoutSeparator(offset: Int): Int {
        var cursor = offset
        while (cursor < endOffset) {
            val character = buffer[cursor]
            if (character == '\n' || character == '\r') {
                return cursor
            }
            cursor++
        }
        return endOffset
    }

    private companion object {
        const val BEFORE_FIRST_ENTRY = 0
        const val AFTER_MODULE_HEADER = 1
        const val AFTER_FILE_HEADER = 2
    }
}
