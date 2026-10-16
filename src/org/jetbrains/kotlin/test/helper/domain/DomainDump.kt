package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * In memory representation of the `domains.dump.txt` which is stored in kotlin.git.
 * This dump, compactly, contains the association of files with their domain.
 *
 * @property entries contains the raw entries of files being associated with a given domain
 * @property paths contains a map from a given domain to all paths associated with this domain
 *
 * Note: Paths also include folders if, and only if all descendants of this folder do are contained in within the same
 * domains.
 */
data class DomainDump(
    val entries: List<DomainDumpEntry>,
) {
    /**
     * Maps any domain to all paths that belong to said domain
     */
    val paths: Map<Domain, Set<RepositoryPath>> = buildMap<Domain, MutableSet<RepositoryPath>> {
        this@DomainDump.entries.forEach { entry ->
            entry.domains.forEach { domain ->
                getOrPut(domain) { mutableSetOf() }.add(entry.path)
            }
        }
    }

    companion object {
        val EMPTY = DomainDump(emptyList())
    }
}

/**
 * See [DomainDump]
 */
data class DomainDumpEntry(
    val domains: Set<Domain>,
    val path: RepositoryPath,
)

/**
 * Denotes that the [file] carried here is representing the root of the kotlin.git repository
 */
@JvmInline
value class RepositoryRoot private constructor(val file: VirtualFile) {
    companion object {
        fun of(file: VirtualFile): RepositoryRoot = RepositoryRoot(file)
    }

    fun resolve(path: RepositoryPath): VirtualFile? {
        return file.findFileByRelativePath(path.relativePathString)
    }

    fun relativePathOf(file: VirtualFile): RepositoryPath? {
        if (!VfsUtil.isAncestor(this.file, file, false)) return null
        val relativePath = VfsUtil.getRelativePath(file, this.file) ?: return null
        return RepositoryPath.of(relativePath)
    }
}

/**
 * Denotes that the [relativePathString] shall be interpreted relative to the [RepositoryRoot]
 */
@JvmInline
value class RepositoryPath private constructor(val relativePathString: String) {
    companion object {
        fun of(path: String): RepositoryPath {
            return RepositoryPath(FileUtil.toSystemIndependentName(path.trim()))
        }
    }
}

@JvmInline
value class Domain private constructor(val name: String) {
    companion object {
        fun of(domain: String): Domain {
            return Domain(domain.trim())
        }
    }

    override fun toString(): String {
        return name
    }
}

fun parseDomainDump(text: String): DomainDump {
    val entries = mutableListOf<DomainDumpEntry>()
    var currentDomains: Set<Domain>? = null

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) continue

        if (line.startsWith("- ")) {
            val path = line.substring(2).trim()
            if (path.isEmpty()) continue
            currentDomains?.let { domains -> entries += DomainDumpEntry(domains, RepositoryPath.of(path)) }
            continue
        }

        if (line.endsWith(':')) {
            val heading = line.dropLast(1).trim()
            val domains = heading.split(',').map(String::trim).filter(String::isNotEmpty)
                .map { name -> Domain.of(name) }
                .toSet()

            if (domains.isNotEmpty()) {
                currentDomains = domains
            }
        }
    }

    return DomainDump(entries)
}