package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import android.util.Log
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.presentation.theme.AppSpacing
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Time interval picker with separate inputs for days, hours, and minutes
 */
@Composable
fun TimeIntervalPicker(
    title: String,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minimumMinutes: Int = Constants.MIN_INTERVAL_MINUTES
) {
    // Decompose incoming minutes value into days, hours, minutes
    val initialDays = minutes / Constants.MINUTES_PER_DAY
    val remainingAfterDays = minutes % Constants.MINUTES_PER_DAY
    val initialHours = remainingAfterDays / Constants.MINUTES_PER_HOUR
    val initialMins = remainingAfterDays % Constants.MINUTES_PER_HOUR

    // State for user input
    var dayValue by remember(minutes) { mutableIntStateOf(initialDays) }
    var hourValue by remember(minutes) { mutableIntStateOf(initialHours) }
    var minuteValue by remember(minutes) { mutableIntStateOf(initialMins) }

    var dayInput by remember(minutes) { mutableStateOf(initialDays.toString()) }
    var hourInput by remember(minutes) { mutableStateOf(initialHours.toString()) }
    var minuteInput by remember(minutes) { mutableStateOf(initialMins.toString()) }

    // Debounce state - tracks when user last made a change; reset when external `minutes` prop changes
    var lastChangeTimestamp by remember(minutes) { mutableLongStateOf(0L) }

    // Debounced update - only fires after user stops typing
    LaunchedEffect(dayValue, hourValue, minuteValue, lastChangeTimestamp) {
        if (lastChangeTimestamp > 0) {
            delay(Constants.DEBOUNCE_DELAY_MS)
            val total = (dayValue * Constants.MINUTES_PER_DAY) + (hourValue * Constants.MINUTES_PER_HOUR) + minuteValue
            // Clamp between minimum and maximum interval
            val clamped = min(max(total, minimumMinutes), Constants.MAX_INTERVAL_MINUTES)
            onMinutesChange(clamped)
        }
    }

    fun notifyChange() {
        lastChangeTimestamp = System.currentTimeMillis()
    }

    val focusManager = LocalFocusManager.current
    var dayFocused by remember { mutableStateOf(false) }
    var hourFocused by remember { mutableStateOf(false) }
    var minuteFocused by remember { mutableStateOf(false) }
    val editing = dayFocused || hourFocused || minuteFocused || lastChangeTimestamp > 0

    /**
     * Applies the typed interval right away (skipping the debounce), shows the value that was
     * actually saved (e.g. 1 min is raised to the 15 min minimum) and closes the keyboard.
     */
    fun confirm() {
        val total = (dayValue * Constants.MINUTES_PER_DAY) + (hourValue * Constants.MINUTES_PER_HOUR) + minuteValue
        val clamped = min(max(total, minimumMinutes), Constants.MAX_INTERVAL_MINUTES)
        Log.d(TAG, "confirm: typed=${total}min -> saved=${clamped}min (current=${minutes}min)")

        lastChangeTimestamp = 0L // cancels the pending debounced update

        val d = clamped / Constants.MINUTES_PER_DAY
        val h = (clamped % Constants.MINUTES_PER_DAY) / Constants.MINUTES_PER_HOUR
        val m = (clamped % Constants.MINUTES_PER_DAY) % Constants.MINUTES_PER_HOUR
        dayValue = d; hourValue = h; minuteValue = m
        dayInput = d.toString(); hourInput = h.toString(); minuteInput = m.toString()

        focusManager.clearFocus()
        if (clamped != minutes) onMinutesChange(clamped)
    }

    val doneOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
    val doneActions = KeyboardActions(onDone = { confirm() })

    androidx.compose.material3.Card(
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall))
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W500,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Days input
                OutlinedTextField(
                    value = dayInput,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || (newValue.all { it.isDigit() } && newValue.length <= Constants.MAX_DAYS_INPUT_LENGTH)) {
                            dayInput = newValue
                            dayValue = newValue.toIntOrNull() ?: 0
                            notifyChange()
                        }
                    },
                    label = { Text(stringResource(R.string.days_txt)) },
                    keyboardOptions = doneOptions,
                    keyboardActions = doneActions,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { dayFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                // Hours input
                OutlinedTextField(
                    value = hourInput,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || (newValue.all { it.isDigit() } && newValue.length <= Constants.MAX_HOURS_MINUTES_INPUT_LENGTH)) {
                            hourInput = newValue
                            hourValue = newValue.toIntOrNull() ?: 0
                            notifyChange()
                        }
                    },
                    label = { Text(stringResource(R.string.hours_txt)) },
                    keyboardOptions = doneOptions,
                    keyboardActions = doneActions,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { hourFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                // Minutes input
                OutlinedTextField(
                    value = minuteInput,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || (newValue.all { it.isDigit() } && newValue.length <= Constants.MAX_HOURS_MINUTES_INPUT_LENGTH)) {
                            minuteInput = newValue
                            minuteValue = newValue.toIntOrNull() ?: 0
                            notifyChange()
                        }
                    },
                    label = { Text(stringResource(R.string.mins)) },
                    keyboardOptions = doneOptions,
                    keyboardActions = doneActions,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { minuteFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${stringResource(R.string.total_interval)} ${formatIntervalComposable(minutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { confirm() },
                    enabled = editing
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private const val TAG = "TimeIntervalPicker"

@Composable
private fun formatIntervalComposable(minutes: Int): String {
    val minUnit = stringResource(R.string.time_unit_min)

    return when {
        minutes < Constants.MINUTES_PER_HOUR -> "$minutes $minUnit"
        minutes < Constants.MINUTES_PER_DAY -> {
            val h = minutes / Constants.MINUTES_PER_HOUR
            val remainingMins = minutes % Constants.MINUTES_PER_HOUR
            val hUnit = pluralStringResource(R.plurals.time_unit_hours, h)
            if (remainingMins == 0) "$h $hUnit"
            else "$h $hUnit $remainingMins $minUnit"
        }
        else -> {
            val d = minutes / Constants.MINUTES_PER_DAY
            val remainingMinutes = minutes % Constants.MINUTES_PER_DAY
            val remainingHours = remainingMinutes / Constants.MINUTES_PER_HOUR
            val finalMins = remainingMinutes % Constants.MINUTES_PER_HOUR
            val dUnit = pluralStringResource(R.plurals.time_unit_days, d)
            buildString {
                append("$d $dUnit")
                if (remainingHours > 0) {
                    val hUnit = pluralStringResource(R.plurals.time_unit_hours, remainingHours)
                    append(" $remainingHours $hUnit")
                }
                if (finalMins > 0) append(" $finalMins $minUnit")
            }
        }
    }
}
