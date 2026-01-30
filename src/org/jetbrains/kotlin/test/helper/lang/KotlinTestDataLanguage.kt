package org.jetbrains.kotlin.test.helper.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.IStubFileElementType
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.parsing.KotlinParser
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtImplementationDetail
import org.jetbrains.kotlin.psi.stubs.KotlinFileStub
import org.jetbrains.kotlin.psi.stubs.KotlinStubVersions
import org.jetbrains.kotlin.psi.stubs.elements.KtFileElementType
import org.jetbrains.kotlin.psi.stubs.elements.KtFileStubBuilder
import org.jetbrains.kotlin.psi.stubs.elements.StubIndexService
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFileStubImpl
import org.jetbrains.kotlin.test.helper.getTestDataType
import javax.swing.Icon

object KotlinTestDataFileType : LanguageFileType(KotlinTestDataLanguage) {
    override fun getName(): String = "KotlinTestData"
    override fun getDescription(): String = "Kotlin test data file"
    override fun getDefaultExtension(): String = "kt"
    override fun getIcon(): Icon? = KotlinFileType.INSTANCE.icon
}

class KotlinTestDataLanguageSubstitutor : LanguageSubstitutor() {
    override fun getLanguage(file: VirtualFile, project: Project): Language? {
        if (file.getTestDataType(project) != null) {
            return KotlinTestDataLanguage
        }
        return null
    }
}

object KotlinTestDataLanguage : Language(KotlinLanguage.INSTANCE, "kotlinTestData") {
    private fun readResolve(): Any = KotlinTestDataLanguage
}

object KotlinTestDataFileElementType : IStubFileElementType<KotlinFileStubImpl>(KotlinTestDataFileElementType.NAME, KotlinLanguage.INSTANCE) {
    internal const val NAME = "kotlinTestData.FILE"

    override fun getBuilder(): StubBuilder {
        return KtFileStubBuilder()
    }

    override fun getLanguage(): Language {
        return KotlinTestDataLanguage
    }

    override fun getStubVersion(): Int {
        return KotlinStubVersions.SOURCE_STUB_VERSION
    }

    override fun getExternalId(): String {
        return NAME
    }

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? {
        val project = psi.project
        val languageForParser = getLanguageForParser(psi)
        val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, null, languageForParser, chameleon.chars)
        return KotlinParser.parse(builder, psi.containingFile).firstChildNode
    }

    override fun indexStub(stub: PsiFileStub<*>, sink: IndexSink) {
        StubIndexService.getInstance().indexFile(stub as KotlinFileStubImpl, sink)
    }
}

class KotlinTestDataParserDefinition : KotlinParserDefinition() {
    override fun getFileNodeType(): IFileElementType {
        return KotlinTestDataFileElementType
    }
}
