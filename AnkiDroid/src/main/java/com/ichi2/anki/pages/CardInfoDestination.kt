/*
 *  Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>
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
package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.utils.Destination

data class CardInfoDestination(
    val cardId: CardId,
) : Destination {
    override fun toIntent(context: Context): Intent {
        val title = context.getString(R.string.card_info_title)
        return PageFragment.getIntent(context, "card-info/$cardId", title)
    }
}
