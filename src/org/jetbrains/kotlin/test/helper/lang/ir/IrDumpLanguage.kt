package org.jetbrains.kotlin.test.helper.lang.ir

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Textual IR dumps produced by `DumpIrTree` in the Kotlin compiler (`*.ir.txt` test data files).
 */
object IrDumpLanguage : Language("KotlinIrDump") {
    private fun readResolve(): Any = IrDumpLanguage

    override fun getDisplayName(): String = "Kotlin IR Dump"
}

object IrDumpFileType : LanguageFileType(IrDumpLanguage) {
    private val _icon: Icon = IconLoader.getIcon("/icons/ir_dump.svg", IrDumpFileType::class.java)
    override fun getIcon(): Icon = _icon
    override fun getName(): String = "KotlinIrDump"
    override fun getDescription(): String = "Kotlin IR dump"
    override fun getDefaultExtension(): String = "ir.txt"
}
