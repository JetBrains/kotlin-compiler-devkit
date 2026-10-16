package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TestFederationIgnoreServiceTest : BasePlatformTestCase() {
    fun testIgnoreRulesMatchMuteCommonCsvAndLeaveSiblingVisible() {
        addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/mute-common.csv
                """.trimIndent(),
        )
        val ignoredFile = addProjectFile("tests/mute-common.csv", "mute common")
        val siblingFile = addProjectFile("tests/other.csv", "not ignored")

        assertTrue(isIgnoredByTestFederation(project, ignoredFile))
        assertFalse(isIgnoredByTestFederation(project, siblingFile))
    }

    fun testGlobRulesInheritToDescendantsAndRespectNegationOrder() {
        val rules = parseTestFederationIgnoreRules(
            """
                ignore:
                  - tests/**
                  - "!tests/keep/**"
                  - tests/private
                """.trimIndent(),
        )

        assertTrue(rules.isIgnored(RepositoryPath.of("tests/nested/Test.kt")))
        assertFalse(rules.isIgnored(RepositoryPath.of("tests/keep/Test.kt")))
        assertTrue(rules.isIgnored(RepositoryPath.of("tests/private/Test.kt")))
        assertFalse(rules.isIgnored(RepositoryPath.of("other/Test.kt")))
    }

    fun testMalformedYamlDoesNotProduceIgnoreRules() {
        assertTrue(parseTestFederationIgnoreRules("ignore: [unterminated").isEmpty())
    }

    fun testCachedRulesRefreshWhenConfigurationIsCreatedEditedAndRemoved() {
        val ignoredFile = addProjectFile("tests/mute-common.csv", "mute common")

        assertFalse(isIgnoredByTestFederation(project, ignoredFile))

        val configurationFile = addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/mute-common.csv
                """.trimIndent(),
        )
        assertTrue(isIgnoredByTestFederation(project, ignoredFile))

        WriteCommandAction.runWriteCommandAction(project) {
            configurationFile.setBinaryContent(
                """
                    ignore:
                      - tests/other.csv
                    """.trimIndent().toByteArray(),
            )
        }
        assertFalse(isIgnoredByTestFederation(project, ignoredFile))

        WriteCommandAction.runWriteCommandAction(project) {
            configurationFile.setBinaryContent(
                """
                    ignore:
                      - tests/mute-common.csv
                    """.trimIndent().toByteArray(),
            )
        }
        assertTrue(isIgnoredByTestFederation(project, ignoredFile))

        WriteCommandAction.runWriteCommandAction(project) {
            configurationFile.rename(this, "domains.yaml.disabled")
        }
        assertFalse(isIgnoredByTestFederation(project, ignoredFile))

        WriteCommandAction.runWriteCommandAction(project) {
            configurationFile.rename(this, "domains.yaml")
        }
        assertTrue(isIgnoredByTestFederation(project, ignoredFile))

        WriteCommandAction.runWriteCommandAction(project) {
            configurationFile.delete(this)
        }
        assertFalse(isIgnoredByTestFederation(project, ignoredFile))
    }

    fun testUnsavedConfigurationEditsApplyOrderedNegation() {
        val configurationFile = addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/mute-common.csv
                """.trimIndent(),
        )
        val ignoredFile = addProjectFile("tests/mute-common.csv", "mute common")
        val siblingFile = addProjectFile("tests/other.csv", "not ignored")
        assertTrue(isIgnoredByTestFederation(project, ignoredFile))

        val document = FileDocumentManager.getInstance().getDocument(configurationFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(
                0,
                document.textLength,
                """
                    ignore:
                      - tests/*
                      - "!tests/mute-common.csv"
                    """.trimIndent(),
            )
        }
        assertFalse(isIgnoredByTestFederation(project, ignoredFile))
        assertTrue(isIgnoredByTestFederation(project, siblingFile))

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(
                0,
                document.textLength,
                """
                    ignore:
                      - "!tests/mute-common.csv"
                      - tests/*
                    """.trimIndent(),
            )
        }
        assertTrue(isIgnoredByTestFederation(project, ignoredFile))
        assertTrue(isIgnoredByTestFederation(project, siblingFile))
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun addProjectFile(path: String, contents: String): VirtualFile {
        lateinit var file: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            val segments = path.split('/')
            var directory = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
                ?: error("Project base directory is not present in the local VFS")
            for (segment in segments.dropLast(1)) {
                directory = directory.findChild(segment)?.takeIf { it.isValid }
                    ?: directory.createChildDirectory(this, segment)
            }
            file = directory.findChild(segments.last())?.takeIf { it.isValid }
                ?: directory.createChildData(this, segments.last())
            file.setBinaryContent(contents.toByteArray())
        }
        return file
    }

    override fun tearDown() {
        try {
            val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            WriteCommandAction.runWriteCommandAction(project) {
                listOf(
                    "repo/domains.yaml",
                    "repo/domains.yaml.disabled",
                    "tests/mute-common.csv",
                    "tests/other.csv",
                ).forEach { relativePath ->
                    projectRoot?.findFileByRelativePath(relativePath)?.delete(this)
                }
            }
        } finally {
            super.tearDown()
        }
    }
}
