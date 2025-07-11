/*
 *  Copyright (c) 2024 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.previewer

import android.os.Parcelable
import androidx.annotation.CheckResult
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NotetypeFile
import com.ichi2.anki.asyncIO
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.launchCatchingIO
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Consts.DEFAULT_DECK_ID
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.pages.AnkiServer
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.parcelize.Parcelize
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.VisibleForTesting

class TemplatePreviewerViewModel(
    arguments: TemplatePreviewerArguments,
    cardMediaPlayer: CardMediaPlayer,
) : CardViewerViewModel(cardMediaPlayer) {
    private val notetype = arguments.notetype
    private val fillEmpty = arguments.fillEmpty
    private val isCloze = notetype.isCloze

    /**
     * identifies which of the card templates or cloze deletions it corresponds to
     * * for card templates, values are from 0 to the number of templates minus 1
     * * for cloze deletions, values are from 0 to max cloze index minus 1
     */
    @VisibleForTesting
    val ordFlow = MutableStateFlow(arguments.ord)

    private val note: Deferred<Note>
    private val templateNames: Deferred<List<String>>
    private val clozeOrds: Deferred<List<Int>>?
    override var currentCard: Deferred<Card>
    override val server = AnkiServer(this).also { it.start() }

    /**
     * Ordered list of cards with empty fronts
     */
    internal val cardsWithEmptyFronts: Deferred<List<Boolean>>?

    init {
        note =
            asyncIO {
                withCol {
                    if (arguments.id != 0L) {
                        Note(this, arguments.id)
                    } else {
                        Note.fromNotetypeId(this@withCol, arguments.notetype.id)
                    }
                }.apply {
                    fields = arguments.fields
                    tags = arguments.tags
                }
            }
        currentCard =
            asyncIO {
                val note = note.await()
                withCol {
                    note.ephemeralCard(
                        col = this,
                        ord = ordFlow.value,
                        customNoteType = notetype,
                        fillEmpty = fillEmpty,
                        deckId = arguments.deckId,
                    )
                }
            }
        if (isCloze) {
            val clozeNumbers =
                asyncIO {
                    val note = note.await()
                    withCol { clozeNumbersInNote(note) }
                }
            clozeOrds =
                asyncIO {
                    clozeNumbers.await().map { it - 1 }
                }
            templateNames =
                asyncIO {
                    val tr = CollectionManager.TR
                    clozeNumbers.await().map { tr.cardTemplatesCard(it) }
                }
            cardsWithEmptyFronts = null
        } else {
            clozeOrds = null
            templateNames = CompletableDeferred(notetype.templatesNames)
            cardsWithEmptyFronts =
                asyncIO {
                    val note = note.await()
                    List(templateNames.await().size) { ord ->
                        val questionText =
                            withCol {
                                note
                                    .ephemeralCard(
                                        col = this,
                                        ord = ord,
                                        customNoteType = notetype,
                                        fillEmpty = fillEmpty,
                                        deckId = arguments.deckId,
                                    ).renderOutput(this)
                            }.questionText
                        EMPTY_FRONT_LINK in questionText
                    }
                }
        }
    }

    /* *********************************************************************************************
     ************************ Public methods: meant to be used by the View **************************
     ********************************************************************************************* */

    override fun onPageFinished(isAfterRecreation: Boolean) {
        if (isAfterRecreation) {
            launchCatchingIO {
                // TODO: We should persist showingAnswer to SavedStateHandle
                if (showingAnswer.value) showAnswer() else showQuestion()
            }
            return
        }
        launchCatchingIO {
            ordFlow.collectLatest { ord ->
                currentCard =
                    asyncIO {
                        val note = note.await()
                        withCol {
                            note.ephemeralCard(
                                col = this,
                                ord = ord,
                                customNoteType = notetype,
                                fillEmpty = fillEmpty,
                            )
                        }
                    }
                showQuestion()
                loadAndPlaySounds(CardSide.QUESTION)
            }
        }
    }

    fun toggleShowAnswer() {
        launchCatchingIO {
            if (showingAnswer.value) {
                showQuestion()
                loadAndPlaySounds(CardSide.QUESTION)
            } else {
                showAnswer()
                loadAndPlaySounds(CardSide.ANSWER)
            }
        }
    }

    @CheckResult
    suspend fun getTemplateNames(): List<String> = templateNames.await()

    fun onTabSelected(position: Int) {
        launchCatchingIO {
            val ord =
                if (isCloze) {
                    clozeOrds!!.await()[position]
                } else {
                    position
                }
            ordFlow.emit(ord)
        }
    }

    @CheckResult
    suspend fun getCurrentTabIndex(): Int =
        if (isCloze) {
            clozeOrds!!.await().indexOf(ordFlow.value)
        } else {
            ordFlow.value
        }

    /* *********************************************************************************************
     *************************************** Internal methods ***************************************
     ********************************************************************************************* */

    private suspend fun loadAndPlaySounds(side: CardSide) {
        cardMediaPlayer.loadCardAvTags(currentCard.await())
        cardMediaPlayer.autoplayAllForSide(side)
    }

    // https://github.com/ankitects/anki/blob/df70564079f53e587dc44f015c503fdf6a70924f/qt/aqt/clayout.py#L579
    override suspend fun typeAnsFilter(text: String): String =
        if (showingAnswer.value) {
            val typeAnswer = TypeAnswer.getInstance(currentCard.await(), text)
            if (typeAnswer?.expectedAnswer?.isEmpty() == true) {
                typeAnswer.expectedAnswer = "sample"
            }
            typeAnswer?.answerFilter(typedAnswer = "example") ?: text
        } else {
            val repl = "<center><input id='typeans' type=text value='example' readonly='readonly'></center>"
            val warning = "<center><b>${CollectionManager.TR.cardTemplatesTypeBoxesWarning()}</b></center>"
            StringBuilder(text).replaceFirst(typeAnsRe, repl).replace(typeAnsRe, warning)
        }

    companion object {
        @Language("HTML")
        private const val EMPTY_FRONT_LINK = """<a href='https://docs.ankiweb.net/templates/errors.html#front-of-card-is-blank'>"""

        fun factory(
            arguments: TemplatePreviewerArguments,
            cardMediaPlayer: CardMediaPlayer,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TemplatePreviewerViewModel(arguments, cardMediaPlayer)
                }
            }
    }
}

/**
 * @param id id of the note. Use 0 for non-created notes.
 *
 * @param ord identifies which of the card templates or cloze deletions it corresponds to
 * * for card templates, values are from 0 to the number of templates minus 1
 * * for cloze deletions, values are from 0 to max cloze index minus 1
 *
 * @param fillEmpty if blank fields should be replaced with placeholder content
 */
@Parcelize
data class TemplatePreviewerArguments(
    private val notetypeFile: NotetypeFile,
    val fields: MutableList<String>,
    val tags: MutableList<String>,
    val id: NoteId = 0,
    val ord: Int = 0,
    val fillEmpty: Boolean = false,
    val deckId: DeckId = DEFAULT_DECK_ID,
) : Parcelable {
    val notetype: NotetypeJson get() = notetypeFile.getNotetype()
}
