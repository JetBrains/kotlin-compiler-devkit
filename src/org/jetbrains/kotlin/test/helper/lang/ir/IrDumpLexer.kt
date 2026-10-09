package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.ATTRIBUTE_KEY
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.COMMENT
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.DECLARATION_NAME
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.FLAGS
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.KIND
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.KIND_QUALIFIER
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.LABEL
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.LITERAL_KEYWORD
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.NUMBER
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.REFERENCE
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.STRING
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.TEXT
import org.jetbrains.kotlin.test.helper.lang.ir.IrDumpTokenTypes.WHITE_SPACE

/**
 * A deliberately lenient, line-based lexer for IR dumps.
 *
 * Every line is tokenized independently and anything that isn't recognized becomes [TEXT], so changes in the dump
 * format can only degrade highlighting but never break it. Only the first token of a line has the initial state,
 * which makes incremental re-lexing always restart at a line start.
 */
class IrDumpLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset: Int = 0

    private var lineStart: Int = 0
    private val lineTokenTypes = ArrayList<IElementType>()
    private val lineTokenEnds = ArrayList<Int>()
    private var lineTokenIndex: Int = 0

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
    ) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenizeLine(startOffset)
    }

    override fun getState(): Int = if (lineTokenIndex == 0) LINE_START else MID_LINE

    override fun getTokenType(): IElementType? = lineTokenTypes.getOrNull(lineTokenIndex)

    override fun getTokenStart(): Int = if (lineTokenIndex == 0) lineStart else lineTokenEnds[lineTokenIndex - 1]

    override fun getTokenEnd(): Int = lineTokenEnds.getOrElse(lineTokenIndex) { lineStart }

    override fun advance() {
        if (lineTokenIndex >= lineTokenTypes.size) return
        lineTokenIndex++
        if (lineTokenIndex == lineTokenTypes.size) {
            tokenizeLine(lineTokenEnds.last())
        }
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    private fun tokenizeLine(start: Int) {
        lineStart = start
        lineTokenIndex = 0
        lineTokenTypes.clear()
        lineTokenEnds.clear()
        if (start >= endOffset) return

        val contentEnd = lineEndWithoutSeparator(start)
        val contentStart = emit(WHITE_SPACE, skipBlanks(start, contentEnd))
        if (startsWith(contentStart, contentEnd, "//")) {
            emit(COMMENT, contentEnd)
        } else {
            tokenizeContent(contentStart, contentEnd)
        }
        emit(WHITE_SPACE, lineEnd(contentEnd))
    }

    private fun tokenizeContent(start: Int, end: Int) {
        var pos = start
        val labelEnd = labelEnd(pos, end)
        var isTypeArgument = false
        if (labelEnd != -1) {
            isTypeArgument = startsWith(pos, end, "TYPE_ARG ")
            emit(LABEL, labelEnd)
            pos = emit(WHITE_SPACE, skipBlanks(labelEnd, end))
        }

        // The value of a type argument is a type, which might look like an element kind (e.g. `T1`).
        val kindEnd = if (isTypeArgument) -1 else kindEnd(pos, end)
        if (kindEnd == -1) {
            tokenizeFreeText(pos, end)
            return
        }
        pos = emit(KIND, kindEnd)

        while (true) {
            val qualifierStart = skipBlanks(pos, end)
            val qualifierEnd = qualifierEnd(qualifierStart, end)
            if (qualifierEnd == -1) break
            emit(WHITE_SPACE, qualifierStart)
            pos = emit(KIND_QUALIFIER, qualifierEnd)
        }

        tokenizeAttributes(pos, end)
    }

    private fun tokenizeAttributes(start: Int, end: Int) {
        var pos = start
        while (pos < end) {
            val c = buffer[pos]
            val keyEnd = attributeKeyEnd(pos, end)
            val flagsEnd = flagsEnd(pos, end)
            pos = when {
                isBlank(c) -> emit(WHITE_SPACE, skipBlanks(pos, end))
                c == '\'' -> emit(REFERENCE, quotedEnd(pos, end))
                c == '"' -> emit(STRING, stringEnd(pos, end))
                startsWith(pos, end, "/*") -> emit(COMMENT, commentEnd(pos, end))
                flagsEnd != -1 -> emit(FLAGS, flagsEnd)
                keyEnd != -1 -> {
                    emit(ATTRIBUTE_KEY, keyEnd)
                    tokenizeAttributeValue(pos, keyEnd, end)
                }
                else -> emit(TEXT, textEnd(pos, end, stopAtQuote = true))
            }
        }
    }

    /**
     * Highlights simple attribute values. Quoted values and everything after the first blank are left to the caller.
     */
    private fun tokenizeAttributeValue(keyStart: Int, valueStart: Int, end: Int): Int {
        if (valueStart >= end) return valueStart
        val keyLength = valueStart - keyStart - 1
        val isName = keyLength == 4 && startsWith(keyStart, end, "name")
        val isValue = keyLength == 5 && startsWith(keyStart, end, "value")

        val c = buffer[valueStart]
        if (isValue && c == '\'') return emit(STRING, quotedEnd(valueStart, end))
        if (isBlank(c) || c == '\'' || c == '"' || startsWith(valueStart, end, "/*")) return valueStart

        val valueEnd = textEnd(valueStart, end, stopAtQuote = false)
        val type = when {
            isName -> DECLARATION_NAME
            isValue && NUMBER_REGEX.matches(buffer.subSequence(valueStart, valueEnd)) -> NUMBER
            isValue && buffer.subSequence(valueStart, valueEnd).toString() in LITERAL_KEYWORDS -> LITERAL_KEYWORD
            else -> TEXT
        }
        return emit(type, valueEnd)
    }

    /** Lines without an element kind (signatures, annotations, types) only get strings and comments highlighted. */
    private fun tokenizeFreeText(start: Int, end: Int) {
        var pos = start
        while (pos < end) {
            val c = buffer[pos]
            pos = when {
                c == '"' -> emit(STRING, stringEnd(pos, end))
                startsWith(pos, end, "/*") -> emit(COMMENT, commentEnd(pos, end))
                isBlank(c) -> emit(TEXT, skipBlanks(pos, end))
                else -> emit(TEXT, textEnd(pos, end, stopAtQuote = false))
            }
        }
    }

    /**
     * Appends a token ending at [end] (empty tokens are dropped, adjacent text and whitespace tokens are merged).
     * Returns [end].
     */
    private fun emit(type: IElementType, end: Int): Int {
        val currentEnd = lineTokenEnds.lastOrNull() ?: lineStart
        if (end <= currentEnd) return currentEnd
        if ((type == TEXT || type == WHITE_SPACE) && lineTokenTypes.lastOrNull() == type) {
            lineTokenEnds[lineTokenEnds.lastIndex] = end
        } else {
            lineTokenTypes += type
            lineTokenEnds += end
        }
        return end
    }

    /** `label: `, `label:` at the end of the line, `ARG name: `, `TYPE_ARG (of class) T: `, `bound name: `. */
    private fun labelEnd(start: Int, end: Int): Int {
        val wordEnd = wordEnd(start, end)
        if (wordEnd == start) return -1
        if (isLabelColon(wordEnd, end)) return wordEnd + 1

        if (wordEnd < end && buffer[wordEnd] == ' ' && NAMED_LABEL_PREFIXES.any { it.length == wordEnd - start && startsWith(start, end, it) }) {
            var nameStart = wordEnd + 1
            if (startsWith(nameStart, end, OF_CLASS_PREFIX)) nameStart += OF_CLASS_PREFIX.length
            var i = nameStart
            while (i < end && !isBlank(buffer[i]) && buffer[i] != ':') i++
            if (i > nameStart && isLabelColon(i, end)) return i + 1
        }
        return -1
    }

    private fun isLabelColon(offset: Int, end: Int): Boolean =
        offset < end && buffer[offset] == ':' && (offset + 1 == end || isBlank(buffer[offset + 1]))

    /** An `UPPER_SNAKE_CASE` word of at least three characters, e.g. `FUN` or `BLOCK_BODY`. */
    private fun kindEnd(start: Int, end: Int): Int {
        if (start >= end || buffer[start] !in 'A'..'Z') return -1
        var i = start + 1
        while (i < end && (buffer[i] in 'A'..'Z' || buffer[i] in '0'..'9' || buffer[i] == '_')) i++
        if (i - start < 3) return -1
        // Source offsets may directly follow the kind, e.g. `CALL[12, 34]`.
        return if (i == end || isBlank(buffer[i]) || buffer[i] == '[') i else -1
    }

    /** A bare capitalized word followed by a blank, e.g. `FAKE_OVERRIDE` or `Int`. */
    private fun qualifierEnd(start: Int, end: Int): Int {
        if (start >= end || buffer[start] !in 'A'..'Z') return -1
        val i = wordEnd(start, end)
        return if (i == end || isBlank(buffer[i])) i else -1
    }

    /** `key:` or `key=` at the start of a word, returns the offset after the separator. */
    private fun attributeKeyEnd(start: Int, end: Int): Int {
        if (start > lineStart && !isBlank(buffer[start - 1])) return -1
        if (!isIdentifierStart(buffer[start])) return -1
        val i = wordEnd(start, end)
        return if (i < end && (buffer[i] == ':' || buffer[i] == '=')) i + 1 else -1
    }

    /** ` [flag1,flag2]` followed by a blank or the line end. */
    private fun flagsEnd(start: Int, end: Int): Int {
        if (buffer[start] != '[' || start == lineStart || !isBlank(buffer[start - 1])) return -1
        var i = start + 1
        while (true) {
            if (i >= end || !isIdentifierStart(buffer[i])) return -1
            i = wordEnd(i, end)
            if (i >= end) return -1
            when (buffer[i]) {
                ',' -> i++
                ']' -> break
                else -> return -1
            }
        }
        i++
        return if (i == end || isBlank(buffer[i])) i else -1
    }

    /** A `'`-quoted span closed by a `'` that is followed by a blank or the line end, or the line end otherwise. */
    private fun quotedEnd(start: Int, end: Int): Int {
        var i = start + 1
        while (i < end) {
            if (buffer[i] == '\'' && (i + 1 == end || isBlank(buffer[i + 1]))) return i + 1
            i++
        }
        return end
    }

    /** A `"`-quoted string with backslash escapes, or until the line end if unclosed. */
    private fun stringEnd(start: Int, end: Int): Int {
        var i = start + 1
        while (i < end) {
            when (buffer[i]) {
                '\\' -> i++
                '"' -> return i + 1
            }
            i++
        }
        return end
    }

    private fun commentEnd(start: Int, end: Int): Int {
        var i = start + 2
        while (i + 1 < end) {
            if (buffer[i] == '*' && buffer[i + 1] == '/') return i + 2
            i++
        }
        return end
    }

    /** Consumes at least one character, then stops before a blank, a block comment or (optionally) a quote. */
    private fun textEnd(start: Int, end: Int, stopAtQuote: Boolean): Int {
        var i = start + 1
        while (i < end) {
            val c = buffer[i]
            if (isBlank(c) || c == '"' || (stopAtQuote && c == '\'') || startsWith(i, end, "/*")) break
            i++
        }
        return i
    }

    private fun wordEnd(start: Int, end: Int): Int {
        var i = start
        while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
        return i
    }

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

    private fun skipBlanks(start: Int, end: Int): Int {
        var i = start
        while (i < end && isBlank(buffer[i])) i++
        return i
    }

    private fun startsWith(offset: Int, end: Int, prefix: String): Boolean {
        if (offset + prefix.length > end) return false
        for (index in prefix.indices) {
            if (buffer[offset + index] != prefix[index]) return false
        }
        return true
    }

    private fun lineEndWithoutSeparator(offset: Int): Int {
        var i = offset
        while (i < endOffset && buffer[i] != '\n' && buffer[i] != '\r') i++
        return i
    }

    private fun lineEnd(contentEnd: Int): Int {
        var i = contentEnd
        if (i < endOffset && buffer[i] == '\r') i++
        if (i < endOffset && buffer[i] == '\n') i++
        return i
    }

    private companion object {
        const val LINE_START = 0
        const val MID_LINE = 1

        val NAMED_LABEL_PREFIXES = listOf("ARG", "TYPE_ARG", "bound")
        const val OF_CLASS_PREFIX = "(of class) "

        val NUMBER_REGEX = Regex("""-?(\d[\w.+\-]*|NaN|Infinity)""")
        val LITERAL_KEYWORDS = setOf("true", "false", "null")
    }
}
