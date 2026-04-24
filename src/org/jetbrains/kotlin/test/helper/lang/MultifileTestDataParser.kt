package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

class MultifileTestDataParser : PsiParser {
    override fun parse(
        root: IElementType,
        builder: PsiBuilder,
    ): ASTNode {
        val rootMarker = builder.mark()

        parsePreamble(builder)

        while (!builder.eof()) {
            if (!parseEntry(builder)) {
                val invalid = builder.mark()
                builder.advanceLexer()
                invalid.error("Unexpected multifile test data token")
            }
        }

        rootMarker.done(root)
        return builder.treeBuilt
    }

    private fun parsePreamble(builder: PsiBuilder) {
        if (builder.tokenType == MULTIFILE_PREAMBLE_TEXT) {
            val preamble = builder.mark()
            while (builder.tokenType == MULTIFILE_PREAMBLE_TEXT) {
                builder.advanceLexer()
            }
            preamble.done(MULTIFILE_PREAMBLE)
        }
    }

    private fun parseEntry(builder: PsiBuilder): Boolean {
        if (builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            return false
        }

        val entry = builder.mark()

        if (builder.tokenType == MULTIFILE_MODULE_LINE) {
            val moduleHeader = builder.mark()
            builder.advanceLexer()
            moduleHeader.done(MULTIFILE_MODULE_HEADER)
        }

        if (builder.tokenType == MULTIFILE_FILE_LINE) {
            val fileHeader = builder.mark()
            builder.advanceLexer()
            fileHeader.done(MULTIFILE_FILE_HEADER)
        } else {
            builder.error("Expected // FILE: directive")
            entry.done(MULTIFILE_ENTRY)
            return true
        }

        if (builder.tokenType != null && builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            val content = builder.mark()
            while (builder.tokenType != null && builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
                builder.advanceLexer()
            }
            content.done(MULTIFILE_FILE_CONTENT)
        }

        entry.done(MULTIFILE_ENTRY)
        return true
    }
}
