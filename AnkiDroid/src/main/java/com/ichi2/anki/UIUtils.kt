//noinspection MissingCopyrightHeader #8659

package com.ichi2.anki

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.ichi2.anki.common.time.Time
import java.util.Calendar

fun showThemedToast(
    context: Context,
    text: String,
    shortLength: Boolean,
) {
    Toast.makeText(context, text, if (shortLength) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}

fun showThemedToast(
    context: Context,
    text: CharSequence,
    shortLength: Boolean,
) {
    showThemedToast(context, text.toString(), shortLength)
}

fun showThemedToast(
    context: Context,
    @StringRes textResource: Int,
    shortLength: Boolean,
) {
    Toast.makeText(context, textResource, if (shortLength) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}

fun getDensityAdjustedValue(
    context: Context,
    value: Float,
): Float = context.resources.displayMetrics.density * value

fun getDayStart(time: Time): Long {
    val cal = time.calendar()
    if (cal[Calendar.HOUR_OF_DAY] < 4) {
        cal.roll(Calendar.DAY_OF_YEAR, -1)
    }
    cal[Calendar.HOUR_OF_DAY] = 4
    cal[Calendar.MINUTE] = 0
    cal[Calendar.SECOND] = 0
    cal[Calendar.MILLISECOND] = 0
    return cal.timeInMillis
}
