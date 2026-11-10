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
- Besides the runs launched from the IDE, the newest aggregate TeamCity builds of the 10 most recently checked out
  branches are shown; they are fetched on every refresh and kept in memory only. Builds of other branches are
  fetched on demand and then stay listed like the automatically fetched ones.
- Safe merges show up under their branch as well, marked by their own safe-merge icons instead of the status
  marks; dry runs use a dashed variant and add a grayed "dry run" suffix. If a safe merge's rebase fails before
  a gate is launched, the coordinator build itself is shown with its failure.
- A branch with a known run shows the latest status instead of the branch icon (spinner, success or failure).
- Build rows show test counts and, while scheduling or running, the elapsed time.
- Expand a failed build to list its failed tests; double-click a test or use its context menu's "Navigate to Test"
  action to open it in the editor when its source is found. If the source cannot be found, no navigation occurs.
- The build context menu offers stopping unfinished builds, opening the build on TeamCity and removing remembered
  launches from the list. Use "Open in TeamCity" to open a build in the browser.
- The branch context menu offers launching the branch and "Refresh Branch", which fetches its aggregate builds and
  safe merges on demand; branches outside the recently checked out ones are fetched only that way.

## How it works

All code lives in `src/org/jetbrains/kotlin/test/helper/teamcity` and `.../helper/git`.

| File | Responsibility |
|---|---|
| `RemoteRunToolWindowFactory.kt` | Tool window UI: tree, search field, renderer, toolbar and context menu actions |
| `TeamCityTestLaunchService.kt` | Launch and stop flow: checks, dialogs, progress, CLI calls |
| `TeamCityCli.kt` | Command lines and output parsing for the `teamcity` CLI |
| `GitCommandFacade.kt` | Picks the TeamCity remote, computes the branch push state, reads the recently checked out branches |
| `RememberedTeamCityBuild.kt` | Persists launched builds per project |
| `TeamCityBuildStatusService.kt` | Polls build statuses and failed tests; fetches branch builds and safe merges per round and on demand |
| `TeamCityBranchBuilds.kt` | Fetches the aggregate builds and safe merges of the requested branches, and the build queue |
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
- Every refresh round also fetches the newest 5 aggregate builds of the 10 most recently checked out branches
  (current branch included; read from the HEAD reflog) via guest REST
  (`buildType:Kotlin_KotlinDev_Aggregate`, `branch:<name>`; `master` queries the default branch; running and
  canceled chains are included) plus the
  aggregate build queue, since queued builds are not served by the builds endpoint. Fetched builds live in
  memory only and merge with the remembered launches (same build id ⇒ the remembered launch wins);
  "Remove from List" therefore applies to remembered launches only.
- Branches outside the recent ones are fetched only through their context-menu action. Every round replaces the
  builds of the branches it fetches and keeps the on-demand builds of the others, whose unfinished builds keep
  being polled like remembered launches.
- Safe merges are fetched like the aggregates: every round also requests the newest 5 Safe-Merge Coordinator builds
  of each fetched branch except `master` (its default-branch builds are chain cleanup steps, not safe merges).
  Each coordinator build's comment names the aggregate gate build it launched on the merge ref — the row becomes
  that gate build, with its state, test counts, failed tests and elapsed time, and "Open in TeamCity" points at
  it. Until the comment names a gate, or when the rebase failed, the row is the coordinator build itself.
  A gate's status is fetched each round until it finishes; a failed fetch keeps the row with its last known
  status instead of dropping it.
- While a round is running, the refresh button itself turns into a spinner; the status line under
  the tree explains the outcome: fetching, no builds found for the branches, or a connection problem (e.g. no VPN).
- `TeamCityBuildStatusService` polls TeamCity's guest REST API (`/guestAuth/app/rest/...`) through the IDE's
  `HttpRequests`, so no login is needed for reading. It polls every minute while the tool window is open, and every
  15 seconds while a build is still scheduling.
- The launched build is an *Aggregate Trigger*, which only starts the real *Aggregate (master)* build. Until the trigger
  finishes, the row shows "Scheduling…". Once it finishes, its status text contains the Aggregate build URL, and the
  remembered trigger is replaced by that build.
- Test counts come from the build's `testOccurrences` field. For builds with failures, the failed occurrences
  (at most 200) are fetched once per change of the failure count and cached.
- Navigation uses the parsed test name from TeamCity (package, class, method) to find the class with `JavaPsiFacade`,
  then the method by name. If the class is found but the method is not, navigation opens the class; if the class is
  not found, navigation does nothing.

## Tests

Tests are in `test/org/jetbrains/kotlin/test/helper/teamcity` and `.../helper/git`:

```bash
./gradlew test --tests '*TeamCity*' --tests '*RemoteRun*' --tests '*FailedTest*'
```
