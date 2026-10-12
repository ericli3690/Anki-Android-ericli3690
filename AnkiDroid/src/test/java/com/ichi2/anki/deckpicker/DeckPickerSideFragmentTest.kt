// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.deckpicker

import android.content.Intent
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.commit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.InitialActivity
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.StudyOptionsFragment
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.deckpicker.DeckPickerSideFragment.Content
import com.ichi2.anki.dialogs.SyncErrorDialog
import com.ichi2.anki.reviewreminders.AddEditReminderDialog
import com.ichi2.anki.reviewreminders.ReminderTroubleshootingFragment
import com.ichi2.anki.reviewreminders.ReviewReminderScope
import com.ichi2.anki.reviewreminders.ScheduleRemindersFragment
import com.ichi2.anki.reviewreminders.ScheduleRemindersFragment.FragmentHost
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.utils.ext.DIALOG_FRAGMENT_TAG
import com.ichi2.anki.utils.showDialogFragment
import com.ichi2.testutils.BackupManagerTestUtilities
import com.ichi2.testutils.ext.menu
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.sameInstance
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Tests for [DeckPickerSideFragment]: the tablet side pane of the [DeckPicker].
 */
@RunWith(AndroidJUnit4::class)
class DeckPickerSideFragmentTest : RobolectricTest() {
    @Before
    fun setUpTablet() {
        RuntimeEnvironment.setQualifiers("xlarge-port")
        setIntroductionSlidesShown(true)
        // avoid unrelated startup dialogs
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        InitialActivity.setUpgradedToLatestVersion(targetContext.sharedPrefs())
        Prefs.newReviewRemindersEnabled = true
        addBasicNote()
    }

    @After
    fun resetBackupSpace() = BackupManagerTestUtilities.reset()

    @Test
    fun `reminders and an open reminder dialog survive recreation`() =
        runTest {
            val deckId = col.decks.selected()
            val controller = startDeckPicker()
            controller.get().sidePane().showReminders(deckId)
            advanceRobolectricLooper()
            controller.get().reminders().childFragmentManager.showDialogFragment(
                AddEditReminderDialog.getInstance(AddEditReminderDialog.DialogMode.Add(ReviewReminderScope.DeckSpecific(deckId))),
            )
            advanceRobolectricLooper()

            controller.recreateWithQualifiers("+land")

            val deckPicker = controller.get()
            val sidePane = deckPicker.sidePane()
            assertThat(sidePane.content, equalTo(Content.REMINDERS))
            assertThat(deckPicker.reminders().toolbarSubtitle, equalTo(col.decks.name(deckId)))
            assertThat(
                deckPicker.reminders().childFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG),
                instanceOf(AddEditReminderDialog::class.java),
            )
            assertThat(
                "a single study options",
                sidePane.childFragmentManager.fragments.count { it is StudyOptionsFragment },
                equalTo(1),
            )
            assertThat("study options is covered", requireNotNull(sidePane.studyOptions).isHidden, equalTo(true))
            assertThat(deckPicker.supportFragmentManager.backStackEntryCount, equalTo(0))
        }

    @Test
    fun `troubleshooting survives recreation, then back returns to reminders and study options`() =
        runTest {
            val deckId = col.decks.selected()
            val controller = startDeckPicker()
            controller.get().sidePane().apply {
                showReminders(deckId)
                showTroubleshooting()
            }
            advanceRobolectricLooper()

            controller.recreateWithQualifiers("+land")
            val deckPicker = controller.get()
            val sidePane = deckPicker.sidePane()
            assertThat(sidePane.overlay, instanceOf(ReminderTroubleshootingFragment::class.java))

            deckPicker.onBackPressedDispatcher.onBackPressed()
            advanceRobolectricLooper()
            assertThat(sidePane.overlay, instanceOf(ScheduleRemindersFragment::class.java))
            assertThat(deckPicker.reminders().toolbarSubtitle, equalTo(col.decks.name(deckId)))

            deckPicker.onBackPressedDispatcher.onBackPressed()
            advanceRobolectricLooper()
            assertThat(sidePane.content, equalTo(Content.STUDY_OPTIONS))
            assertThat("study options is uncovered", requireNotNull(sidePane.studyOptions).isHidden, equalTo(false))
            assertThat("back must not leave the deck picker", deckPicker.isFinishing, equalTo(false))
        }

    @Test
    fun `shrinking with reminders open leaves no pane history, expanding shows study options`() =
        runTest {
            val controller = startDeckPicker()
            controller.get().sidePane().showReminders(col.decks.selected())
            advanceRobolectricLooper()

            controller.recreateWithQualifiers("sw320dp")
            controller.get().let { deckPicker ->
                assertThat(deckPicker.fragmented, equalTo(false))
                assertThat(deckPicker.sidePane, nullValue())
                assertThat(deckPicker.supportFragmentManager.backStackEntryCount, equalTo(0))
                deckPicker.supportFragmentManager.popBackStackImmediate()
                assertThat(
                    "no pane content is resurrected",
                    deckPicker.supportFragmentManager.fragments.none {
                        it is DeckPickerSideFragment || it is StudyOptionsFragment || it is ScheduleRemindersFragment
                    },
                    equalTo(true),
                )
            }

            controller.recreateWithQualifiers("xlarge-port")
            assertThat(controller.get().sidePane().content, equalTo(Content.STUDY_OPTIONS))
            assertThat(controller.get().fragment, notNullValue())
        }

    @Test
    fun `side pane operations keep dialogs and the activity back stack`() =
        runTest {
            val anotherDeck = addDeck("Another deck")
            val deckPicker = startDeckPicker().get()
            deckPicker.showSyncErrorDialog(SyncErrorDialog.Type.DIALOG_SYNC_CONFLICT_RESOLUTION)
            advanceRobolectricLooper()
            val dialog = deckPicker.syncConflictDialog()
            val backStackEntryCount = deckPicker.supportFragmentManager.backStackEntryCount

            deckPicker.sidePane().apply {
                showReminders(col.decks.selected())
                showTroubleshooting()
                onSelectedDeckChanged(anotherDeck)
                showReminders(anotherDeck)
                showStudyOptions()
                refresh()
            }
            advanceRobolectricLooper()

            assertThat(dialog.isShowing, equalTo(true))
            assertThat(deckPicker.supportFragmentManager.backStackEntryCount, equalTo(backStackEntryCount))
        }

    @Test
    fun `study button in the side pane opens the study screen`() =
        runTest {
            val deckPicker = startDeckPicker().get()
            val studyOptions = requireNotNull(deckPicker.fragment)

            studyOptions.requireView().findViewById<Button>(R.id.studyoptions_start).performClick()
            advanceRobolectricLooper()

            assertThat("study screen is started", shadowOf(deckPicker).nextStartedActivity, notNullValue())
        }

    @Test
    fun `study options menu items are hidden while reminders shows`() =
        runTest {
            val deckPicker = startDeckPicker().get()
            assertThat(deckPicker.menu().findItem(R.id.action_custom_study), notNullValue())

            deckPicker.sidePane().showReminders(col.decks.selected())
            advanceRobolectricLooper()
            assertThat(deckPicker.menu().findItem(R.id.action_custom_study), nullValue())

            deckPicker.sidePane().showStudyOptions()
            advanceRobolectricLooper()
            assertThat(deckPicker.menu().findItem(R.id.action_custom_study), notNullValue())
        }

    private fun startDeckPicker(): ActivityController<DeckPicker> =
        startActivityControllerNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent()).also {
            advanceRobolectricLooper()
        }

    /** Recreates the activity with [qualifiers], then waits for startup and deck state to settle */
    private suspend fun ActivityController<DeckPicker>.recreateWithQualifiers(qualifiers: String) {
        val previous = get()
        RuntimeEnvironment.setQualifiers(qualifiers)
        recreate()
        advanceRobolectricLooper()
        assertThat("activity was recreated", get(), not(sameInstance(previous)))
        get().viewModel.reloadDeckCounts().join()
        advanceRobolectricLooper()
    }

    private fun DeckPicker.sidePane() = requireNotNull(sidePane) { "sidePane" }

    private fun DeckPicker.reminders() = sidePane().overlay as ScheduleRemindersFragment

    private val ScheduleRemindersFragment.toolbarSubtitle
        get() = requireView().findViewById<Toolbar>(R.id.non_collapsible_toolbar).subtitle?.toString()

    /** Opens troubleshooting as [ScheduleRemindersFragment] does */
    private fun DeckPickerSideFragment.showTroubleshooting() {
        val overlayFragmentManager = requireNotNull(overlay) { "overlay" }.parentFragmentManager
        overlayFragmentManager.commit {
            replace(R.id.side_pane_overlay_content, ReminderTroubleshootingFragment.newInstance(FragmentHost.DECK_PICKER_SIDE_PANE))
            addToBackStack(null)
        }
        overlayFragmentManager.executePendingTransactions()
    }

    private fun DeckPicker.syncConflictDialog(): AlertDialog {
        val fragment = supportFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) as SyncErrorDialog
        return fragment.dialog as AlertDialog
    }
}
