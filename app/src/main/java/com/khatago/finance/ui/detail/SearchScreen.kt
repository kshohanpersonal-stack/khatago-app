package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.ui.SearchViewModel
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.components.khataGoViewModel
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * Global search across every record type.
 *
 * Search is the answer to "I know there was *something* about Karim and a motorbike" — a ledger grows
 * past the point where scrolling finds anything, and a user who cannot find an old entry starts
 * doubting whether they recorded it at all. Two properties are non-negotiable here:
 *  - it works **offline and instantly**, because the index is the same SQLite data, no network;
 *  - it returns *grouped* results by module, so a shop called "Rahman Store" and a credit item
 *    "Rahman" are not mixed into one ambiguous list.
 *
 * The repository debounces (180 ms) and requires two characters: a single-letter query on every
 * keystroke would flicker results nobody can read.
 */
@Composable
fun SearchRoute(
    container: AppContainer,
    onOpenRecord: (String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val viewModel = khataGoViewModel(::SearchViewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var currency by remember { mutableStateOf<CurrencySpec?>(null) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = KhataGoSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        Spacer(Modifier.height(KhataGoSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                placeholder = { Text("Shop, person, item, note, amount…") },
                leadingIcon = {
                    Icon(imageVector = KhataGoIcons.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQuery("") }) {
                            Icon(
                                imageVector = KhataGoIcons.Delete,
                                contentDescription = "Clear",
                                tint = KhataGoColors.Ink400,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(KhataGoRadii.field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            Spacer(Modifier.width(KhataGoSpacing.sm))
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(KhataGoSpacing.sm)
                    .clickable(onClick = onDismiss),
            )
        }

        if (state.query.trim().length < 2) {
            EmptyState(
                title = "Search your whole ledger",
                body = "Two characters is enough. Names, items, notes and amounts are all searched — " +
                    "shops, credits, loans, EMIs, people, borrowings, lendings, income and expenses.",
                actionLabel = null,
                onAction = null,
            )
        } else if (!state.hasResults) {
            EmptyState(
                title = "Nothing matches “${state.query.trim()}”",
                body = "Search looks inside record titles, notes and counterparty names. If the record " +
                    "was deleted, it is gone for good — backups are the only copy.",
                actionLabel = null,
                onAction = null,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = KhataGoSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.groups.filter { it.items.isNotEmpty() }.forEach { group ->
                    item {
                        Text(
                            text = "${group.label} · ${group.items.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(group.items, key = { "${it.typeKey}-${it.id}" }) { hit ->
                        KhataGoCard(
                            onClick = {
                                // Route on the *stored* typeKey so a search hit opens the same detail
                                // screen the record's own module would have opened.
                                onOpenRecord(hit.typeKey, hit.id)
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = iconForSearch(hit.typeKey),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(KhataGoSpacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hit.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = hit.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (hit.amountMinor > 0L && currency != null) {
                                    Text(
                                        text = moneyText(hit.amountMinor, currency!!),
                                        style = KhataGoTypography.figure,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search hits cross module boundaries, and the module's own icon is what makes a grouped result
 * scannable in a single pass. Mapping it here (rather than reusing the attachment-target mapping)
 * keeps search labels honest: a `shop` hit shows the shop glyph, not a receipt.
 */
private fun iconForSearch(typeKey: String): androidx.compose.ui.graphics.vector.ImageVector = when (typeKey) {
    "shop", "shop_credit" -> KhataGoIcons.ShopCredit
    "loan" -> KhataGoIcons.Loan
    "emi" -> KhataGoIcons.Emi
    "borrowing" -> KhataGoIcons.Borrowed
    "lending" -> KhataGoIcons.Lent
    "person" -> KhataGoIcons.Person
    "income" -> KhataGoIcons.Income
    "expense" -> KhataGoIcons.Expense
    else -> KhataGoIcons.Receipt
}
