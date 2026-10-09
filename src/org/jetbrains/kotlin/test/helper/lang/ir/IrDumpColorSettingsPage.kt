package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class IrDumpColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = IrDumpFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = IrDumpSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = IrDumpLanguage.displayName
}

private val DESCRIPTORS = arrayOf(
    AttributesDescriptor("Element kind", IrDumpSyntaxHighlighter.KIND),
    AttributesDescriptor("Element kind qualifier", IrDumpSyntaxHighlighter.KIND_QUALIFIER),
    AttributesDescriptor("Label", IrDumpSyntaxHighlighter.LABEL),
    AttributesDescriptor("Attribute key", IrDumpSyntaxHighlighter.ATTRIBUTE_KEY),
    AttributesDescriptor("Declaration name", IrDumpSyntaxHighlighter.DECLARATION_NAME),
    AttributesDescriptor("Symbol reference", IrDumpSyntaxHighlighter.REFERENCE),
    AttributesDescriptor("String", IrDumpSyntaxHighlighter.STRING),
    AttributesDescriptor("Number", IrDumpSyntaxHighlighter.NUMBER),
    AttributesDescriptor("Literal keyword", IrDumpSyntaxHighlighter.LITERAL_KEYWORD),
    AttributesDescriptor("Flags", IrDumpSyntaxHighlighter.FLAGS),
    AttributesDescriptor("Comment", IrDumpSyntaxHighlighter.COMMENT),
)

private val DEMO_TEXT = """
    FILE fqName:<root> fileName:/demo.kt
      CLASS CLASS name:A modality:FINAL visibility:public superTypes:[kotlin.Any]
        thisReceiver: VALUE_PARAMETER INSTANCE_RECEIVER kind:DispatchReceiver name:<this> type:<root>.A
        annotations:
          Deprecated(message = "Use B instead")
        FUN FAKE_OVERRIDE name:hashCode visibility:protected/*protected and package*/ modality:OPEN returnType:kotlin.Int [fake_override]
          overridden:
            public open fun hashCode (): kotlin.Int declared in kotlin.Any
      FUN name:test visibility:public modality:FINAL returnType:kotlin.String
        BLOCK_BODY
          RETURN type=kotlin.Nothing from='public final fun test (): kotlin.String declared in <root>'
            CALL 'public final fun plus (other: kotlin.Any?): kotlin.String declared in kotlin.String' type=kotlin.String origin=PLUS
              ARG <this>: CONST String type=kotlin.String value="answer: "
              ARG other: CONST Int type=kotlin.Int value=42
""".trimIndent()
