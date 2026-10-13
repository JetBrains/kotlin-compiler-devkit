---
sessionId: session-261013-115122-rj0r
---

# Requirements

### Overview & Goals
Add an enabled-by-default project setting to show or hide the existing test-federation gutter markers, without changing test scheduling.

### Functional Requirements
- Provide a checkbox in the existing Kotlin Compiler DevKit project settings to enable or disable test-federation gutter markers.
- Persist the option per project and default it to enabled, including when loading settings saved before this option existed.
- When disabled, hide smoke and contract markers and their click popups; when enabled, preserve the current marker and schedule details.
- Provide a right-click gutter action to disable test-federation markers project-wide; this uses the same persisted setting, removes the markers immediately, and the settings checkbox can re-enable them.
- Do not change test scheduling or add a dependency on the Kotlin repository’s test-runtime artifact.

# Technical Design

### Current Implementation
`TestFederationLineMarkerProvider` in `src/org/jetbrains/kotlin/test/helper/codeinsight/TestFederationLineMarkerProvider.kt` resolves effective smoke/contract schedules and creates Kotlin gutters; it is registered in `resources/META-INF/plugin.xml`. IntelliJ `GutterIconRenderer` supports context-menu actions through `getPopupMenuActions()`, but the current federation markers do not expose one. `TestDataPathsConfiguration` in `src/org/jetbrains/kotlin/test/helper/TestDataPluginSettings.kt` is a project-level `PersistentStateComponent` saved to `kotlinTestDataPluginTestDataPaths.xml`. The same file’s `TestDataPathsConfigurable` uses `BoundConfigurable` and existing `JBCheckBox` controls, with explicit state synchronization in `isModified()`, `reset()`, and `apply()`. 

### Key Decisions
- Store a Boolean setting on the existing project-scoped `TestDataPathsConfiguration`, defaulting to `true`; this reuses current persistence and keeps the feature preference project-specific.
- Add the option to `TestDataPathsConfigurable` and thread it through the existing settings-state, `isModified()`, `reset()`, and `apply()` lifecycle rather than adding another service or configurable.
- Have the line-marker provider check the setting before PSI schedule/test resolution. Restart project highlighting when the value changes so open editors reflect settings or gutter-menu toggles without being reopened.
- Add a concise action to the federation gutter renderer’s context menu that disables markers project-wide by updating the same project setting; users re-enable them from the existing settings checkbox.

### Proposed Changes
Extend the existing project settings component and configurable with a default-enabled checkbox, then gate `TestFederationLineMarkerProvider` on that setting before its current resolution work. Add a gutter-renderer context action that changes the same setting to disabled, and re-trigger daemon highlighting after either settings or context-menu changes. Cover default, persistence, context-menu disable, and re-enabled behavior in focused tests.

### File Structure
- Update `src/org/jetbrains/kotlin/test/helper/TestDataPluginSettings.kt` with the persisted enabled-by-default option and its settings checkbox lifecycle.
- Update `src/org/jetbrains/kotlin/test/helper/codeinsight/TestFederationLineMarkerProvider.kt` to skip marker creation when the option is disabled.
- Update `resources/messages/MyBundle.properties` with the context-menu action label.
- Extend `test/org/jetbrains/kotlin/test/helper/codeinsight/TestFederationLineMarkerProviderTest.kt` and add settings-state coverage as appropriate.

# Testing

### Validation Approach
- Verify a fresh/default configuration and a legacy saved state both leave the option enabled; verify the option persists after apply/load.
- Verify disabled settings suppress function and class gutters, and enabling them restores existing smoke/contract markers and popup schedules.
- Verify applying a changed value refreshes markers in already-open editors; run focused settings/provider tests and build the plugin.
- Verify the gutter context menu exposes a disable action, invoking it persists the disabled setting and removes markers on refresh, and the settings checkbox can re-enable them.

# Delivery Steps

### ✓ Step 1: Resolve effective JUnit scheduling tags with platform PSI utilities
A small Kotlin PSI lookup returns effective smoke and contract tags from direct, composed, and inherited annotations.

- Add a compact schedule data class and top-level extraction functions in `TestFederationLineMarkerProvider.kt`.
- Use `AnnotationUtil` for inherited class annotations and traverse resolved annotation PSI for `@Tag` metadata; the `MetaAnnotationUtil` precheck is intentionally omitted because it missed Kotlin light-class annotation chains.
- Map `smoke` and `contract:<Domain>` values to schedule records, deduplicate effective tags, and bound traversal with a shared visited set and standard-meta-annotation pruning.
- Add focused tests for `MustRunAlways` metadata, contract domains, composed annotations, inherited abstract-class annotations, repeated/container tags, and unrelated tags.

### ✓ Step 2: Display and explain effective schedules in the editor gutter
Kotlin test declarations display a smoke or contract marker, and clicking it reveals every effective scheduling trigger.

- Add the line-marker provider and click popup in `TestFederationLineMarkerProvider.kt`; put function-level markers on functions and class-level/inherited markers on concrete test classes.
- Use smoke and contract icon treatments and format popup details for smoke and each contract domain, including relevant scheduling conditions.
- Add SVG assets under `resources/icons/`, popup strings in `resources/messages/MyBundle.properties`, and the Kotlin provider registration in `resources/META-INF/plugin.xml`.
- Add focused PSI/UI tests for direct, meta-annotated, and inherited schedules, combined/multiple tags, marker absence without relevant tags, and popup content; build the plugin and run those tests.

### ✓ Step 3: Persist and expose the default-enabled marker setting
The existing project settings store and present a test-federation gutter-marker checkbox that defaults to enabled.

- Add an enabled-by-default Boolean property to `TestDataPathsConfiguration` in `TestDataPluginSettings.kt`; preserve the default when loading older persisted state without the property.
- Add the checkbox to `TestDataPathsConfigurable` and include it in the UI state initialization, modified check, reset, and apply paths.
- Add focused coverage for the default value and saving/loading the setting.

### ✓ Step 4: Gate gutter markers on the setting and refresh open editors
Disabling the option removes test-federation gutters, while enabling it restores the existing marker behavior without reopening files.

- Check the project setting at the start of `TestFederationLineMarkerProvider.getLineMarkerInfo` and return without resolving schedules when disabled.
- Restart project daemon highlighting when the applied option changes.
- Extend provider tests to verify disabled suppression, enabled smoke/contract markers and popup details, plus immediate refresh behavior when the setting is changed; run the focused suite and `buildPlugin`.

### ✓ Step 5: Add a right-click action to disable federation markers
A gutter context-menu action disables test-federation markers for the current project and refreshes open editors.

- Provide a custom gutter renderer for the existing line markers and expose a localized disable action through its popup-menu actions.
- Have the action update the same persisted project setting used by the settings checkbox, then restart daemon highlighting so markers disappear immediately.
- Keep re-enabling in the existing settings configurable; avoid introducing a second preference or a separate persistence path.
- Test that the action is available from the gutter renderer, changes the stored setting, suppresses markers after refresh, and that re-enabling through settings restores them.