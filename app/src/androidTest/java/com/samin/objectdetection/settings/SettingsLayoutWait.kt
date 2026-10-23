package com.samin.objectdetection.settings

import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue

internal fun openSettingsCategory(title: String) {
    val card = onView(withContentDescription(org.hamcrest.CoreMatchers.startsWith("$title.")))
    card.perform(scrollTo())
    // With device animations enabled, the first tap otherwise only stops ScrollView's motion.
    SystemClock.sleep(500)
    card.perform(click())
}

/** A newly shown settings category can still be awaiting its first layout when Espresso is idle. */
internal fun awaitSettingsViewLayout(matcher: Matcher<View>) {
    val deadline = SystemClock.elapsedRealtime() + 5000
    var lastFailure: AssertionError? = null
    while (SystemClock.elapsedRealtime() < deadline) {
        try {
            onView(matcher).check { view, error ->
                if (error != null) throw error
                val ancestors = generateSequence(view as View?) { it.parent as? View }
                    .joinToString(" > ") { "${it.javaClass.simpleName}(visibility=${it.visibility},${it.width}x${it.height},pending=${it.isLayoutRequested})" }
                assertTrue("Settings control must be shown and laid out before scrolling: $ancestors",
                    view.isShown && view.width > 0 && view.height > 0 && !view.isLayoutRequested)
            }
            return
        } catch (error: AssertionError) {
            lastFailure = error
        }
        SystemClock.sleep(25)
    }
    throw AssertionError("Settings control did not finish layout", lastFailure)
}
