package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.atomic.AtomicBoolean

private const val DOMAINS_YAML_PATH = "repo/domains.yaml"

internal data class TestFederationIgnoreRule(
    val matcher: PathMatcher,
    val negated: Boolean,
)

private data class CachedIgnoreRules(
    val configurationFile: VirtualFile,
    val modificationStamp: Long,
    val rules: List<TestFederationIgnoreRule>,
)

/**
 * Single entry point for YAML ignore checks: finds `repo/domains.yaml` once per call
 * and returns before creating the service, and its listener, in projects without it.
 */
internal fun isIgnoredByTestFederation(project: Project, file: VirtualFile): Boolean {
    if (!file.isValid) return false
    val repositoryRoot = findTestFederationRepositoryRoot(project) ?: return false
    return TestFederationIgnoreService.getInstance(project).isIgnored(repositoryRoot, file)
}

/**
 * The project root, if it contains `repo/domains.yaml` as a file.
 */
private fun findTestFederationRepositoryRoot(project: Project): RepositoryRoot? {
    val candidate = project.basePath
        ?.let(FileUtil::toSystemIndependentName)
        ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        ?: return null

    if (candidate.findFileByRelativePath(DOMAINS_YAML_PATH)?.isFile != true) return null
    return RepositoryRoot.of(candidate)
}

@Service(Service.Level.PROJECT)
class TestFederationIgnoreService(private val project: Project) : Disposable {
    companion object {
        fun getInstance(project: Project): TestFederationIgnoreService =
            project.getService(TestFederationIgnoreService::class.java)
    }

    @Volatile
    private var cachedIgnoreRules: CachedIgnoreRules? = null

    private val repositoryRoot: RepositoryRoot?
        get() = findTestFederationRepositoryRoot(project)

    private val domainsYamlPath = RepositoryPath.of(DOMAINS_YAML_PATH)

    private val domainsYamlListenerInstalled = AtomicBoolean(false)

    /**
     * [repositoryRoot] is the project root, which contains `repo/domains.yaml`.
     */
    internal fun isIgnored(repositoryRoot: RepositoryRoot, file: VirtualFile): Boolean {
        installConfigurationListener()
        if (!VfsUtil.isAncestor(repositoryRoot.file, file, false)) return false
        val domainsYamlFile = repositoryRoot.resolve(domainsYamlPath) ?: return false
        if (!domainsYamlFile.isFile) return false
        val rules = ignoreRules(domainsYamlFile)
        if (rules.isEmpty()) return false
        val relativePath = repositoryRoot.relativePathOf(file) ?: return false
        return rules.isIgnored(relativePath)
    }

    override fun dispose() {
        cachedIgnoreRules = null
    }

    /**
     * Installed once the marker is first seen; it stays to refresh the view on later edits or removal.
     */
    private fun installConfigurationListener() {
        if (!domainsYamlListenerInstalled.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = afterVFSChanges(events)
            },
        )
    }

    private fun afterVFSChanges(events: List<VFileEvent>) {
        val repositoryRoot = repositoryRoot ?: return
        val domainsYamlFile = repositoryRoot.resolve(domainsYamlPath) ?: return
        if (!domainsYamlFile.isValid) return
        if (!domainsYamlFile.isFile) return
        if (events.any { it.affectsDomainYaml() }) {
            cachedIgnoreRules = null
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) ProjectView.getInstance(project).refresh()
            }
        }
    }

    private fun ignoreRules(configurationFile: VirtualFile): List<TestFederationIgnoreRule> {
        val document = FileDocumentManager.getInstance().getCachedDocument(configurationFile)
        val modificationStamp = document?.modificationStamp ?: configurationFile.modificationStamp

        cachedIgnoreRules?.let { cached ->
            if (cached.configurationFile == configurationFile && cached.modificationStamp == modificationStamp) {
                return cached.rules
            }
        }

        val rules = loadIgnoreRules(configurationFile, document)
        cachedIgnoreRules = CachedIgnoreRules(configurationFile, modificationStamp, rules)
        return rules
    }

    private fun loadIgnoreRules(file: VirtualFile, document: Document?): List<TestFederationIgnoreRule> = try {
        val contents = document?.text ?: file.contentsToByteArray().decodeToString()
        parseTestFederationIgnoreRules(contents)
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun VFileEvent.affectsDomainYaml(): Boolean {
        val domainsYamlFile = repositoryRoot?.resolve(domainsYamlPath) ?: return false

        val eventPaths = buildList {
            add(path)
            when (this@affectsDomainYaml) {
                is VFileMoveEvent -> add(oldPath)
                is VFilePropertyChangeEvent -> add(oldPath)
            }
        }
        return isIgnoreConfigurationPathAffected(domainsYamlFile.path, eventPaths)
    }
}

internal fun isIgnoreConfigurationPathAffected(configurationPath: String, eventPaths: Iterable<String>): Boolean {
    val normalizedConfigurationPath = FileUtil.toSystemIndependentName(configurationPath).trimEnd('/')
    return eventPaths.any { eventPath ->
        val normalizedEventPath = FileUtil.toSystemIndependentName(eventPath).trimEnd('/')
        normalizedEventPath == normalizedConfigurationPath ||
            FileUtil.isAncestor(normalizedEventPath, normalizedConfigurationPath, false)
    }
}

internal fun parseTestFederationIgnoreRules(contents: String): List<TestFederationIgnoreRule> {
    val loaderOptions = LoaderOptions().apply {
        isAllowDuplicateKeys = false
    }
    val root = try {
        Yaml(SafeConstructor(loaderOptions)).load<Any?>(contents) as? Map<*, *> ?: return emptyList()
    } catch (_: RuntimeException) {
        return emptyList()
    }
    val patterns = root["ignore"] as? List<*> ?: return emptyList()
    return patterns.mapNotNull { pattern -> (pattern as? String)?.let(::compileIgnoreRule) }
}

private fun compileIgnoreRule(pattern: String): TestFederationIgnoreRule? {
    val negated = pattern.startsWith('!')
    val glob = if (negated) pattern.substring(1) else pattern
    if (glob.isBlank()) return null

    val matcher = try {
        FileSystems.getDefault().getPathMatcher("glob:${glob}")
    } catch (_: IllegalArgumentException) {
        return null
    }
    return TestFederationIgnoreRule(matcher, negated)
}

internal fun List<TestFederationIgnoreRule>.isIgnored(path: RepositoryPath): Boolean {
    var currentPath = Path.of("")
    var ignored = false
    for (segment in Path.of(path.relativePathString)) {
        currentPath = currentPath.resolve(segment)
        val matchingRule = lastOrNull { it.matcher.matches(currentPath) }
        if (matchingRule != null) ignored = !matchingRule.negated
    }
    return ignored
}
