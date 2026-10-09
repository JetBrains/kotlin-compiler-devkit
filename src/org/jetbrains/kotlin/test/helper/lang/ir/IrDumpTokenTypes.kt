package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

internal class IrDumpTokenType(debugName: String) : IElementType(debugName, IrDumpLanguage)

internal object IrDumpTokenTypes {
    val FILE: IFileElementType = IFileElementType(IrDumpLanguage)

    /** Element kind at the start of a line, e.g. `FUN`, `CALL`, `BLOCK_BODY`. */
    val KIND = IrDumpTokenType("KIND")

    /** Bare words right after the element kind, e.g. `ENUM_CLASS` in `CLASS ENUM_CLASS` or `Int` in `CONST Int`. */
    val KIND_QUALIFIER = IrDumpTokenType("KIND_QUALIFIER")

    /** Child label, e.g. `thisReceiver:`, `ARG x:`, `overridden:`. */
    val LABEL = IrDumpTokenType("LABEL")

    /** Attribute key including the separator, e.g. `name:` or `type=`. */
    val ATTRIBUTE_KEY = IrDumpTokenType("ATTRIBUTE_KEY")

    /** Value of the `name:` attribute. */
    val DECLARATION_NAME = IrDumpTokenType("DECLARATION_NAME")

    /** Quoted symbol reference, e.g. `'public final fun foo (): kotlin.Unit declared in <root>'`. */
    val REFERENCE = IrDumpTokenType("REFERENCE")

    val STRING = IrDumpTokenType("STRING")
    val NUMBER = IrDumpTokenType("NUMBER")
    val LITERAL_KEYWORD = IrDumpTokenType("LITERAL_KEYWORD")

    /** Declaration flags, e.g. `[fake_override,operator]`. */
    val FLAGS = IrDumpTokenType("FLAGS")

    val COMMENT = IrDumpTokenType("COMMENT")
    val TEXT = IrDumpTokenType("TEXT")
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE

    val WHITE_SPACES: TokenSet = TokenSet.create(WHITE_SPACE)
    val COMMENTS: TokenSet = TokenSet.create(COMMENT)
    val STRINGS: TokenSet = TokenSet.create(STRING)
}
