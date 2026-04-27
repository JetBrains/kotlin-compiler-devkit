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
        val hasStructuralDirectives = hasStructuralDirectives(builder.originalText)

        parsePreamble(builder, hasStructuralDirectives)

        while (!builder.eof()) {
            if (!parseEntry(builder, hasStructuralDirectives)) {
                val invalid = builder.mark()
                builder.advanceLexer()
                invalid.error("Unexpected multifile test data token")
            }
        }

        rootMarker.done(root)
        return builder.treeBuilt
    }

    private fun parsePreamble(
        builder: PsiBuilder,
        hasStructuralDirectives: Boolean,
    ) {
        if (hasStructuralDirectives && builder.tokenType != null && builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            val preamble = builder.mark()
            parseBlockBody(builder)
            preamble.done(MULTIFILE_PREAMBLE)
            return
        }

        if (!hasStructuralDirectives && (builder.tokenType == MULTIFILE_NEW_LINE || builder.tokenType == MULTIFILE_COMMENT_LINE)) {
            val preamble = builder.mark()
            while (builder.tokenType == MULTIFILE_NEW_LINE || builder.tokenType == MULTIFILE_COMMENT_LINE) {
                builder.advanceLexer()
            }
            preamble.done(MULTIFILE_PREAMBLE)
        }
    }

    private fun parseEntry(
        builder: PsiBuilder,
        hasStructuralDirectives: Boolean,
    ): Boolean {
        if (!hasStructuralDirectives) {
            val entry = builder.mark()
            val content = builder.mark()
            parseBlockBody(builder)
            content.done(MULTIFILE_FILE_CONTENT)
            entry.done(MULTIFILE_ENTRY)
            return true
        }

        if (builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            return false
        }

        val entry = builder.mark()

        if (builder.tokenType == MULTIFILE_MODULE_LINE) {
            val moduleHeader = builder.mark()
            builder.advanceLexer()
            moduleHeader.done(MULTIFILE_MODULE_HEADER)

            while (builder.tokenType == MULTIFILE_NEW_LINE) {
                builder.advanceLexer()
            }
        }

        if (builder.tokenType == MULTIFILE_FILE_LINE) {
            val fileHeader = builder.mark()
            builder.advanceLexer()
            fileHeader.done(MULTIFILE_FILE_HEADER)
        }

        if (builder.tokenType != null && builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            val content = builder.mark()
            parseBlockBody(builder)
            content.done(MULTIFILE_FILE_CONTENT)
        }

        entry.done(MULTIFILE_ENTRY)
        return true
    }

    private fun hasStructuralDirectives(text: CharSequence): Boolean {
        return text.lineSequence().any { it.contains(STRUCTURAL_DIRECTIVE_REGEX) }
    }

    private fun parseBlockBody(builder: PsiBuilder) {
        while (builder.tokenType != null && builder.tokenType != MULTIFILE_MODULE_LINE && builder.tokenType != MULTIFILE_FILE_LINE) {
            if (builder.tokenType == MULTIFILE_TEXT_BLOCK) {
                val textBlock = builder.mark()
                builder.advanceLexer()
                textBlock.done(MULTIFILE_TEXT_BLOCK_ELEMENT)
                continue
            }
            builder.advanceLexer()
        }
    }

    companion object {
        private val STRUCTURAL_DIRECTIVE_REGEX = """^//\s*(?:FILE|MODULE)""".toRegex()
    }
}
