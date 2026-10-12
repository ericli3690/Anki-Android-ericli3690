// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.deckpicker

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import com.ichi2.anki.R
import com.ichi2.anki.StudyOptionsFragment
import com.ichi2.anki.StudyOptionsFragment.Companion.registerStudyOptionsAddEditReminderHandler
import com.ichi2.anki.StudyOptionsFragment.Companion.registerStudyOptionsStudyHandler
import com.ichi2.anki.StudyOptionsFragment.Companion.setStudyOptionsStudyResult
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.reviewreminders.ReviewReminderScope
import com.ichi2.anki.reviewreminders.ScheduleRemindersFragment
import timber.log.Timber

/**
 * The side pane of the [com.ichi2.anki.DeckPicker] on large screens.
 *
 * The pane has two layers:
 * - **base**: a [StudyOptionsFragment] for the selected deck. Added once and never removed, so its
 *   view and scroll position survive while it is covered.
 * - **overlay**: empty, or an [Overlay] covering the base, showing review reminders.
 *
 * @see Content
 */
class DeckPickerSideFragment : Fragment(R.layout.fragment_deck_picker_side) {
    /** What the pane is showing. Derived from the overlay layer, which the FragmentManager restores. */
    enum class Content {
        STUDY_OPTIONS,
        REMINDERS,
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val content: Content
        get() = if (overlayHost == null) Content.STUDY_OPTIONS else Content.REMINDERS

    val studyOptions: StudyOptionsFragment?
        get() = childFragmentManager.fragments.firstNotNullOfOrNull { it as? StudyOptionsFragment }

    private val overlayHost: Overlay?
        get() = childFragmentManager.fragments.firstNotNullOfOrNull { it as? Overlay }

    /** The screen displayed in the overlay, if any */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val overlay: Fragment?
        get() = overlayHost?.screen

    /**
     * The last selected deck this pane was told about. Saved, so a replay of the same deck after
     * recreation can be told apart from a real change.
     */
    private var selectedDeckId: DeckId? = null

    /**
     * Whether the overlay should be closed when the pane is next resumed.
     *
     * Once the activity has saved its state (for example, the user moved to another screen),
     * fragments cannot be changed until it returns: the change would be lost if Android
     * destroyed and restored the activity. A request to close the overlay in that window is
     * remembered here instead.
     */
    private var pendingCloseOverlay = false

    private val backCallback =
        object : OnBackPressedCallback(enabled = false) {
            override fun handleOnBackPressed() {
                val overlayFragmentManager = overlayHost?.childFragmentManager
                if (overlayFragmentManager != null && overlayFragmentManager.backStackEntryCount > 0) {
                    Timber.i("Side pane: back within the overlay")
                    overlayFragmentManager.popBackStack()
                } else {
                    Timber.i("Side pane: back to study options")
                    closeOverlay()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedDeckId = savedInstanceState?.getLong(STATE_SELECTED_DECK_ID)?.takeIf { it != 0L }

        childFragmentManager.registerStudyOptionsStudyHandler(this) {
            // the DeckPicker opens the study screen
            parentFragmentManager.setStudyOptionsStudyResult()
        }
        childFragmentManager.registerStudyOptionsAddEditReminderHandler(this) { deckId ->
            showReminders(deckId)
        }

        if (studyOptions == null) {
            childFragmentManager.commit {
                add(R.id.side_pane_content, StudyOptionsFragment())
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // Registered after the activity's own callbacks, so the pane handles Back first
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        updateBackCallback()
    }

    override fun onResume() {
        super.onResume()
        if (pendingCloseOverlay) {
            pendingCloseOverlay = false
            closeOverlay()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedDeckId?.let { outState.putLong(STATE_SELECTED_DECK_ID, it) }
    }

    /** Explicitly navigates to the study options of the selected deck, closing the overlay. */
    fun showStudyOptions() {
        closeOverlay()
        refresh()
    }

    /**
     * Reloads the study options from the collection: the selected deck's name, description,
     * card counts and menu items. Call after the collection or the selected deck may have changed.
     *
     * Does not change what the pane displays.
     */
    fun refresh() {
        studyOptions?.refreshInterface()
    }

    /** Covers the study options with the review reminders of [deckId]. */
    fun showReminders(deckId: DeckId) {
        Timber.i("Side pane: showing review reminders for deck %d", deckId)
        childFragmentManager.commitNow {
            overlayHost?.let { remove(it) }
            studyOptions?.let {
                hide(it)
                // below RESUMED: removes its menu items from the toolbar
                setMaxLifecycle(it, Lifecycle.State.STARTED)
            }
            add(R.id.side_pane_content, Overlay.newInstance(deckId))
        }
        updateBackCallback()
    }

    /**
     * Informs the pane of the selected deck. If the deck changed, the overlay is closed.
     * The same deck (for example, replayed after recreation) is ignored.
     */
    fun onSelectedDeckChanged(deckId: DeckId) {
        if (deckId == selectedDeckId) return
        Timber.d("Side pane: selected deck changed to %d", deckId)
        selectedDeckId = deckId
        closeOverlay()
        refresh()
    }

    fun onResizingChanged(isResizing: Boolean) {
        studyOptions?.onResizingChanged(isResizing)
    }

    /** Removes the overlay, and everything in it, uncovering the study options. */
    private fun closeOverlay() {
        val overlayHost = overlayHost ?: return
        if (childFragmentManager.isStateSaved) {
            Timber.d("Side pane: deferring closing the overlay")
            pendingCloseOverlay = true
            return
        }
        childFragmentManager.commitNow {
            remove(overlayHost)
            studyOptions?.let {
                show(it)
                setMaxLifecycle(it, Lifecycle.State.RESUMED)
            }
        }
        updateBackCallback()
    }

    /** Back is handled by the pane only while the overlay is displayed */
    private fun updateBackCallback() {
        backCallback.isEnabled = overlayHost != null
    }

    /**
     * The overlay layer: review reminders for a deck, and the screens it drills down to on this
     * fragment's back stack. Removing the overlay discards all of them at once, without
     * revisiting earlier screens.
     */
    class Overlay : Fragment(R.layout.fragment_deck_picker_side_overlay) {
        val screen: Fragment?
            get() = childFragmentManager.findFragmentById(R.id.side_pane_overlay_content)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (screen != null) return
            val deckId = requireArguments().getLong(ARG_DECK_ID)
            childFragmentManager.commit {
                add(
                    R.id.side_pane_overlay_content,
                    ScheduleRemindersFragment.newInstance(
                        scope = ReviewReminderScope.DeckSpecific(deckId),
                        host = ScheduleRemindersFragment.FragmentHost.DECK_PICKER_SIDE_PANE,
                    ),
                )
            }
        }

        companion object {
            private const val ARG_DECK_ID = "deckId"

            fun newInstance(deckId: DeckId) = Overlay().apply { arguments = bundleOf(ARG_DECK_ID to deckId) }
        }
    }

    companion object {
        private const val STATE_SELECTED_DECK_ID = "selectedDeckId"
    }
}
