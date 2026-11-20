package org.jetbrains.kotlin.test.helper.github

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `gh` is faked in every test, so no comment is ever posted.
 */
class SafeMergeBotCommandServiceTest : BasePlatformTestCase() {
    private var serviceScope: CoroutineScope? = null

    override fun tearDown() {
        try {
            serviceScope?.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testNotifiesThePostedCommandWithALinkToGitHub() {
        val notifications = collectNotifications()
        val service = service { ProcessOutput("$PULL_REQUEST_URL#issuecomment-1\n", "", 0, false, false) }

        val job = service.post(OPEN_PULL_REQUEST, SafeMergeBotCommand.SAFE_MERGE)
        waitFor("the comment to be posted") { job.isFinished() && notifications.isNotEmpty() }

        val notification = notifications.single()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals("Kotlin pull request commented", notification.title)
        assertEquals("Commented /safe-merge on pull request #42.", notification.content)
        assertEquals(listOf("Open on GitHub"), notification.actions.map { action -> action.templateText })
    }

    fun testNotifiesWhatGhPrintedWhenTheCommandWasNotPosted() {
        val notifications = collectNotifications()
        val service = service { ProcessOutput("", "unknown flag: --body <x>\n", 1, false, false) }

        val job = service.post(OPEN_PULL_REQUEST, SafeMergeBotCommand.SAFE_MERGE)
        waitFor("the failure to be reported") { job.isFinished() && notifications.isNotEmpty() }

        val notification = notifications.single()
        assertEquals(NotificationType.ERROR, notification.type)
        assertEquals("Kotlin pull request not commented", notification.title)
        assertEquals(
            "Could not comment /safe-merge on pull request #42: unknown flag: --body &lt;x&gt;",
            notification.content,
        )
        assertFalse(service.isPosting(OPEN_PULL_REQUEST, SafeMergeBotCommand.SAFE_MERGE))
    }

    /**
     * Runs a fake `gh` script through the real process machinery, which records what it was given.
     */
    fun testRunsGhWithTheCommandAsItsOwnArgumentAndWithoutPrompts() {
        if (SystemInfo.isWindows) return

        val directory = FileUtil.createTempDirectory("fake-gh", null, true)
        val fakeGh = directory.resolve("gh").apply {
            writeText(
                """
                #!/bin/sh
                printf '%s\n' "$@" > "$(dirname "$0")/arguments.txt"
                printenv GH_PROMPT_DISABLED > "$(dirname "$0")/environment.txt"
                echo "$PULL_REQUEST_URL#issuecomment-7"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        val notifications = collectNotifications()
        val service = serviceRunning(fakeGh)

        val job = service.post(OPEN_PULL_REQUEST, SafeMergeBotCommand.SAFE_MERGE)
        waitFor("the comment to be posted") { job.isFinished() && notifications.isNotEmpty() }

        assertEquals(
            listOf("pr", "comment", "42", "--repo", "JetBrains/kotlin", "--body", "/safe-merge"),
            directory.resolve("arguments.txt").readLines(),
        )
        assertEquals(listOf("1"), directory.resolve("environment.txt").readLines())
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
    }

    /**
     * Fakes the run of `gh`.
     */
    private fun service(runGh: suspend (GeneralCommandLine) -> ProcessOutput): SafeMergeBotCommandService =
        newService().apply {
            findGh = { File("/usr/bin/gh") }
            this.runGh = runGh
        }

    /**
     * Runs [fakeGh] through the real process machinery; it must never be the real `gh`.
     */
    private fun serviceRunning(fakeGh: File): SafeMergeBotCommandService =
        newService().apply { findGh = { fakeGh } }

    private fun newService(): SafeMergeBotCommandService {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope

        return SafeMergeBotCommandService(project, scope)
    }

    private fun collectNotifications(): List<Notification> {
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            },
        )
        return notifications
    }

    /**
     * The posting runs on a background thread, while the test runs on the EDT, which must keep
     * dispatching events for the notifications to arrive.
     */
    private fun waitFor(what: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for $what", { condition() }, 10)
    }

    private fun Job?.isFinished(): Boolean = this != null && isCompleted

    private companion object {
        const val PULL_REQUEST_URL = "https://github.com/JetBrains/kotlin/pull/42"

        val OPEN_PULL_REQUEST = OpenKotlinPullRequest(
            url = PULL_REQUEST_URL,
            headSha = "0123456789abcdef0123456789abcdef01234567",
            number = 42,
        )
    }
}
