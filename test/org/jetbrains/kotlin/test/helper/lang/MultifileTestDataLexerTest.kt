package org.jetbrains.kotlin.test.helper.lang

import kotlin.test.Test
import kotlin.test.assertEquals

class MultifileTestDataLexerTest {
    @Test
    fun `keeps preamble before first file and parses subsequent entries`() {
        assertEquals(
            listOf(
                "MULTIFILE_COMMENT_LINE:// FIR_IDENTICAL\\n",
                "MULTIFILE_COMMENT_LINE:// SKIP_TXT\\n",
                "MULTIFILE_NEW_LINE:\\n",
                "MULTIFILE_MODULE_LINE:// MODULE: moduleA\\n",
                "MULTIFILE_FILE_LINE:// FILE: a.kt\\n",
                "MULTIFILE_TEXT_BLOCK:package sample\\n\\nclass A\\n",
                "MULTIFILE_FILE_LINE:// FILE: b.java\\n",
                "MULTIFILE_TEXT_BLOCK:class B {}",
            ),
            tokenize(
                """
                // FIR_IDENTICAL
                // SKIP_TXT
                
                // MODULE: moduleA
                // FILE: a.kt
                package sample
                
                class A
                // FILE: b.java
                class B {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `treats non comment text before first file as content not preamble`() {
        assertEquals(
            listOf(
                "MULTIFILE_TEXT_BLOCK:package stray\\n",
                "MULTIFILE_FILE_LINE:// FILE: sample.kt\\n",
                "MULTIFILE_TEXT_BLOCK:class A",
            ),
            tokenize(
                """
                package stray
                // FILE: sample.kt
                class A
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unsplit file may live entirely in top level preamble block`() {
        assertEquals(
            listOf(
                "MULTIFILE_COMMENT_LINE:// LANGUAGE: +ContextParameters\\n",
                "MULTIFILE_NEW_LINE:\\n",
                "MULTIFILE_TEXT_BLOCK:package sample\\n\\nclass A",
            ),
            tokenize(
                """
                // LANGUAGE: +ContextParameters
                
                package sample
                
                class A
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `content block may start with comment preamble before actual text`() {
        assertEquals(
            listOf(
                "MULTIFILE_FILE_LINE:// FILE: sample.kt\\n",
                "MULTIFILE_COMMENT_LINE:// LANGUAGE: +ContextParameters\\n",
                "MULTIFILE_COMMENT_LINE:// WITH_STDLIB\\n",
                "MULTIFILE_NEW_LINE:\\n",
                "MULTIFILE_TEXT_BLOCK:class A",
            ),
            tokenize(
                """
                // FILE: sample.kt
                // LANGUAGE: +ContextParameters
                // WITH_STDLIB
                
                class A
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `does not split on indented file marker inside content`() {
        assertEquals(
            listOf(
                "MULTIFILE_FILE_LINE:// FILE: sample.kt\\n",
                "MULTIFILE_TEXT_BLOCK:fun example() {\\n    // FILE: not-a-header.kt\\n}",
            ),
            tokenize(
                """
                // FILE: sample.kt
                fun example() {
                    // FILE: not-a-header.kt
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `treats module header inside content as plain text when no file follows`() {
        assertEquals(
            listOf(
                "MULTIFILE_FILE_LINE:// FILE: sample.kt\\n",
                "MULTIFILE_TEXT_BLOCK:fun example() {\\n// MODULE: not-structural\\nprintln(42)\\n}",
            ),
            tokenize(
                """
                // FILE: sample.kt
                fun example() {
                // MODULE: not-structural
                println(42)
                }
                """.trimIndent(),
            ),
        )
    }

    private fun tokenize(text: String): List<String> {
        val lexer = MultifileTestDataLexer()
        lexer.start(text)

        val tokens = mutableListOf<String>()
        while (lexer.tokenType != null) {
            tokens += "${lexer.tokenType}:${
                text.substring(lexer.tokenStart, lexer.tokenEnd)
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
            }"
            lexer.advance()
        }

        return tokens
    }
}
