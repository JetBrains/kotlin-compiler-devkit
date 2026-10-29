# Remote Run

The "Remote Run" tool window launches the aggregate TeamCity test run for a branch of the Kotlin repository and
follows it until it finishes. It is the same run a GitHub `/dry-run` comment or `scripts/teamcity-run.sh` starts.

## Requirements

- The bundled Git plugin (the tool window is registered in `resources/META-INF/git4idea-integration.xml`).
- The [TeamCity CLI](https://www.jetbrains.com/help/teamcity/teamcity-cli-get-started.html) `teamcity`, version 1.6.1
  or newer, on the `PATH` or in `/opt/homebrew/bin` / `/usr/local/bin`.

## Usage

- **Launch Test Run** (toolbar) always runs the checked out branch. Right-click a branch to run another one.
- All local branches are listed: the current branch first, then branches by their latest run, then the rest by name.
  The search field filters them like the Git branches popup.
- A branch with a known run shows the latest status instead of the branch icon (spinner, success or failure).
- Build rows show test counts and, while scheduling or running, the elapsed time.
- Expand a failed build to list its failed tests; double-click a test to open it in the editor.
- Double-click a build to open it on TeamCity. Unfinished builds can be stopped from the context menu.

## How it works

All code lives in `src/org/jetbrains/kotlin/test/helper/teamcity` and `.../helper/git`.

| File | Responsibility |
|---|---|
| `RemoteRunToolWindowFactory.kt` | Tool window UI: tree, search field, renderer, toolbar and context menu actions |
| `TeamCityTestLaunchService.kt` | Launch and stop flow: checks, dialogs, progress, CLI calls |
| `TeamCityCli.kt` | Command lines and output parsing for the `teamcity` CLI |
| `GitCommandFacade.kt` | Picks the TeamCity remote and computes the branch push state |
| `RememberedTeamCityBuild.kt` | Persists launched builds per project |
| `TeamCityBuildStatusService.kt` | Polls build statuses and failed tests |
| `TeamCityBuildStatus.kt`, `FailedTests.kt` | REST URLs, JSON parsing, status icons |

### Launching

1. The branch is checked against the GitHub `JetBrains/kotlin` remote, using the remote-tracking refs from the last
   fetch. Unpushed commits ask to push first; diverged, missing or detached branches are refused.
2. If the branch already has unfinished builds, the user is asked to stop them (default), keep them or cancel.
3. The CLI checks the login (`teamcity auth status`) and, if needed, runs `teamcity auth login` in a Run console.
4. `teamcity run start Kotlin_KotlinDev_AggregateTrigger --branch <branch> --no-push --json --no-input` runs in a
   background Run console tab. The build id is read from the JSON output.
5. The new build is remembered. Builds the user chose to stop are cancelled with `teamcity run cancel` only after the
   launch succeeded.

### Following a build

- Launched builds (the 50 newest) are stored in the workspace file (`KotlinDevKitTeamCityBuilds`).
- `TeamCityBuildStatusService` polls TeamCity's guest REST API (`/guestAuth/app/rest/...`) through the IDE's
  `HttpRequests`, so no login is needed for reading. It polls every minute while the tool window is open, and every
  15 seconds while a build is still scheduling.
- The launched build is an *Aggregate Trigger*, which only starts the real *Aggregate (master)* build. Until the trigger
  finishes, the row shows "Scheduling…". Once it finishes, its status text contains the Aggregate build URL, and the
  remembered trigger is replaced by that build.
- Test counts come from the build's `testOccurrences` field. For builds with failures, the failed occurrences
  (at most 200) are fetched once per change of the failure count and cached.
- Navigation uses the parsed test name from TeamCity (package, class, method) to find the class with `JavaPsiFacade`,
  then the method by name. If nothing is found, the build opens on TeamCity.

## Tests

Tests are in `test/org/jetbrains/kotlin/test/helper/teamcity` and `.../helper/git`:

```bash
./gradlew test --tests '*TeamCity*' --tests '*RemoteRun*' --tests '*FailedTest*'
```
