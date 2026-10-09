package org.jetbrains.kotlin.test.helper.lang.ir

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrDumpLexerTest {
    @Test
    fun `declaration with qualifier attributes and flags`() {
        assertEquals(
            listOf(
                "KIND:FUN",
                "WHITE_SPACE: ",
                "KIND_QUALIFIER:FAKE_OVERRIDE",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:name:",
                "DECLARATION_NAME:compareTo",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:visibility:",
                "TEXT:public",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:returnType:",
                "TEXT:kotlin.Int",
                "WHITE_SPACE: ",
                "FLAGS:[fake_override,operator]",
            ),
            tokenize("FUN FAKE_OVERRIDE name:compareTo visibility:public returnType:kotlin.Int [fake_override,operator]"),
        )
    }

    @Test
    fun `labeled expression with reference`() {
        assertEquals(
            listOf(
                "WHITE_SPACE:    ",
                "LABEL:receiver:",
                "WHITE_SPACE: ",
                "KIND:GET_VAR",
                "WHITE_SPACE: ",
                "REFERENCE:'<this>: <root>.A declared in <root>.A.<get-x>'",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:type=",
                "TEXT:<root>.A",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:origin=",
                "TEXT:null",
            ),
            tokenize("    receiver: GET_VAR '<this>: <root>.A declared in <root>.A.<get-x>' type=<root>.A origin=null"),
        )
    }

    @Test
    fun constants() {
        assertEquals(
            listOf(
                "LABEL:ARG x:",
                "WHITE_SPACE: ",
                "KIND:CONST",
                "WHITE_SPACE: ",
                "KIND_QUALIFIER:String",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:type=",
                "TEXT:kotlin.String",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:value=",
                """STRING:"say \"hi\"\n"""",
            ),
            tokenize("""ARG x: CONST String type=kotlin.String value="say \"hi\"\n""""),
        )
        assertEquals(
            listOf("KIND:CONST", "WHITE_SPACE: ", "KIND_QUALIFIER:Char", "WHITE_SPACE: ", "ATTRIBUTE_KEY:value=", "STRING:'''"),
            tokenize("CONST Char value='''"),
        )
        assertEquals(
            listOf("KIND:CONST", "WHITE_SPACE: ", "KIND_QUALIFIER:Long", "WHITE_SPACE: ", "ATTRIBUTE_KEY:value=", "NUMBER:-1"),
            tokenize("CONST Long value=-1"),
        )
        assertEquals(
            listOf("KIND:CONST", "WHITE_SPACE: ", "KIND_QUALIFIER:Null", "WHITE_SPACE: ", "ATTRIBUTE_KEY:value=", "LITERAL_KEYWORD:null"),
            tokenize("CONST Null value=null"),
        )
    }

    @Test
    fun `brackets in types are not flags`() {
        assertEquals(
            listOf(
                "KIND:VALUE_PARAMETER",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:superTypes:",
                "TEXT:[kotlin.Any]",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:type:",
                "TEXT:@[FlexibleNullability]",
                "WHITE_SPACE: ",
                "TEXT:kotlin.String?",
            ),
            tokenize("VALUE_PARAMETER superTypes:[kotlin.Any] type:@[FlexibleNullability] kotlin.String?"),
        )
    }

    @Test
    fun `comment inside attribute value`() {
        assertEquals(
            listOf(
                "KIND:PROPERTY",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:visibility:",
                "TEXT:protected",
                "COMMENT:/*protected and package*/",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:modality:",
                "TEXT:FINAL",
                "WHITE_SPACE: ",
                "FLAGS:[var]",
            ),
            tokenize("PROPERTY visibility:protected/*protected and package*/ modality:FINAL [var]"),
        )
    }

    @Test
    fun `free text lines`() {
        assertEquals(
            listOf(
                "LABEL:overridden:",
                "WHITE_SPACE:\\n",
                "WHITE_SPACE:  ",
                "TEXT:protected",
                "COMMENT:/*protected and package*/",
                "TEXT: open fun foo (): kotlin.Unit declared in <root>.A",
                "WHITE_SPACE:\\n",
                "LABEL:annotations:",
                "WHITE_SPACE:\\n",
                "WHITE_SPACE:  ",
                "TEXT:A(x = ",
                "STRING:\"abc\"",
                "TEXT:, y = <null>)",
                "WHITE_SPACE:\\n",
                "COMMENT:// comment",
            ),
            tokenize(
                """
                overridden:
                  protected/*protected and package*/ open fun foo (): kotlin.Unit declared in <root>.A
                annotations:
                  A(x = "abc", y = <null>)
                // comment
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `type arguments are not element kinds`() {
        assertEquals(
            listOf("LABEL:TYPE_ARG T:", "WHITE_SPACE: ", "TEXT:K1 of <root>.Foo"),
            tokenize("TYPE_ARG T: K1 of <root>.Foo"),
        )
        assertEquals(
            listOf("LABEL:TYPE_ARG (of class) T:", "WHITE_SPACE: ", "TEXT:kotlin.Int"),
            tokenize("TYPE_ARG (of class) T: kotlin.Int"),
        )
    }

    @Test
    fun `unclosed quotes end at line end`() {
        assertEquals(
            listOf(
                "KIND:ERROR_EXPR",
                "WHITE_SPACE: ",
                "REFERENCE:'Unresolved",
                "WHITE_SPACE:\\r\\n",
                "KIND:CONST",
                "WHITE_SPACE: ",
                "ATTRIBUTE_KEY:value=",
                "STRING:\"abc",
            ),
            tokenize("ERROR_EXPR 'Unresolved\r\nCONST value=\"abc"),
        )
    }

    @Test
    fun `empty input`() {
        assertEquals(emptyList(), tokenize(""))
    }

    @Test
    fun `arbitrary input is fully covered and restartable at line starts`() {
        val random = Random(42)
        val alphabet = "'\"[]/*:=,@<>.-_ \t\n\rAZaz09"
        repeat(2000) {
            val text = buildString { repeat(random.nextInt(80)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val tokens = lex(text, 0)

            var expectedStart = 0
            for ((_, start, end) in tokens) {
                assertEquals(expectedStart, start, "Gap or overlap in <$text>")
                assertTrue(end > start, "Empty token in <$text>")
                expectedStart = end
            }
            assertEquals(text.length, expectedStart, "Input not fully covered: <$text>")

            for ((index, token) in tokens.withIndex()) {
                if (token.state != 0) continue
                assertTrue(token.start == 0 || text[token.start - 1] == '\n' || text[token.start - 1] == '\r', "Restart point mid-line in <$text>")
                assertEquals(tokens.drop(index), lex(text, token.start), "Restart differs in <$text>")
            }
        }
    }

    private data class Token(val type: String, val start: Int, val end: Int, val state: Int)

    private fun lex(text: String, startOffset: Int): List<Token> {
        val lexer = IrDumpLexer()
        lexer.start(text, startOffset, text.length, 0)
        val tokens = mutableListOf<Token>()
        while (lexer.tokenType != null) {
            tokens += Token(lexer.tokenType.toString(), lexer.tokenStart, lexer.tokenEnd, lexer.state)
            lexer.advance()
        }
        return tokens
    }

    private fun tokenize(text: String): List<String> =
        lex(text, 0).map {
            "${it.type}:${
                text.substring(it.start, it.end)
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
            }"
        }
}
