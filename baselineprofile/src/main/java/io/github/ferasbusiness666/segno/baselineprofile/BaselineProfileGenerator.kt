package io.github.ferasbusiness666.segno.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code Segno runs on its main paths: a cold start, a search with its results scrolled,
 * Library Songs scrolled, a song played, Now Playing and the queue opened, and the tabs switched.
 * The phone needs some music files (CI copies a few hundred short ones first) and the internet.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PKG, maxIterations = 6, stableIterations = 2) {
        device.executeShellCommand("pm grant $PKG android.permission.READ_MEDIA_AUDIO")
        device.executeShellCommand("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()
        search()
        library()
        tap("tabHome")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.search() {
        tap("tabSearch")
        val box = device.wait(Until.findObject(By.res(PKG, "query")), 5_000) ?: return
        box.click()
        box.text = "daft punk"
        device.pressEnter()
        if (device.wait(Until.hasObject(By.res(PKG, "title")), 30_000) == true) scroll(device, "results")
    }

    private fun MacrobenchmarkScope.library() {
        tap("tabLibrary")
        tap("chipSongs")
        if (device.wait(Until.hasObject(By.res(PKG, "title")), 10_000) != true) return
        scroll(device, "list")
        device.findObject(By.res(PKG, "title"))?.click() ?: return
        val mini = device.wait(Until.findObject(By.res(PKG, "miniCard")), 10_000) ?: return
        mini.click()
        val queue = device.wait(Until.findObject(By.res(PKG, "npQueue")), 5_000)
        if (queue != null) {
            queue.click()
            device.wait(Until.hasObject(By.res(PKG, "queueList")), 3_000)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.tap(id: String) {
        device.wait(Until.findObject(By.res(PKG, id)), 5_000)?.click()
        device.waitForIdle()
    }

    private fun scroll(device: UiDevice, id: String) {
        val list = device.findObject(By.res(PKG, id)) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    private companion object {
        const val PKG = "io.github.ferasbusiness666.segno"
    }
}
