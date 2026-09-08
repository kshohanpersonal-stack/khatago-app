package com.khatago.finance.data.repo

import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.dao.SearchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Global search across every record type.
 *
 * Debounced at 180 ms: SQLite LIKE on an indexed column is quick, but firing a query on every
 * keystroke on a mid-range phone is what makes a search field feel sticky. The minimum query length
 * is 2 because a single letter returns hundreds of unhelpful rows.
 *
 * Ordering is intentionally "type first, then name": when you search for "Rahim" you want to see
 * that you have one shop, one person and two lendings with that name, not an interleaved wall.
 */
class SearchRepository(private val database: KhataGoDatabase) {

    fun search(query: String): Flow<List<SearchGroup>> = flow {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            emit(emptyList())
            return@flow
        }
        delay(DEBOUNCE_MILLIS)
        val primary = database.searchDao().search(trimmed, LIMIT)
        val secondary = database.searchDao().searchObligations(trimmed, LIMIT)
        emit((primary + secondary).grouped())
    }.flowOn(Dispatchers.IO)

    private fun List<SearchRow>.grouped(): List<SearchGroup> =
        groupBy { it.typeKey }
            .entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                val key = entry.key
                val rows = entry.value
                SearchGroup(
                    typeKey = key,
                    label = labelOf(key),
                    items = rows
                        .map { it.toHit() }
                        .sortedWith(
                            compareByDescending<SearchHit> { hit -> hit.amountMinor }
                                .thenBy { hit -> hit.title.lowercase() },
                        )
                        .take(PER_GROUP_LIMIT),
                )
            }

    private fun labelOf(key: String): String = when (key) {
        "shop" -> "Shops"
        "shop_credit" -> "Shop credits"
        "person" -> "People"
        "borrowing" -> "Borrowed"
        "lending" -> "Lent"
        "loan" -> "Loans"
        "emi" -> "EMI purchases"
        "income" -> "Income"
        "expense" -> "Expenses"
        "payment" -> "Payments"
        else -> "Other"
    }

    companion object {
        const val DEBOUNCE_MILLIS = 180L
        const val LIMIT = 80
        const val PER_GROUP_LIMIT = 12
    }
}

data class SearchGroup(
    val typeKey: String,
    val label: String,
    val items: List<SearchHit>,
)

/** Same fields as [com.khatago.finance.data.db.dao.SearchRow]; a separate type keeps the UI free of DAO imports. */
data class SearchHit(
    val typeKey: String,
    val id: Long,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
)

fun SearchRow.toHit(): SearchHit = SearchHit(typeKey, id, title, subtitle, amountMinor)
