package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import org.jetbrains.kotlin.idea.KotlinLanguage
import javax.swing.Icon

object MultifileTestDataFileType : LanguageFileType(MultifileTestDataLanguage) {
    private val _icon: Icon = IconLoader.getIcon("/icons/multifile_testdata.svg", MultifileTestDataFileType::class.java)
    override fun getIcon(): Icon = _icon
    override fun getName(): String = "MultifileTestData"
    override fun getDescription(): String = "Multifile test data file"
    override fun getDefaultExtension(): String = "test"
}

object MultifileTestDataLanguage : Language(KotlinLanguage.INSTANCE, "multifileTestData") {
    private fun readResolve(): Any = MultifileTestDataLanguage
}