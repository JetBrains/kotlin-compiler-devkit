package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.projectView.NodeSortKey
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewSettings
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.GroupByTypeComparator
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.ui.LayeredIcon
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JTree
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DomainTreeStructureTest : BasePlatformTestCase() {
    /* Disk-only cleanup must not leave stale VFS entries in a reused project. */
    private val projectDescriptor = LightProjectDescriptor()

    override fun getProjectDescriptor(): LightProjectDescriptor = projectDescriptor

    fun testMissingDumpCreatesAnEmptyDomainTree() {
        assertFalse(
            DomainProjectService.hasDumpMarker(project),
            "Unexpected dump marker under project base path ${project.basePath}",
        )
        val structure = DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID)
        val root = structure.rootElement as ProjectViewNode<*>

        assertTrue(root.children.isEmpty())
    }

    fun testAddingDumpMarkerActivatesDomainTreeAndLoadsDump() {
        val initialRoot = DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID).rootElement as ProjectViewNode<*>
        assertTrue(initialRoot.children.isEmpty())

        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        val modelChanges = AtomicInteger()
        val loaded = CountDownLatch(1)
        lateinit var dumpFile: VirtualFile
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(
                DomainProjectService.MODEL_CHANGED_TOPIC,
                DomainModelListener {
                    modelChanges.incrementAndGet()
                    loaded.countDown()
                },
            )
            WriteAction.runAndWait<RuntimeException> {
                projectRoot.createChildDirectory(this, "compiler-domain-marker-unrelated-test")
            }
            assertEquals(0, modelChanges.get(), "An unrelated root change should not start the Domain service")

            WriteAction.runAndWait<RuntimeException> {
                val repoDirectory = projectRoot.findChild("repo") ?: projectRoot.createChildDirectory(this, "repo")
                dumpFile = repoDirectory.createChildData(this, "domains.dump.txt")
                dumpFile.setBinaryContent("Alpha:\n- src".toByteArray())
            }
            val activeRoot =
                DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID).rootElement as ProjectViewNode<*>
            val deadline = System.nanoTime() + 10_000_000_000
            while (loaded.count > 0 && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertEquals(0L, loaded.count, "The active dump should load and publish a model change")
            val service = DomainProjectService.getInstance(project)
            assertEquals(parseDomainDump("Alpha:\n- src"), service.dump)
            assertEquals(listOf("Alpha"), activeRoot.children.map { it.value })

            WriteAction.runAndWait<RuntimeException> { dumpFile.delete(this) }
            assertEquals(DomainDump.EMPTY, service.dump)
        } finally {
            connection.disconnect()
        }
    }

    fun testDomainGroupsCarryDumpDeclarationIndices() {
        val groups = loadDomainGroups(
            """
                Gamma:
                - compiler-domain-group-test/Assigned.txt
                Alpha:
                - compiler-domain-group-test/Assigned.txt
                Beta:
                - compiler-domain-group-test/Assigned.txt
            """.trimIndent(),
        )

        assertEquals(listOf("Gamma", "Alpha", "Beta"), groups.map { it.value })
        assertEquals(listOf(0, 1, 2), groups.map { it.domainIndex })
        assertDeclarationOrderUnderAllSortSettings(groups, listOf("Gamma", "Alpha", "Beta"))
    }

    fun testDomainDeclarationIndicesUseFirstAppearanceInCombinedHeadings() {
        val groups = loadDomainGroups(
            """
                Gamma, Delta:
                - compiler-domain-group-test/Assigned.txt
                Alpha:
                - compiler-domain-group-test/Assigned.txt
                Beta, Gamma:
                - compiler-domain-group-test/Assigned.txt
            """.trimIndent(),
        )

        assertEquals(listOf("Gamma", "Delta", "Alpha", "Beta"), groups.map { it.value })
        assertEquals(listOf(0, 1, 2, 3), groups.map { it.domainIndex })
        assertDeclarationOrderUnderAllSortSettings(groups, listOf("Gamma", "Delta", "Alpha", "Beta"))
    }

    fun testCompactDirectoriesSettingIsAvailableToDomainTreeStructure() {
        val projectView = ProjectView.getInstance(project)
        val pane = DomainProjectViewPane(project)
        val previousSetting = projectView.isCompactDirectories(DomainProjectViewPane.VIEW_ID)
        try {
            assertTrue(pane.supportsCompactDirectories())

            projectView.setCompactDirectories(DomainProjectViewPane.VIEW_ID, false)
            assertFalse(DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID).isCompactDirectories)

            projectView.setCompactDirectories(DomainProjectViewPane.VIEW_ID, true)
            assertTrue(DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID).isCompactDirectories)
        } finally {
            projectView.setCompactDirectories(DomainProjectViewPane.VIEW_ID, previousSetting)
            pane.dispose()
        }
    }

    fun testRepositoryStructureChangesAdvanceTheRevision() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        WriteAction.runAndWait<RuntimeException> {
            val repoDirectory = projectRoot.findChild("repo") ?: projectRoot.createChildDirectory(this, "repo")
            val dumpFile = repoDirectory.findChild("domains.dump.txt")?.takeIf { it.isValid }
                ?: repoDirectory.createChildData(this, "domains.dump.txt")
            dumpFile.setBinaryContent(
                """
                    Alpha:
                    - compiler-domain-refresh-test/represented
                    Ignored:
                    - compiler-domain-ignored-test/ignored
                    """.trimIndent().toByteArray(),
            )
        }
        val service = DomainProjectService.getInstance(project)
        assertEquals(
            parseDomainDump(
                """
                    Alpha:
                    - compiler-domain-refresh-test/represented
                    Ignored:
                    - compiler-domain-ignored-test/ignored
                    """.trimIndent(),
            ),
            service.dump,
        )
        val initialRevision = service.repositoryRevision

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.createChildDirectory(this, "compiler-domain-refresh-test")
        }
        val domainPathRevision = service.repositoryRevision
        assertTrue(domainPathRevision > initialRevision)

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.findChild("compiler-domain-refresh-test")!!.createChildDirectory(this, "represented")
        }
        val representedPathRevision = service.repositoryRevision
        assertTrue(representedPathRevision > domainPathRevision)

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.findChild("compiler-domain-refresh-test")!!
                .findChild("represented")!!
                .createChildData(this, "added.kt")
        }
        val domainDescendantRevision = service.repositoryRevision
        assertTrue(domainDescendantRevision > representedPathRevision)

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.createChildDirectory(this, "compiler-domain-ignored-test")
        }
        val ignoredPathRevision = service.repositoryRevision
        assertTrue(ignoredPathRevision > domainDescendantRevision)

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.findChild("compiler-domain-ignored-test")!!
                .createChildDirectory(this, "ignored")
                .createChildData(this, "muted.kt")
        }
        val ignoredDescendantRevision = service.repositoryRevision
        assertTrue(ignoredDescendantRevision > ignoredPathRevision)

        WriteAction.runAndWait<RuntimeException> {
            projectRoot.findChild("compiler-domain-refresh-test")!!.createChildDirectory(this, "unrelated")
        }
        val unrelatedDirectoryRevision = service.repositoryRevision
        assertTrue(unrelatedDirectoryRevision > ignoredDescendantRevision)

        lateinit var notesDirectory: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            notesDirectory = projectRoot.createChildDirectory(this, "compiler-domain-unrelated-test")
        }
        val notesDirectoryRevision = service.repositoryRevision
        assertTrue(notesDirectoryRevision > unrelatedDirectoryRevision)

        lateinit var notesFile: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            notesFile = notesDirectory.createChildData(this, "notes.txt")
        }
        val repositoryStructureRevision = service.repositoryRevision
        assertTrue(repositoryStructureRevision > notesDirectoryRevision)

        WriteAction.runAndWait<RuntimeException> {
            notesFile.setBinaryContent("updated notes".toByteArray())
        }
        assertEquals(repositoryStructureRevision, service.repositoryRevision)

        val outsideRoot = projectRoot.parent
            ?: error("Project base directory parent is not present in the local VFS")
        val outsideDirectoryName = "compiler-domain-outside-test-${System.nanoTime()}"
        assertNull(outsideRoot.findChild(outsideDirectoryName))

        val outsideDirectoryPath = "${outsideRoot.path}/$outsideDirectoryName"
        val outsideEvents = AtomicInteger()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    outsideEvents.addAndGet(events.count { event -> event.path == outsideDirectoryPath })
                }
            },
        )

        var outsideDirectory: VirtualFile? = null
        try {
            WriteAction.runAndWait<RuntimeException> {
                outsideDirectory = outsideRoot.createChildDirectory(this, outsideDirectoryName)
            }
            assertEquals(1, outsideEvents.get(), "Creating a fresh outside directory should publish a VFS event")
            assertEquals(repositoryStructureRevision, service.repositoryRevision)
        } finally {
            WriteAction.runAndWait<RuntimeException> { outsideDirectory?.delete(this) }
        }
    }

    fun testDomainDirectoriesUseStandardSourceRootPresentation() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        lateinit var contentRoot: VirtualFile
        lateinit var sourceRoot: VirtualFile
        lateinit var orgDirectory: VirtualFile
        lateinit var jetbrainsDirectory: VirtualFile
        lateinit var kotlinDirectory: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-presentation-test")
            sourceRoot = contentRoot.createChildDirectory(this, "src")
            orgDirectory = sourceRoot.createChildDirectory(this, "org")
            jetbrainsDirectory = orgDirectory.createChildDirectory(this, "jetbrains")
            kotlinDirectory = jetbrainsDirectory.createChildDirectory(this, "kotlin")
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry = rootModel.addContentEntry(contentRoot)
            contentEntry.addSourceFolder(sourceRoot, false)
            rootModel.commit()
        }

        assertTrue(ProjectFileIndex.getInstance(project).isInSourceContent(sourceRoot))

        val settings = Proxy.newProxyInstance(
            ViewSettings::class.java.classLoader,
            arrayOf(ViewSettings::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as ViewSettings
        val psiManager = PsiManager.getInstance(project)
        val sourcePsiDirectory = psiManager.findDirectory(sourceRoot)!!
        val repositoryRoot = RepositoryRoot.of(contentRoot)
        val dump = DomainDump(
            entries = listOf(DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src"))),
        )
        val domainNode = DomainGroupNode(
            project,
            settings,
            Domain.of("Alpha"),
            0,
            repositoryRoot,
            dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true },
        )
        val sourceDirectoryNode = domainNode.children.single() as DomainDirectoryNode
        val domainPresentation = sourceDirectoryNode.updatedPresentation()
        val standardSourcePresentation = PresentationData().also {
            PsiDirectoryNode(project, sourcePsiDirectory, settings).update(it)
        }

        assertEquals(standardSourcePresentation.getIcon(false), domainPresentation.getIcon(false))
        assertTrue(domainNode.contains(kotlinDirectory))
        assertFalse(domainNode.contains(projectRoot))
        assertFalse(domainPresentation.presentableText.orEmpty().contains("[${module.name}]"))
        assertFalse(domainPresentation.locationString.orEmpty().contains("[${module.name}]"))
        assertFalse(domainPresentation.coloredText.joinToString("") { it.text }.contains("[${module.name}]"))
        assertEquals("src", withoutBracketedModuleNames("src [${module.name}]", setOf(module.name)::contains))
        assertEquals("sources", withoutBracketedModuleNames("[${module.name}] sources", setOf(module.name)::contains))
        assertEquals(
            "tools",
            withoutBracketedModuleNames(
                "tools [kotlin.tools, kotlin.libraries.tools]",
                setOf("kotlin.tools", "kotlin.libraries.tools")::contains,
            ),
        )
        assertEquals("[not a module]", withoutBracketedModuleNames("[not a module]", setOf(module.name)::contains))

        val orgNode = sourceDirectoryNode.children.single() as DomainDirectoryNode
        val jetbrainsNode = orgNode.children.single() as DomainDirectoryNode
        val kotlinNode = jetbrainsNode.children.single() as DomainDirectoryNode

        val orgPresentation = orgNode.updatedPresentation()
        val jetbrainsPresentation = jetbrainsNode.updatedPresentation()
        val kotlinPresentation = kotlinNode.updatedPresentation()
        assertEquals("org", orgPresentation.displayedDirectoryText())
        assertEquals("jetbrains", jetbrainsPresentation.displayedDirectoryText())
        assertEquals("kotlin", kotlinPresentation.displayedDirectoryText())
    }

    fun testDomainGroupsRenderPathHierarchyAndYamlIgnoreBadge() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        lateinit var contentRoot: VirtualFile
        lateinit var assignedFile: VirtualFile
        lateinit var ignoredFile: VirtualFile
        lateinit var siblingFile: VirtualFile
        File(project.basePath!!, "repo").mkdirs()
        WriteAction.runAndWait<RuntimeException> {
            val repoDirectory = projectRoot.findChild("repo")?.takeIf { it.isValid }
                ?: projectRoot.createChildDirectory(this, "repo")
            val ignoreConfiguration = repoDirectory.findChild("domains.yaml")?.takeIf { it.isValid }
                ?: repoDirectory.createChildData(this, "domains.yaml")
            ignoreConfiguration.setBinaryContent(
                """
                    ignore:
                      - compiler-domain-group-test/feature/Assigned.txt
                    """.trimIndent().toByteArray(),
            )
            contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-group-test")
            val featureDirectory = contentRoot.createChildDirectory(this, "feature")
            assignedFile = featureDirectory.createChildData(this, "Assigned.txt")
            ignoredFile = featureDirectory.createChildDirectory(this, "ignored").createChildData(this, "Hidden.txt")
            siblingFile = contentRoot.createChildDirectory(this, "sibling").createChildData(this, "Sibling.txt")
            contentRoot.createChildData(this, "root.txt")
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(contentRoot)
            rootModel.commit()
        }

        val dump = DomainDump(
            entries = listOf(
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("feature")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("root.txt")),
            ),
        )
        val settings = Proxy.newProxyInstance(
            ViewSettings::class.java.classLoader,
            arrayOf(ViewSettings::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as ViewSettings
        val repositoryRoot = RepositoryRoot.of(contentRoot)
        val pathTree = dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true }
        val domainNode = DomainGroupNode(project, settings, Domain.of("Alpha"), 0, repositoryRoot, pathTree)

        assertEquals(setOf("feature", "root.txt"), pathTree.children.keys)
        assertEquals(1, domainNode.children.count { it is DomainDirectoryNode })
        assertEquals(1, domainNode.children.count { it !is DomainDirectoryNode })
        assertEquals(2, domainNode.children.size)
        assertTrue(domainNode.contains(assignedFile))
        assertFalse(domainNode.contains(siblingFile))
        assertTrue(domainNode.contains(ignoredFile))

        val featureNode = domainNode.children.single { it is DomainDirectoryNode }
        val assignedNode = featureNode.children.single { it !is DomainDirectoryNode }
        assignedNode.update()
        val assignedPresentation = assignedNode.presentation
        assertTrue(assignedPresentation.getIcon(false) is LayeredIcon)
        assertTrue(assignedPresentation.tooltip.orEmpty().contains("Ignored by test federation"))
        val visibleNode = domainNode.children.single { it !is DomainDirectoryNode }
        visibleNode.update()
        val visiblePresentation = visibleNode.presentation
        assertFalse(visiblePresentation.tooltip.orEmpty().contains("Ignored by test federation"))

        domainNode.update()
        val domainPresentation = domainNode.presentation
        assertFalse(domainPresentation.tooltip.orEmpty().contains("Ignored by test federation"))
    }

    fun testDirectoryPresentationCompactionAndFolderSortingOptions() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        lateinit var contentRoot: VirtualFile
        lateinit var sourceRoot: VirtualFile
        lateinit var packageDirectory: VirtualFile
        lateinit var nestedPackageDirectory: VirtualFile
        lateinit var deepestPackageDirectory: VirtualFile
        lateinit var compactedDirectory: VirtualFile
        lateinit var nestedDirectory: VirtualFile
        lateinit var terminalDirectory: VirtualFile
        lateinit var directoryParent: VirtualFile
        lateinit var directoryTerminal: VirtualFile
        lateinit var assignedFile: VirtualFile
        lateinit var nestedAssignedFile: VirtualFile
        lateinit var ordinaryDirectory: VirtualFile
        lateinit var ordinaryNestedDirectory: VirtualFile
        lateinit var ordinaryLeafDirectory: VirtualFile
        lateinit var ordinaryFile: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-directory-options-test")
            sourceRoot = contentRoot.createChildDirectory(this, "src")
            packageDirectory = sourceRoot.createChildDirectory(this, "com")
            nestedPackageDirectory = packageDirectory.createChildDirectory(this, "example")
            deepestPackageDirectory = nestedPackageDirectory.createChildDirectory(this, "domain")
            deepestPackageDirectory.createChildData(this, "Package.txt")
            compactedDirectory = contentRoot.createChildDirectory(this, "zFolder")
            nestedDirectory = compactedDirectory.createChildDirectory(this, "nested")
            terminalDirectory = nestedDirectory.createChildDirectory(this, "deep")
            assignedFile = terminalDirectory.createChildData(this, "Inside.txt")
            directoryParent = contentRoot.createChildDirectory(this, "parent")
            directoryParent.createChildData(this, "AtParent.txt")
            val nestedParent = directoryParent.createChildDirectory(this, "nested")
            directoryTerminal = nestedParent.createChildDirectory(this, "terminal")
            nestedAssignedFile = directoryTerminal.createChildData(this, "NestedInside.txt")
            ordinaryDirectory = contentRoot.createChildDirectory(this, "ordinary")
            ordinaryNestedDirectory = ordinaryDirectory.createChildDirectory(this, "nested")
            ordinaryLeafDirectory = ordinaryNestedDirectory.createChildDirectory(this, "leaf")
            ordinaryFile = ordinaryLeafDirectory.createChildData(this, "Ordinary.txt")
            contentRoot.createChildData(this, "a.txt")

            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(contentRoot).addSourceFolder(sourceRoot, false)
            rootModel.addContentEntry(terminalDirectory)
            rootModel.addContentEntry(directoryTerminal)
            rootModel.commit()
        }
        assertTrue(terminalDirectory in ModuleRootManager.getInstance(module).contentRoots)
        assertTrue(directoryTerminal in ModuleRootManager.getInstance(module).contentRoots)

        val repositoryRoot = RepositoryRoot.of(contentRoot)
        val dump = DomainDump(
            entries = listOf(
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/com/example/domain/Package.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("zFolder/nested/deep/Inside.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("parent/AtParent.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("parent/nested/terminal/NestedInside.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("ordinary/nested/leaf/Ordinary.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("a.txt")),
            ),
        )

        fun domainNode(settings: ViewSettings) = DomainGroupNode(
            project,
            settings,
            Domain.of("Alpha"),
            0,
            repositoryRoot,
            dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true },
        )

        fun directoryText(node: DomainDirectoryNode): String = node.updatedPresentation().displayedDirectoryText()

        val pathOnlyNode = domainNode(viewSettings())
        val pathOnlyFolder =
            pathOnlyNode.children.filterIsInstance<DomainDirectoryNode>().single { it.canRepresent(compactedDirectory) }
        assertEquals("zFolder", directoryText(pathOnlyFolder))
        assertEquals(
            setOf("nested"),
            pathOnlyFolder.children.filterIsInstance<DomainDirectoryNode>().map(::directoryText).toSet()
        )

        val compactNode = domainNode(viewSettings(compactDirectories = true))
        val compactFolder =
            compactNode.children.filterIsInstance<DomainDirectoryNode>().single { it.canRepresent(compactedDirectory) }
        assertEquals("zFolder/nested/deep", compactFolder.updatedPresentation().displayedDirectoryText())
        assertFalse(directoryText(compactFolder).contains('[') || directoryText(compactFolder).contains(']'))
        assertTrue(compactFolder.canRepresent(compactedDirectory))
        assertTrue(compactFolder.canRepresent(nestedDirectory))
        assertTrue(compactFolder.canRepresent(terminalDirectory))
        assertTrue(compactFolder.contains(assignedFile))
        val compactedFile = compactFolder.children.single() as PsiFileNode
        assertEquals(assignedFile, compactedFile.value.virtualFile)

        val parentDirectoryNode = compactNode.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(directoryParent) }
        val compactedChild = parentDirectoryNode.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(directoryTerminal) }
        assertEquals("nested/terminal", directoryText(compactedChild))
        assertFalse(directoryText(compactedChild).contains('[') || directoryText(compactedChild).contains(']'))
        assertTrue(compactedChild.contains(nestedAssignedFile))
        compactFolder.assertRendersProjectedLabel("zFolder/nested/deep")
        compactedChild.assertRendersProjectedLabel("nested/terminal")

        val ordinaryFolder = compactNode.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(ordinaryDirectory) }
        assertFalse(ordinaryLeafDirectory in ModuleRootManager.getInstance(module).contentRoots)
        ordinaryFolder.assertRendersProjectedLabel("ordinary/nested/leaf")
        assertTrue(ordinaryFolder.canRepresent(ordinaryNestedDirectory))
        assertTrue(ordinaryFolder.canRepresent(ordinaryLeafDirectory))
        assertTrue(ordinaryFolder.contains(ordinaryFile))

        val abbreviatedSettings = viewSettings(flattenPackages = true, abbreviatePackageNames = true)
        val abbreviatedNode = domainNode(abbreviatedSettings)
        val abbreviatedSourceRoot =
            abbreviatedNode.children.filterIsInstance<DomainDirectoryNode>().single { it.canRepresent(sourceRoot) }
        val abbreviatedPackage = abbreviatedSourceRoot.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(deepestPackageDirectory) }
        val standardParentNode = PsiDirectoryNode(
            project,
            PsiManager.getInstance(project).findDirectory(nestedPackageDirectory)!!,
            abbreviatedSettings,
        )
        val standardPackageNode = PsiDirectoryNode(
            project,
            PsiManager.getInstance(project).findDirectory(deepestPackageDirectory)!!,
            abbreviatedSettings,
        ).apply { parent = standardParentNode }
        val standardPackageText = PresentationData().also(standardPackageNode::update).displayedDirectoryText()
        assertEquals(standardPackageText, directoryText(abbreviatedPackage))
        val regularNode = domainNode(viewSettings(flattenPackages = true))
        val regularSourceRoot =
            regularNode.children.filterIsInstance<DomainDirectoryNode>().single { it.canRepresent(sourceRoot) }
        val regularPackage = regularSourceRoot.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(deepestPackageDirectory) }
        assertFalse(standardPackageText == directoryText(regularPackage))
        abbreviatedPackage.assertRendersProjectedLabel(standardPackageText)
        regularPackage.assertRendersProjectedLabel(directoryText(regularPackage))

        val flattenedCompactNode = domainNode(viewSettings(flattenPackages = true, compactDirectories = true))
        val flattenedCompactSourceRoot = flattenedCompactNode.children
            .filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(sourceRoot) }
        val flattenedTopPackage = flattenedCompactSourceRoot.children
            .filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(packageDirectory) }
        val flattenedLeafPackage = flattenedCompactSourceRoot.children
            .filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(deepestPackageDirectory) }
        assertTrue(flattenedTopPackage.children.isEmpty())
        assertEquals(
            setOf("Package.txt"),
            flattenedLeafPackage.children.filterIsInstance<PsiFileNode>().map { it.value.name }.toSet()
        )

        val foldersOnTopNode = domainNode(viewSettings(foldersAlwaysOnTop = true))
        val folder = foldersOnTopNode.children.filterIsInstance<DomainDirectoryNode>()
            .single { it.canRepresent(compactedDirectory) }
        val file = foldersOnTopNode.children.filterNot { it is DomainDirectoryNode }.single() as ProjectViewNode<*>
        val foldersOnTopComparator = object : GroupByTypeComparator(project, DomainProjectViewPane.VIEW_ID) {
            override fun isFoldersAlwaysOnTop(): Boolean = true
        }
        val alphabeticalComparator = object : GroupByTypeComparator(project, DomainProjectViewPane.VIEW_ID) {
            override fun isFoldersAlwaysOnTop(): Boolean = false
        }
        val standardFolder = PsiDirectoryNode(
            project,
            PsiManager.getInstance(project).findDirectory(compactedDirectory)!!,
            viewSettings(foldersAlwaysOnTop = true),
        )
        assertTrue(foldersOnTopComparator.compare(folder, file) < 0)
        assertEquals(
            foldersOnTopComparator.compare(standardFolder, file),
            foldersOnTopComparator.compare(folder, file),
        )
        assertEquals(
            alphabeticalComparator.compare(standardFolder, file),
            alphabeticalComparator.compare(folder, file),
        )

        val liveSettings = timeSortViewSettings()
        val livePathNode = dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true }
            .children.getValue(compactedDirectory.name)
        val liveProjection = projectDomainDirectoryPath(
            project,
            livePathNode,
            liveSettings,
        ) { true }
        val liveDirectory = DomainDirectoryNode(
            project,
            PsiManager.getInstance(project).findDirectory(compactedDirectory)!!,
            liveSettings,
            repositoryRoot,
            liveProjection,
        )
        val liveStandardDirectory = PsiDirectoryNode(
            project,
            PsiManager.getInstance(project).findDirectory(compactedDirectory)!!,
            liveSettings,
        )
        liveDirectory.update()
        liveStandardDirectory.update()
        val liveFile = PsiFileNode(
            project,
            PsiManager.getInstance(project).findFile(contentRoot.findChild("a.txt")!!)!!,
            liveSettings,
        )
        liveFile.update()
        assertEquals(NodeSortKey.BY_TIME_ASCENDING, liveSettings.sortKey)
        assertNotNull(liveFile.timeSortKey)

        assertEquals(liveStandardDirectory.timeSortKey, liveDirectory.timeSortKey)
        val timeComparator = object : GroupByTypeComparator(project, DomainProjectViewPane.VIEW_ID) {
            override fun getSortKey(): NodeSortKey = NodeSortKey.BY_TIME_ASCENDING

            override fun isFoldersAlwaysOnTop(): Boolean = false
        }
        assertTrue(timeComparator.compare(liveStandardDirectory, liveFile) < 0)
        assertEquals(
            timeComparator.compare(liveStandardDirectory, liveFile),
            timeComparator.compare(liveDirectory, liveFile),
        )

        for (sortKey in NodeSortKey.entries) {
            for (foldersOnTop in listOf(false, true)) {
                val fallback = groupByTypeComparator(sortKey, foldersOnTop)
                val comparator = domainDeclarationOrderComparator(fallback)
                assertEquals(
                    fallback.compare(liveDirectory, liveFile),
                    comparator.compare(liveDirectory, liveFile),
                    "sortKey=$sortKey, foldersOnTop=$foldersOnTop",
                )
                assertEquals(
                    fallback.compare(liveFile, liveDirectory),
                    comparator.compare(liveFile, liveDirectory),
                    "sortKey=$sortKey, foldersOnTop=$foldersOnTop",
                )
            }
        }
    }

    fun testPackageSettingsRespectPackageBoundariesAndDomainMembership() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        lateinit var contentRoot: VirtualFile
        lateinit var sourceRoot: VirtualFile
        lateinit var orgDirectory: VirtualFile
        lateinit var jetbrainsDirectory: VirtualFile
        lateinit var kotlinDirectory: VirtualFile
        lateinit var compilerPackageDirectory: VirtualFile
        lateinit var chainDirectory: VirtualFile
        lateinit var middleDirectory: VirtualFile
        lateinit var leafDirectory: VirtualFile
        lateinit var nestedSourceRoot: VirtualFile
        lateinit var rootFile: VirtualFile
        lateinit var assignedFile: VirtualFile
        lateinit var leafFile: VirtualFile
        lateinit var nestedSourceFile: VirtualFile
        lateinit var ignoredFile: VirtualFile
        lateinit var unassignedPackageDirectory: VirtualFile
        lateinit var excludedFile: VirtualFile
        lateinit var ideIgnoredFile: VirtualFile
        lateinit var nonPackageFile: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-projection-test")
            sourceRoot = contentRoot.createChildDirectory(this, "src")
            orgDirectory = sourceRoot.createChildDirectory(this, "org")
            rootFile = orgDirectory.createChildData(this, "Root.txt")
            val jetbrains = orgDirectory.createChildDirectory(this, "jetbrains")
            unassignedPackageDirectory = orgDirectory.createChildDirectory(this, "unassigned")
            unassignedPackageDirectory.createChildData(this, "Other.txt")
            jetbrainsDirectory = jetbrains
            kotlinDirectory = jetbrains.createChildDirectory(this, "kotlin")
            assignedFile = kotlinDirectory.createChildData(this, "Assigned.txt")
            val excludedDirectory = kotlinDirectory.createChildDirectory(this, "excluded")
            excludedFile = excludedDirectory.createChildData(this, "Excluded.txt")
            ignoredFile = kotlinDirectory.createChildDirectory(this, "ignored").createChildData(this, "Hidden.txt")
            ideIgnoredFile = kotlinDirectory.createChildData(this, ".DS_Store")
            compilerPackageDirectory = jetbrains.createChildDirectory(this, "compiler")
            compilerPackageDirectory.createChildData(this, "Compiler.txt")
            nestedSourceRoot = jetbrains.createChildDirectory(this, "nestedSrc")
            nestedSourceFile = nestedSourceRoot.createChildData(this, "Nested.txt")
            chainDirectory = orgDirectory.createChildDirectory(this, "chain")
            middleDirectory = chainDirectory.createChildDirectory(this, "middle")
            leafDirectory = middleDirectory.createChildDirectory(this, "leaf")
            leafFile = leafDirectory.createChildData(this, "Leaf.txt")

            val nonPackageDirectory = contentRoot.createChildDirectory(this, "compiler")
                .createChildDirectory(this, "testData")
                .createChildDirectory(this, "fixture")
            nonPackageFile = nonPackageDirectory.createChildData(this, "Fixture.txt")

            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry = rootModel.addContentEntry(contentRoot)
            contentEntry.addSourceFolder(sourceRoot, false)
            contentEntry.addSourceFolder(nestedSourceRoot, false)
            contentEntry.addExcludeFolder(excludedDirectory)
            rootModel.commit()
        }

        assertTrue(
            JavaDirectoryService.getInstance().isSourceRoot(PsiManager.getInstance(project).findDirectory(sourceRoot)!!)
        )
        val repositoryRoot = RepositoryRoot.of(contentRoot)
        val dump = DomainDump(
            entries = listOf(
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/org/Root.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/org/jetbrains/kotlin")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/org/jetbrains/compiler")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/org/jetbrains/nestedSrc/Nested.txt")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("src/org/chain/middle")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("compiler/testData/fixture/Fixture.txt")),
            ),
        )

        fun domainNode(settings: ViewSettings) = DomainGroupNode(
            project,
            settings,
            Domain.of("Alpha"),
            0,
            repositoryRoot,
            dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true },
        )

        fun directoryText(node: DomainDirectoryNode): String =
            node.updatedPresentation().displayedDirectoryText()

        fun childTexts(node: DomainDirectoryNode): Set<String> = node.children.map { child ->
            if (child is DomainDirectoryNode) {
                directoryText(child)
            } else {
                child.update()
                child.presentation.presentableText.orEmpty()
            }
        }.toSet()

        fun directoryChildren(node: DomainDirectoryNode): List<DomainDirectoryNode> =
            node.children.filterIsInstance<DomainDirectoryNode>()

        val pathOnlyNode = domainNode(viewSettings())
        val pathOnlySourceRoot =
            pathOnlyNode.children.filterIsInstance<DomainDirectoryNode>().single { directoryText(it) == "src" }
        val pathOnlyOrg = directoryChildren(pathOnlySourceRoot).single()
        assertEquals("org", directoryText(pathOnlyOrg))
        assertEquals(setOf("Root.txt", "jetbrains", "chain"), childTexts(pathOnlyOrg))
        val pathOnlyJetbrains = directoryChildren(pathOnlyOrg).single { directoryText(it) == "jetbrains" }
        assertEquals(setOf("kotlin", "compiler", "nestedSrc"), childTexts(pathOnlyJetbrains))
        assertEquals(
            "kotlin",
            directoryText(directoryChildren(pathOnlyJetbrains).single { directoryText(it) == "kotlin" })
        )

        val pathOnlyCompiler =
            pathOnlyNode.children.filterIsInstance<DomainDirectoryNode>().single { directoryText(it) == "compiler" }
        val testDataNode = directoryChildren(pathOnlyCompiler).single()
        assertEquals("testData", directoryText(testDataNode))
        assertEquals("fixture", directoryText(directoryChildren(testDataNode).single()))
        assertTrue(pathOnlyNode.contains(nonPackageFile))

        val emptyMiddleNode = domainNode(viewSettings(hideEmptyMiddlePackages = true))
        val emptyMiddleSourceRoot =
            emptyMiddleNode.children.filterIsInstance<DomainDirectoryNode>().single { directoryText(it) == "src" }
        val emptyMiddleOrg = directoryChildren(emptyMiddleSourceRoot).single()
        val emptyMiddleJetbrains = directoryChildren(emptyMiddleOrg).single { directoryText(it) == "jetbrains" }
        assertEquals(setOf("kotlin", "compiler", "nestedSrc"), childTexts(emptyMiddleJetbrains))
        val compactedChain = directoryChildren(emptyMiddleOrg).single { directoryText(it).startsWith("chain") }
        assertEquals("chain.middle.leaf", directoryText(compactedChain))
        compactedChain.assertRendersProjectedLabel("chain.middle.leaf")
        assertTrue(compactedChain.canRepresent(chainDirectory))
        assertTrue(compactedChain.canRepresent(middleDirectory))
        assertTrue(compactedChain.canRepresent(leafDirectory))
        assertTrue(compactedChain.contains(leafFile))
        assertTrue(emptyMiddleNode.contains(assignedFile))
        assertFalse(emptyMiddleNode.contains(unassignedPackageDirectory.findChild("Other.txt")!!))
        assertTrue(emptyMiddleNode.contains(ignoredFile))
        val emptyMiddleCompiler =
            emptyMiddleNode.children.filterIsInstance<DomainDirectoryNode>().single { directoryText(it) == "compiler" }
        val unflattenedTestData = directoryChildren(emptyMiddleCompiler).single()
        assertEquals("testData", directoryText(unflattenedTestData))
        assertEquals("fixture", directoryText(directoryChildren(unflattenedTestData).single()))

        val flattenedNode = domainNode(viewSettings(flattenPackages = true))
        val flattenedSourceRoot =
            flattenedNode.children.filterIsInstance<DomainDirectoryNode>().single { directoryText(it) == "src" }
        val flattenedPackages = directoryChildren(flattenedSourceRoot).associateBy(::directoryText)
        assertEquals(
            setOf(
                "org", "org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlin.ignored", "org.jetbrains.compiler",
                "org.chain", "org.chain.middle", "org.chain.middle.leaf",
            ),
            flattenedPackages.keys,
        )
        assertFalse(flattenedPackages.containsKey("org.unassigned"))
        assertEquals(setOf("Root.txt"), childTexts(flattenedPackages.getValue("org")))
        val flattenedJetbrains = flattenedPackages.getValue("org.jetbrains")
        assertEquals(setOf("nestedSrc"), childTexts(flattenedJetbrains))
        assertTrue(flattenedJetbrains.contains(nestedSourceFile))
        val flattenedKotlin = flattenedPackages.getValue("org.jetbrains.kotlin")
        flattenedKotlin.assertRendersProjectedLabel("org.jetbrains.kotlin")
        assertTrue(flattenedKotlin.canRepresent(kotlinDirectory))
        assertFalse(flattenedKotlin.canRepresent(jetbrainsDirectory))
        assertTrue(flattenedKotlin.contains(assignedFile))
        assertFalse(flattenedKotlin.contains(rootFile))
        assertTrue(flattenedKotlin.contains(excludedFile))
        assertFalse(flattenedKotlin.contains(ideIgnoredFile))
        assertEquals(setOf("Assigned.txt", "excluded"), childTexts(flattenedKotlin))
        val excludedDirectoryNode = directoryChildren(flattenedKotlin).single { directoryText(it) == "excluded" }
        assertEquals(setOf("Excluded.txt"), childTexts(excludedDirectoryNode))
        assertEquals(setOf("Compiler.txt"), childTexts(flattenedPackages.getValue("org.jetbrains.compiler")))
        val flattenedMiddle = flattenedPackages.getValue("org.chain.middle")
        assertTrue(flattenedMiddle.contains(leafFile))

        val flattenedAndCompactedNode = domainNode(viewSettings(flattenPackages = true, hideEmptyMiddlePackages = true))
        val compactedSourceRoot = flattenedAndCompactedNode.children.filterIsInstance<DomainDirectoryNode>()
            .single { directoryText(it) == "src" }
        val compactedPackages = directoryChildren(compactedSourceRoot).associateBy(::directoryText)
        assertEquals(
            setOf(
                "org", "org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlin.ignored",
                "org.jetbrains.compiler", "org.chain.middle.leaf",
            ),
            compactedPackages.keys,
        )
        val compactedJetbrains = compactedPackages.getValue("org.jetbrains")
        assertTrue(compactedJetbrains.canRepresent(jetbrainsDirectory))
        assertTrue(compactedJetbrains.contains(nestedSourceFile))
        val visibleNestedSourceRoot = directoryChildren(compactedJetbrains).single { directoryText(it) == "nestedSrc" }
        assertEquals(setOf("Nested.txt"), childTexts(visibleNestedSourceRoot))
        val compactedKotlin = compactedPackages.getValue("org.jetbrains.kotlin")
        assertFalse(compactedKotlin.canRepresent(jetbrainsDirectory))
        assertFalse(compactedKotlin.contains(jetbrainsDirectory))
        assertEquals(setOf("Assigned.txt", "excluded"), childTexts(compactedKotlin))

    }

    fun testContentRootModuleAnnotationFollowsModuleRenames() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        lateinit var contentRoot: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-module-rename-test")
            contentRoot.createChildData(this, "Assigned.txt")
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(contentRoot)
            rootModel.commit()
        }
        val settings = viewSettings()
        val contentRootPsi = PsiManager.getInstance(project).findDirectory(contentRoot)!!
        val repositoryRoot = RepositoryRoot.of(projectRoot)
        val dump = DomainDump(
            entries = listOf(DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of(contentRoot.name))),
        )
        val domainNode = DomainGroupNode(
            project,
            settings,
            Domain.of("Alpha"),
            0,
            repositoryRoot,
            dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true },
        )

        val originalName = module.name
        val renamedName = "$originalName-renamed"
        renameModule(originalName, renamedName)
        try {
            val standardPresentation = PresentationData().also {
                PsiDirectoryNode(project, contentRootPsi, settings).update(it)
            }
            assertTrue(standardPresentation.allText().contains("[$renamedName]"))

            val domainPresentation = (domainNode.children.single() as DomainDirectoryNode).updatedPresentation()
            assertFalse(domainPresentation.allText().contains("[$renamedName]"))
            assertEquals(contentRoot.name, domainPresentation.displayedDirectoryText())
        } finally {
            renameModule(renamedName, originalName)
        }
    }

    private fun assertDeclarationOrderUnderAllSortSettings(groups: List<DomainGroupNode>, expected: List<String>) {
        groups.forEach { it.update() }
        for (sortKey in NodeSortKey.entries) {
            for (foldersOnTop in listOf(false, true)) {
                val comparator = domainDeclarationOrderComparator(groupByTypeComparator(sortKey, foldersOnTop))
                assertEquals(
                    expected,
                    groups.reversed().sortedWith(comparator).map { it.value },
                    "sortKey=$sortKey, foldersOnTop=$foldersOnTop",
                )
            }
        }
    }

    private fun groupByTypeComparator(sortKey: NodeSortKey, foldersOnTop: Boolean): GroupByTypeComparator =
        object : GroupByTypeComparator(project, DomainProjectViewPane.VIEW_ID) {
            override fun getSortKey(): NodeSortKey = sortKey

            override fun isFoldersAlwaysOnTop(): Boolean = foldersOnTop
        }

    private fun loadDomainGroups(dumpText: String): List<DomainGroupNode> {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        val loaded = CountDownLatch(1)
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(DomainProjectService.MODEL_CHANGED_TOPIC, DomainModelListener { loaded.countDown() })
            WriteAction.runAndWait<RuntimeException> {
                val contentRoot = projectRoot.createChildDirectory(this, "compiler-domain-group-test")
                contentRoot.createChildData(this, "Assigned.txt")
                contentRoot.createChildData(this, "Ignored.txt")
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(contentRoot)
                rootModel.commit()

                val repoDirectory = projectRoot.findChild("repo") ?: projectRoot.createChildDirectory(this, "repo")
                repoDirectory.createChildData(this, "domains.dump.txt").setBinaryContent(dumpText.toByteArray())
            }
            val root = DomainTreeStructure(project, DomainProjectViewPane.VIEW_ID).rootElement as ProjectViewNode<*>
            val deadline = System.nanoTime() + 10_000_000_000
            while (loaded.count > 0 && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertEquals(0L, loaded.count, "The active dump should load and publish a model change")
            assertEquals(parseDomainDump(dumpText), DomainProjectService.getInstance(project).dump)
            return root.children.map { it as DomainGroupNode }
        } finally {
            connection.disconnect()
        }
    }

    private fun renameModule(oldName: String, newName: String) {
        WriteAction.runAndWait<RuntimeException> {
            val moduleModel = ModuleManager.getInstance(project).getModifiableModel()
            moduleModel.renameModule(moduleModel.findModuleByName(oldName)!!, newName)
            moduleModel.commit()
        }
    }

    override fun tearDown() {
        val probeDirectories = listOf(
            "compiler-domain-refresh-test",
            "compiler-domain-marker-unrelated-test",
            "compiler-domain-ignored-test",
            "compiler-domain-unrelated-test",
            "compiler-domain-presentation-test",
            "compiler-domain-directory-options-test",
            "compiler-domain-group-test",
            "compiler-domain-projection-test",
            "compiler-domain-module-rename-test",
            "repo",
        )
            .mapNotNull { name -> project.basePath?.let { File(it, name) } }
        try {
            super.tearDown()
        } finally {
            // Disk-only cleanup: a VFS delete event would trigger the bundled Vue plugin's LSP initialization.
            probeDirectories.forEach(FileUtil::delete)
        }
    }
}

private fun PresentationData.allText(): String =
    listOfNotNull(presentableText, locationString).joinToString(" ") + coloredText.joinToString("") { it.text }

private fun PresentationData.displayedDirectoryText(): String =
    coloredText.joinToString("") { it.text }.ifEmpty { presentableText.orEmpty() }.trim()

private fun DomainDirectoryNode.assertRendersProjectedLabel(expected: String) {
    val presentation = updatedPresentation()
    presentation.coloredText.forEach { fragment ->
        assertNotNull(fragment.attributes, "coloredText fragment '${fragment.text}' must have attributes")
    }
    assertEquals(expected, presentation.displayedDirectoryText())
    update()
    this.presentation.coloredText.forEach { fragment ->
        assertNotNull(fragment.attributes, "coloredText fragment '${fragment.text}' must have attributes")
    }
    val renderer = NodeRenderer()
    renderer.customizeCellRenderer(JTree(), this, false, false, true, 0, false)
    assertTrue(
        renderer.getCharSequence(false).contains(expected),
        "Rendered text '${renderer.getCharSequence(false)}' should contain '$expected'",
    )
}

private fun viewSettings(
    flattenPackages: Boolean = false,
    hideEmptyMiddlePackages: Boolean = false,
    abbreviatePackageNames: Boolean = false,
    compactDirectories: Boolean = false,
    foldersAlwaysOnTop: Boolean = false,
): ViewSettings = Proxy.newProxyInstance(
    ViewSettings::class.java.classLoader,
    arrayOf(ViewSettings::class.java),
) { _, method, _ ->
    when (method.name) {
        "isFlattenPackages" -> flattenPackages
        "isHideEmptyMiddlePackages" -> hideEmptyMiddlePackages
        "isAbbreviatePackageNames" -> abbreviatePackageNames
        "isCompactDirectories" -> compactDirectories
        "isFoldersAlwaysOnTop" -> foldersAlwaysOnTop
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            else -> null
        }
    }
} as ViewSettings

private fun timeSortViewSettings(): ProjectViewSettings = Proxy.newProxyInstance(
    ProjectViewSettings::class.java.classLoader,
    arrayOf(ProjectViewSettings::class.java),
) { _, method, _ ->
    when (method.name) {
        "getSortKey" -> NodeSortKey.BY_TIME_ASCENDING
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            else -> null
        }
    }
} as ProjectViewSettings
