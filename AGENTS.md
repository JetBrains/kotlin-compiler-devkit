---
project: Kotlin
languages: [Kotlin, Java]
build-system: Gradle
repository: monorepo
---

# AGENTS.md

This file provides guidance to AI agents when working with code in this repository.

## Project Overview

Kotlin Compiler DevKit is an IntelliJ IDEA plugin that enhances the development experience for working with compiler tests in the Kotlin project or Kotlin compiler plugins. It provides a specialized test data editor, test running capabilities, and diff application features.

## Build Commands

```bash
# Build the plugin (outputs to ./build/distributions/)
./gradlew buildPlugin

# Run all tests
./gradlew test

# Run linting (ktlint + detekt)
./gradlew ktlintCheck detekt

# Run the plugin in a sandbox IDE for testing
./gradlew runIde

# Verify plugin compatibility
./gradlew verifyPlugin
```

## Run Configurations

Use JetBrains MCP `get_run_configurations()` or these pre-configured options:
- **Run Plugin** - Launches sandbox IDE with plugin installed
- **Run Tests** - Executes test suite
- **Run Verifications** - Runs plugin verification
- **Run Linters** - Runs linters verification

## Architecture

### Core Components

- **`KotlinTestDataFileEditorProvider`** (`src/.../helper/KotlinTestDataFileEditorProvider.kt`) - Entry point that provides a custom editor for test data files. Wraps the standard text editor with `TestDataEditor`.

- **`TestDataEditor`** (`src/.../helper/ui/TestDataEditor.kt`) - Split editor UI showing test file and related files side-by-side with toolbar for running tests.

- **`TestDataPathsConfiguration`** (`src/.../helper/TestDataPluginSettings.kt`) - Persists plugin settings per project in `.idea/kotlinTestDataPluginTestDataPaths.xml`.

### Key Packages

| Package | Purpose |
|---------|---------|
| `actions/` | Editor toolbar and context menu actions (run tests, apply diffs, create reproducer) |
| `gradle/` | Gradle command line building and test generation utilities |
| `ui/` | Editor UI components and settings panels |
| `reference/` | PSI references for test directives |
| `inspections/` | Code inspections and intention actions |
| `runAnything/` | "Run Anything" provider for `testGlobally` command |

### Extension Points

The plugin registers extensions in `resources/META-INF/plugin.xml`:
- `fileEditorProvider` - Custom test data editor
- `projectConfigurable` - Settings UI
- `localInspection` - Context parameter inspection
- `intentionAction` - Create contextual overload
- `completion.contributor` - Directive completion
- `psi.referenceContributor` - Directive references

## JetBrains IDE MCP - MANDATORY for file and project operations

**NEVER use these tools:** `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`.
**ALWAYS use JetBrains MCP equivalents instead.**

Use other similar tools only if it is not possible to use the JetBrains IDE MCP, and you together with the user can't manage to make it work.

### Why MCP over standard tools?

**Synchronization with IDE:**
- Standard tools work with the filesystem directly, MCP works with IDE's view of files
- If a file is open in IDE with unsaved changes, standard `Read` sees the old disk version, while MCP sees current IDE buffer
- Standard `Write`/`Edit` may conflict with IDE's buffer or not be picked up immediately
- MCP changes integrate with IDE's undo history

**IDE capabilities:**
- `search_in_files_by_text` uses IntelliJ indexes — faster than grep on large codebases
- `rename_refactoring` understands code structure and updates all references correctly
- `get_symbol_info` provides type info, documentation, and declarations
- `get_file_problems` runs IntelliJ inspections beyond syntax checking

### MCP server configuration

The JetBrains IDE MCP server can be called as `jetbrains`, `idea`, `my-idea`, `my-idea-dev`, etc.
If there are many options for the JetBrains IDE MCP server, ask the user what MCP server to use.

### Tool mapping

| Instead of      | Use JetBrains MCP                                     |
|-----------------|-------------------------------------------------------|
| `Read`          | `get_file_text_by_path`                               |
| `Edit`, `Write` | `replace_text_in_file`, `create_new_file`             |
| `Grep`          | `search_in_files_by_text`, `search_in_files_by_regex` |
| `Glob`          | `find_files_by_name_keyword`, `find_files_by_glob`    |
| `Task(Explore)` | `list_directory_tree`, `search_in_files_by_text`      |

### Additional MCP tools

- **Code analysis**: `get_symbol_info`, `get_file_problems` for understanding code
- **Refactoring**: `rename_refactoring` for symbol renaming (safer than text replacement)
- **Terminal**: `execute_terminal_command` for running commands
- **Run configurations**: `get_run_configurations()` to discover, or `execute_run_configuration(name="...")` if name is known

### MANDATORY - Verify After Writing Code

Use JetBrains MCP `get_file_problems` with errorsOnly=false to check files for warnings. FIX any warnings related to the code changes made. You may ignore unrelated warnings.