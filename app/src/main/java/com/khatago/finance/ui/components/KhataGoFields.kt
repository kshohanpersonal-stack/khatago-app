package com.khatago.finance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.money.MoneyParseResult
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing

/**
 * Form fields shared by every add/edit screen.
 *
 * The rules these enforce are the reason money forms feel trustworthy:
 *  - **Amounts are parsed, never coerced.** Too many decimals is an error message, not a silent
 *    round; a negative amount is refused with an explanation of what the user probably meant.
 *  - **Labels sit above fields**, not as placeholders, so a half-typed value never hides the label
 *    that tells you which field it is — critical when two amounts ("Total" and "Paid") are adjacent.
 *  - **Dates are epoch days in the domain**, and the UTC-midnight conversion happens only inside
 *    the picker, in one place, because Material's `DatePickerState` is UTC-based while KhataGo
 *    counts local days. Getting that boundary wrong shifts every due date by a day for some users.
 */

/** Currency is threaded through the leaf fields so formatting stays a single decision. */
data class FieldContext(
    val currency: CurrencySpec,
    val todayEpochDay: Long,
)

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (required) "$text *" else text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

private val fieldShape = RoundedCornerShape(KhataGoRadii.field)

@Composable
fun TextFieldLine(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    maxLength: Int = 120,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Label(text = label, required = required)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) else onValueChange(it.take(maxLength)) },
            placeholder = placeholder?.let { { Text(it, color = KhataGoColors.Ink400) } },
            isError = error != null,
            singleLine = singleLine,
            shape = fieldShape,
            leadingIcon = leadingIcon,
            trailingIcon = if (maxLength in 1..200 && value.length > maxLength - 20) {
                {
                    Text(
                        text = "${value.length}/$maxLength",
                        style = MaterialTheme.typography.labelSmall,
                        color = KhataGoColors.Ink400,
                        modifier = Modifier.padding(end = KhataGoSpacing.md),
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = KhataGoColors.Ink200,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (helper != null) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The amount field: raw text in, parsed minor units out.
 *
 * It keeps the raw string itself (typing "50." must not fight the user) and reports a
 * [MoneyParseResult] upward so the *form* decides whether to disable Save. That is why there is no
 * `Double` anywhere in this signature: float money is how cent-level bugs are born.
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    context: FieldContext,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    helper: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onParsed: (MoneyParseResult) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Label(
            text = label,
            required = required,
            trailing = if (value.isNotBlank()) {
                {
                    val parsed = MoneyParseResult.parse(value, context.currency, allowZero = true)
                    if (parsed is MoneyParseResult.Success) {
                        Text(
                            text = MoneyFormat.format(parsed.amount.minor, context.currency),
                            style = MaterialTheme.typography.labelSmall,
                            color = KhataGoColors.Emerald700,
                        )
                    }
                }
            } else {
                null
            },
        )
        OutlinedTextField(
            value = value,
            onValueChange = { raw ->
                // Allow only what the parser can handle; this keeps the soft keyboard honest without
                // blocking the decimal separator the user's currency actually uses.
                val allowed = raw.filter { it.isDigit() || it == '.' || it == context.currency.decimalMark }
                onValueChange(allowed)
            },
            shape = fieldShape,
            prefix = { Text(context.currency.symbol, color = KhataGoColors.Ink500) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = imeAction,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = KhataGoColors.Ink200,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val error = MoneyParseResult.parse(value, context.currency, allowZero = false)
            .let { if (it is MoneyParseResult.Invalid) it.message else null }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (helper != null) {
            Text(text = helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    placeholder: String = "Choose",
    helper: String? = null,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Label(text = label, required = required, hint = helper)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.let(labelOf) ?: "",
                onValueChange = {},
                readOnly = true,
                placeholder = { Text(placeholder, color = KhataGoColors.Ink400) },
                isError = error != null,
                shape = fieldShape,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = KhataGoColors.Ink200,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(KhataGoRadii.innerCard),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        leadingIcon = {
                            if (option == selected) {
                                Icon(
                                    imageVector = KhataGoIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(20.dp),
                                )
                            } else {
                                Spacer(Modifier.width(20.dp))
                            }
                        },
                    )
                }
            }
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Date field + Material date picker. Converts between local epoch days (KhataGo's unit) and the
 * picker's UTC-midnight millis at exactly one boundary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    epochDay: Long?,
    onEpochDayChange: (Long?) -> Unit,
    label: String,
    context: FieldContext,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
    helper: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
        Label(
            text = label,
            hint = helper,
            trailing = if (allowClear && epochDay != null) {
                {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickableBox { onEpochDayChange(null) }
                            .padding(horizontal = KhataGoSpacing.sm, vertical = KhataGoSpacing.xs),
                    )
                }
            } else {
                null
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 14.dp, horizontal = KhataGoSpacing.lg),
        ) {
            Text(
                text = epochDay?.let { AppDates.formatMedium(it) } ?: "Pick a date",
                style = MaterialTheme.typography.bodyLarge,
                color = if (epochDay == null) KhataGoColors.Ink400 else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = epochDay?.let { AppDates.relativeDay(it, context.todayEpochDay) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (epochDay ?: context.todayEpochDay) * MILLIS_PER_DAY_UTC,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // UTC-midnight millis -> local epoch day. Integer division of a positive
                        // value is a floor, which is exactly the conversion AppDates uses.
                        onEpochDayChange(millis / MILLIS_PER_DAY_UTC)
                    }
                    open = false
                }) { Text("Use this date") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

private const val MILLIS_PER_DAY_UTC = 86_400_000L

@Composable
fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Note",
    modifier: Modifier = Modifier,
) {
    TextFieldLine(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        placeholder = "Optional — a detail you will want later",
        singleLine = false,
        maxLength = 500,
        imeAction = ImeAction.Done,
    )
}

@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(KhataGoRadii.card))
            .padding(KhataGoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = KhataGoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ChoiceTile(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) KhataGoColors.Emerald50 else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(KhataGoRadii.innerCard),
            )
            .clickable(onClick = onClick)
            .padding(KhataGoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else KhataGoColors.Ink500,
            )
            Spacer(Modifier.width(KhataGoSpacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = KhataGoIcons.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Focus-ring-free tap wrapper used by the read-only date box and choice tiles. */
private fun Modifier.clickableBox(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
