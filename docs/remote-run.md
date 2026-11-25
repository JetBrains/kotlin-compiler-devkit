# Remote Run

The "Remote Run" tool window launches the aggregate TeamCity test run for a branch of the Kotlin repository and
follows it until it finishes. It is the same run a GitHub `/dry-run` comment or `scripts/teamcity-run.sh` starts.

## Requirements

- The bundled Git plugin (the tool window is registered in `resources/META-INF/git4idea-integration.xml`).
- The bundled GitHub plugin, for the pull request indicator and the safe-merge dry run's pull request
  resolution (the implementation is registered in `resources/META-INF/github-integration.xml`).
- The [TeamCity CLI](https://www.jetbrains.com/help/teamcity/teamcity-cli-get-started.html) `teamcity`, version 1.6.1
  or newer, on the `PATH` or in `/opt/homebrew/bin` / `/usr/local/bin`.
- The [GitHub CLI](https://cli.github.com) `gh`, signed in with `gh auth login`, for the "Dry Run" and "Safe Merge"
  actions; on the `PATH` or in `/opt/homebrew/bin` / `/usr/local/bin`.

## Usage

- **Launch: Aggregate (master)** (toolbar) always runs the checked out branch. Right-click a branch to run another one
  or to use **Launch: Safe Merge Dry Run (Rebase)**.
- All local branches are listed: the current branch first, then branches by their latest run, then the rest by name.
  The search field filters them like the Git branches popup.
- Branches start collapsed. Use **Expand All Branches** or **Collapse All Branches** in the toolbar to change the
  currently displayed branch rows; these controls do not expand or collapse build and failed-test rows. Individual
  branch expansion choices are retained across refreshes and filtering for the tool window session.
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
  The test context menu can also "Run Test Locally"; a failed-test class row's context menu offers "Navigate to
  Class" and "Run Failed Tests of This Class Locally". Test, class and build rows can be multi-selected
  (Ctrl/Cmd-click); right-clicking inside the selection keeps it, and the "Run … Locally" actions then run the
  failed tests covered by the selection.
- The build context menu offers stopping unfinished builds, opening the build on TeamCity, removing remembered
  launches from the list and "Run Failed Tests Locally" for all fetched failed tests of the selected builds.
- The "Run … Locally" actions re-run the selected failed tests with Gradle against the checked out working tree,
  through the Run executor. A test is looked up by its TeamCity name (package, class, method) in the open project
  and must exist there with a `@org.jetbrains.kotlin.test.TestMetadata` annotation to run; tests that cannot be
  found in the checkout are skipped. Only the failed tests that were fetched (at most 200) can be re-run, and
  muted tests are included. The run uses the same Gradle command as the `testGlobally` Run Anything action.
- The branch context menu offers **Launch: Aggregate (master)** and **Launch: Safe Merge Dry Run (Rebase)** for the branch,
  and "Refresh Branch", which fetches its aggregate builds and safe merges on demand; branches outside the
  recently checked out ones are fetched only that way. Branches with unfinished launched safe merges keep being
  refreshed automatically.
- A branch with an open or merged pull request in `JetBrains/kotlin` shows the GitHub logo to the right of its name; its
  context menu offers "Open Pull Request" to open it in the browser and "Open Pull Request in IDE" to open it in the
  GitHub plugin's "Pull requests" tool window; the latter is hidden until the GitHub integration is installed and
  configured, that is, an account is logged in and the project knows the `JetBrains/kotlin` repository on GitHub. The
  pull request of every listed branch is resolved when the tool window becomes visible and on **Refresh**, at most once
  a minute.
- Next to the GitHub logo, a status icon shows the pull request's code owners review, as the `kotlin-safemerge` bot
  reports it in its "Code Owners" comment. The first status that applies wins:
  - red circle: a code owner requested changes;
  - yellow circle: a review is requested, or a code owner only commented;
  - white circle: a code owners rule has no reviewer;
  - green circle: every rule is approved;
  - red cross: the bot's comment is missing or cannot be read, which points to a problem with the merge
    infrastructure; commenting `/codeowners` on the pull request asks the bot to post it again.

  Hovering the icon shows the full status, whatever the icon: "Approved, ready to merge", or the lines
  "Changes requested by @…", "Waiting for review from @…" (commenters included) and "Missing reviewers for
  subsystems "…"", each only when it lists someone. A subsystem is a team owning a rule without reviewer, or the
  rule's paths when no team owns it.

  A branch without an open pull request whose latest pull request is merged shows a purple circle instead, and hovering
  it names the branch the pull request was merged into ("Merged into master"). An open pull request always wins, and a
  branch whose latest pull request was closed without merging shows no pull request at all.
- The context menu of a branch with an open pull request also offers "Dry Run" and "Safe Merge", which comment
  `/dry-run` and `/safe-merge` on the pull request for the `kotlin-safemerge` bot: a dry run runs the test pipeline
  with the changes rebased on the latest master, a safe merge rebase-merges the pull request. "Safe Merge" is offered
  only while the status icon is the green circle, i.e. every code owners rule is approved. A notification reports the
  posted comment, with a link to it, or why it could not be posted.

## How it works

TeamCity and Git code lives in `src/org/jetbrains/kotlin/test/helper/teamcity` and `.../helper/git`;
GitHub code lives in `src/org/jetbrains/kotlin/test/helper/github`.

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
| `GitHubPullRequests.kt` | Model of a branch's open or merged Kotlin pull request and of the strict resolution results |
| `GitHubPullRequestSource.kt`, `DefaultGitHubPullRequestSource.kt` | Resolve a branch's open — or merged — Kotlin pull request through the GitHub plugin's API machinery (`githubPullRequestSource` extension point; the implementation loads only with the GitHub plugin) |
| `GitHubPullRequestListing.kt` | Reads GitHub's listing of a branch's pull requests and picks the open or the merged one |
| `GitHubPullRequestService.kt` | Caches the open pull request and its code owners review, or else the merged pull request, of each listed branch, resolved per branch and throttled |
| `CodeOwnersReview.kt` | Finds and parses the `kotlin-safemerge` bot's "Code Owners" comment; the review status precedence |
| `CodeOwnersReviewBadge.kt` | Icon and tooltip of the code owners review, or of the merge, next to the pull request badge |
| `SafeMergeBotCommands.kt`, `SafeMergeBotCommandService.kt` | Which bot commands a branch offers; comments them on its pull request through the GitHub CLI |

### Launching

1. The branch is checked against the GitHub `JetBrains/kotlin` remote, using the remote-tracking refs from the last
   fetch. Unpushed commits ask to push first; diverged, missing or detached branches are refused.
2. If the branch already has unfinished builds, the user is asked to stop them (default), keep them or cancel.
3. The CLI checks the login (`teamcity auth status`) and, if needed, runs `teamcity auth login` in a Run console.
4. `teamcity run start Kotlin_KotlinDev_AggregateTrigger --branch <branch> --no-push --json --no-input` runs in a
   background Run console tab. The build id is read from the JSON output.
5. The new build is remembered. Builds the user chose to stop are cancelled with `teamcity run cancel` only after the
   launch succeeded.

The branch context menu's **Launch: Safe Merge Dry Run (Rebase)** first resolves the exact open PR for the branch in
`JetBrains/kotlin` through the GitHub plugin and pins its current head SHA. If the PR is missing, ambiguous or
unavailable, no build is started. The action starts `Kotlin_KotlinDev_SafeMergeCoordinator` with the environment variables
`SPACE_MERGE_ROBOT_MODE=Dry Run`, `SPACE_MERGE_REQUEST_URL=<PR URL>` and `HEAD_SHA=<head SHA>`; it does not use the
CLI's preview-only `--dry-run` option. The coordinator rebases the pinned head against master on TeamCity — the local
branch is not modified — and launches the aggregate gate on the rebased head; hence the "(Rebase)" in the action name. The
coordinator launch is remembered immediately and replaced by its aggregate gate row when that build appears in the
branch refresh.

### Pull request indicator

`GitHubPullRequestService` resolves the pull request of every branch listed in the tree, throttled to
once a minute and never twice in parallel; the resolution happens when the tool window becomes visible and on
both refresh actions. All GitHub requests go through the GitHub plugin's API machinery and authenticate with
the GitHub account configured in the IDE — GitHub's GraphQL API, from which the plugin reads the pull request
details, effectively requires it, because its unauthenticated per-IP quota is exhausted almost everywhere
(e.g. behind a company network). Without an account, every lookup fails and the indicator keeps its previous
state. A branch's lookup first lists the pull requests with the branch as head in every state (GitHub's REST
`pulls?state=all`, the newest 100), so telling merged branches apart takes no extra request:

- exactly one open pull request is then read in full and kept only if its head and base repository are both
  `JetBrains/kotlin`; several open pull requests are ambiguous and show nothing;
- without an open one, the branch is merged if its latest pull request — the one GitHub numbered last, among those
  whose head and base repository are both `JetBrains/kotlin` — is merged. The merge bots rebase pull requests, and
  often force-push their heads, before merging them, so neither git ancestry nor the local branch head can tell a
  merged branch; GitHub's merged state can. A branch name reused after a merge therefore shows the merged pull
  request until a new pull request is opened for it.

A failed or unreadable lookup keeps the branch's previously resolved pull request and only logs. This cache is
separate from the strict pull-request resolution of the safe-merge launch, which pins a fresh head SHA before
starting anything: it lists only the open pull requests and reads the one it finds the same way.

### Code owners review

Every refresh that finds an open pull request also reads its code owners review, from the bot's comment rather than from
GitHub's reviewers: the comment of the `kotlin-safemerge[bot]` GitHub App (only apps carry the `[bot]` suffix, so no
user can impersonate it) that starts with a "Code Owners" header or carries the bot's `CODE_OWNERS_REVIEW_COMMENT`
marker. The pull request's comments are listed through GitHub's REST API (`issues/<number>/comments`, 100 per page,
at most 3 pages); the bot comments right after the pull request is opened and then edits its comment in place, so it
is practically always on the first page. Should there be several, the most recently updated one wins.

Only the first table of the comment is read — the code owners table, with a Rule, an Owners and an Approval column;
the maintainer commands table that follows is ignored. Per rule:

- the paths are the `<code>` elements of the Rule column, without the zero-width spaces the bot inserts for wrapping;
- the owning teams are the collapsible team lists of the Owners column; the sub-teams a team lists inside its own list
  do not count;
- the approval is the label of the Approval column (`Approved`, `Changes requested`, `Review requested`, `Commented`
  or `No reviewer`), and the reviewers are the GitHub logins linked after it (`<organization>/<team>` for teams).

An unknown label, an unexpected table layout or a table without rules makes the comment unreadable, which shows the
red cross instead of a guessed status. A failed lookup, or comments beyond the third page without the bot's comment
among the pages read, keeps the review previously read for the same pull request; a new pull request of the branch
has no status until its review is read.

### Bot commands

"Dry Run" and "Safe Merge" run `gh pr comment <number> --repo JetBrains/kotlin --body /dry-run` (or `/safe-merge`)
in the background. The repository is named explicitly because the number is one of a `JetBrains/kotlin` pull
request, whatever remotes the checkout has, and `gh` refuses to guess between several remotes. `GH_PROMPT_DISABLED`
keeps `gh` from prompting and `GH_NO_UPDATE_NOTIFIER` keeps update notices out of its output. The comment is posted
as the account `gh` is signed in with, not as the GitHub account configured in the IDE. While a command is being
posted on a pull request, the same action is disabled for it, so a second click does not post it twice. The
approval that "Safe Merge" requires is the one read at the last refresh. Neither action asks for confirmation; the
bot's `/cancel-coordinator` command cancels the merge coordinator running for the branch.

### Following a build

- Launched builds (the 50 newest) are stored in the workspace file (`KotlinDevKitTeamCityBuilds`).
- Every refresh round also fetches the newest 5 aggregate builds of the 10 most recently checked out branches
  (current branch included; read from the HEAD reflog) and branches with unfinished remembered safe merges via guest REST
  (`buildType:Kotlin_KotlinDev_Aggregate`, `branch:<name>`; `master` queries the default branch; running and
  canceled chains are included) plus the
  aggregate build queue, since queued builds are not served by the builds endpoint. Fetched builds live in
  memory only and merge with the remembered launches (same build id ⇒ the remembered launch wins);
  "Remove from List" therefore applies to remembered launches only.
- Branches outside the recent ones are fetched on demand from the context menu and when a safe merge is launched.
  Every round replaces the builds of the branches it fetches and keeps on-demand builds of the others; branches
  with unfinished remembered safe merges keep being fetched until their coordinator or gate finishes.
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

### Running failed tests locally

The "Run … Locally" context-menu actions share the launcher of the `testGlobally` Run Anything command:

1. The failed tests to run are collected from the selection: a failed-test row contributes itself, a
   failed-test class row contributes the tests listed under it, and a build row contributes all its fetched
   failed tests; right-clicking inside a multi-selection keeps it. Their TeamCity names (package, class, method)
   are turned into fully qualified references (`FailedTests.kt` → `failedTestFqName`); if none can be built, a
   toast says so and nothing runs.
2. `findAndLaunchTestsByFqReferences` (`runAnything/TestGloballyRunAnythingProvider.kt`) looks each reference up
   in the open project with `JavaPsiFacade`, keeps methods annotated `@org.jetbrains.kotlin.test.TestMetadata` and
   maps them to Gradle test tasks with `--tests` filters (`gradle/computeGradleCommandLine.kt`); a single test runs
   its test task alone, several tests clean and re-run their tasks with `--continue`.
3. The command runs through `GradleExecuteTaskAction` in the project's base directory with the Run executor.

Since the lookup happens in the open project, only tests present in the checked out working tree can be re-run —
a failed build of another branch runs only the tests that branch shares with the checkout.

## Tests

Tests are in `test/org/jetbrains/kotlin/test/helper/teamcity`, `.../helper/git`, and `.../helper/github`:

```bash
./gradlew test --tests '*TeamCity*' --tests '*RemoteRun*' --tests '*FailedTest*' --tests '*GitHubPullRequest*' \
  --tests '*CodeOwners*' --tests '*SafeMergeBot*'
```
