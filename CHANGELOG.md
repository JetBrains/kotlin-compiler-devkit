<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

## Unreleased
- Add the "Remote Run" tool window: launch, follow and stop aggregate TeamCity test runs for local branches, with test
  counts and navigation to failed tests. The runs of the recently checked out branches are fetched automatically —
  including safe merges, which are marked by dedicated safe-merge icons and follow the aggregate gate the
  Safe-Merge Coordinator launched. See [docs/remote-run.md](docs/remote-run.md).
- Add "Launch: Safe Merge Dry Run (Rebase)" to the branch context menu of the "Remote Run" tool window: it resolves the
  branch's open `JetBrains/kotlin` pull request and starts the Safe-Merge Coordinator in dry-run mode, following
  the aggregate gate it launches.
- Mark branches with an open `JetBrains/kotlin` pull request with a GitHub logo in the "Remote Run" tool window and
  add "Open Pull Request" to their context menu; the open pull request of every listed branch is resolved through the
  GitHub plugin, at most once a minute. The GitHub requests — also of the safe-merge pull-request resolution —
  authenticate with the GitHub account configured in the IDE, which GitHub's GraphQL API effectively requires. See
  [docs/remote-run.md](docs/remote-run.md).
- Add "Run Tests Locally" actions to the "Remote Run" tool window.
- Show the severity of diagnostics declared in a `KtDiagnosticsContainer` as a colored badge in Quick Documentation,
  together with its KDoc, the language feature of deprecation diagnostics, the positioning strategy, the PSI type and
  the containing container.

## 1.0.113
- Migrate test data manager actions to the `checkTestData` / `updateTestData` Gradle tasks (options passed as `-P` properties). The removed `manageTestDataGlobally` task is no longer used.

## 1.0.68
- Support folding of `// EXPECTATIONS` groups in stepping tests

## 1.0.67
- Support `--incremental` flag in `manageTestDataGlobally` command via new `Update Test Data (Incremental)` action.

## 1.0.66
- Add actions to run `manageTestDataGlobally` for selected files.

## 0.0.19
- Fixed an issue when "Available files" combo box didn't update until the editor was closed and reopened again.

## 0.0.18
- Fix searching for additional related files when $TEST_DATA_FILE$ doesn't contain subdirectories.

## 0.0.17
- Fix an NPE that occured sometimes when searching for additional related files.

## 0.0.16
- Support specifying search paths for additional related files.

## 0.0.15
- Migrate plugin to 213 platform

## 0.0.14
- Fix `UninitializedPropertyAccessException` on 212 platform

## 0.0.13
- Limit max compatible version with 2021.2 

## 0.0.12
- Initial release of plugin
