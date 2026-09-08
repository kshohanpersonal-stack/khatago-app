package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Global search.
 *
 * One UNION over the searchable tables, evaluated by SQLite with LIKE on indexed text columns.
 * This is why search works offline, is instant with thousands of records, and needs no indexing
 * service or network round-trip. Matching is case-insensitive for ASCII (SQLite's LIKE default)
 * and partial on both sides, which is what "find my shop" typing looks like in practice.
 *
 * Results are capped deliberately: a person typing "R" on a phone does not want 4,000 rows.
 */
@Dao
interface SearchDao {

    @Query(
        """
        SELECT * FROM (
            SELECT 'shop' AS typeKey, id AS id, name AS title,
                   COALESCE(ownerName, category, 'Shop') AS subtitle,
                   0 AS amountMinor
            FROM shops WHERE name LIKE '%' || :query || '%' OR COALESCE(ownerName,'') LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'person' AS typeKey, id AS id, name AS title, relationship AS subtitle,
                   0 AS amountMinor
            FROM people WHERE name LIKE '%' || :query || '%' OR COALESCE(phone,'') LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'shop_credit' AS typeKey, c.id AS id, c.productName AS title, s.name AS subtitle,
                   c.totalAmountMinor AS amountMinor
            FROM shop_credits c JOIN shops s ON s.id = c.shopId
            WHERE c.productName LIKE '%' || :query || '%' OR s.name LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'loan' AS typeKey, l.id AS id, l.loanName AS title, l.institution AS subtitle,
                   l.totalPayableMinor AS amountMinor
            FROM loans l WHERE l.loanName LIKE '%' || :query || '%' OR l.institution LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'emi' AS typeKey, e.id AS id, e.productName AS title, e.merchant AS subtitle,
                   e.totalPayableMinor AS amountMinor
            FROM emi_purchases e
            WHERE e.productName LIKE '%' || :query || '%' OR e.merchant LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'income' AS typeKey, i.id AS id, COALESCE(i.source, i.categoryName) AS title,
                   'Income · ' || i.categoryName AS subtitle, i.amountMinor AS amountMinor
            FROM incomes i WHERE COALESCE(i.source,'') LIKE '%' || :query || '%' OR i.categoryName LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'expense' AS typeKey, x.id AS id, COALESCE(x.merchant, x.categoryName) AS title,
                   'Expense · ' || x.categoryName AS subtitle, x.amountMinor AS amountMinor
            FROM expenses x WHERE COALESCE(x.merchant,'') LIKE '%' || :query || '%' OR x.categoryName LIKE '%' || :query || '%'
            UNION ALL
            SELECT 'payment' AS typeKey, p.id AS id, 'Payment · ' || p.methodName AS title,
                   p.payableType || ' #' || p.payableId AS subtitle, p.amountMinor AS amountMinor
            FROM payments p WHERE COALESCE(p.reference,'') LIKE '%' || :query || '%'
                                OR COALESCE(p.note,'') LIKE '%' || :query || '%'
        )
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 60): List<SearchRow>

    /** Borrowings and lendings are found through their person, but need their own route. */
    @Query(
        """
        SELECT 'borrowing' AS typeKey, b.id AS id, p.name AS title, 'You borrowed' AS subtitle,
               b.amountMinor AS amountMinor
        FROM borrowings b JOIN people p ON p.id = b.personId
        WHERE p.name LIKE '%' || :query || '%' OR COALESCE(b.notes,'') LIKE '%' || :query || '%'
        UNION ALL
        SELECT 'lending' AS typeKey, l.id AS id, p.name AS title, 'They owe you' AS subtitle,
               l.amountMinor AS amountMinor
        FROM lendings l JOIN people p ON p.id = l.personId
        WHERE p.name LIKE '%' || :query || '%' OR COALESCE(l.notes,'') LIKE '%' || :query || '%'
        LIMIT :limit
        """,
    )
    suspend fun searchObligations(query: String, limit: Int = 60): List<SearchRow>
}
