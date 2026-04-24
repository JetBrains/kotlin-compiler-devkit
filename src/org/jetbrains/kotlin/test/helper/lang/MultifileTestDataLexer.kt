package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
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
            isStructuralModuleHeaderAt(tokenStart) -> setToken(MULTIFILE_MODULE_LINE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            else -> {
                tokenType = MULTIFILE_PREAMBLE_TEXT
                tokenEnd = nextBoundaryOffset(tokenStart)
                state = BEFORE_FIRST_ENTRY
            }
        }
    }

    private fun locateAfterModuleHeader() {
        when {
            isWhitespaceLine(tokenStart) -> setToken(TokenType.WHITE_SPACE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            else -> {
                tokenType = MULTIFILE_CONTENT_TEXT
                tokenEnd = nextBoundaryOffset(tokenStart)
                state = AFTER_FILE_HEADER
            }
        }
    }

    private fun locateAfterFileHeader() {
        when {
            isStructuralModuleHeaderAt(tokenStart) -> setToken(MULTIFILE_MODULE_LINE, lineEnd(tokenStart), AFTER_MODULE_HEADER)
            isFileHeaderAt(tokenStart) -> setToken(MULTIFILE_FILE_LINE, lineEnd(tokenStart), AFTER_FILE_HEADER)
            else -> {
                tokenType = MULTIFILE_CONTENT_TEXT
                tokenEnd = nextBoundaryOffset(tokenStart)
                state = AFTER_FILE_HEADER
            }
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

    private fun nextBoundaryOffset(offset: Int): Int {
        var cursor = offset
        while (cursor < endOffset) {
            if (isFileHeaderAt(cursor) || isStructuralModuleHeaderAt(cursor)) {
                return cursor
            }
            cursor = lineEnd(cursor)
        }
        return endOffset
    }

    private fun isStructuralModuleHeaderAt(offset: Int): Boolean {
        if (!isDirectiveLine(offset, "MODULE")) {
            return false
        }

        var nextOffset = lineEnd(offset)
        while (nextOffset < endOffset && isWhitespaceLine(nextOffset)) {
            nextOffset = lineEnd(nextOffset)
        }

        return nextOffset < endOffset && isFileHeaderAt(nextOffset)
    }

    private fun isFileHeaderAt(offset: Int): Boolean = isDirectiveLine(offset, "FILE")

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

    private fun isWhitespaceLine(offset: Int): Boolean {
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
