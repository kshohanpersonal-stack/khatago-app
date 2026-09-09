package com.khatago.finance.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.ui.graphics.vector.ImageVector
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.domain.model.PayableType

/**
 * One icon set for the whole app: Material Symbols **Outlined**, single weight, single size.
 *
 * Why a fixed family matters more than it sounds: mixing filled and outlined glyphs (or a few
 * rounded ones from a screenshot) reads as "assembled from parts" within about five seconds of use.
 * Consistency of icon *style* is the cheapest premium signal in the app.
 */
object KhataGoIcons {
    // Navigation
    val Home: ImageVector = Icons.Outlined.Home
    val Records: ImageVector = Icons.Outlined.Inventory
    val Payments: ImageVector = Icons.Outlined.Payments
    val Analytics: ImageVector = Icons.Outlined.BarChart
    val More: ImageVector = Icons.Outlined.Settings

    // Modules
    val ShopCredit: ImageVector = Icons.Outlined.LocalMall
    val Loan: ImageVector = Icons.Outlined.AccountBalance
    val Emi: ImageVector = Icons.Outlined.PhoneAndroid
    /** The "opens another screen" affordance, AutoMirrored so it points the right way in RTL. */
    val ChevronRight: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight
    val Borrowed: ImageVector = Icons.Outlined.ArrowDownward
    val Lent: ImageVector = Icons.Outlined.ArrowUpward
    val Income: ImageVector = Icons.Outlined.TrendingUp
    val Expense: ImageVector = Icons.Outlined.TrendingDown
    val Person: ImageVector = Icons.Outlined.Person

    // Actions and affordances
    val Add: ImageVector = Icons.Outlined.Add
    val Search: ImageVector = Icons.Outlined.Search
    val Edit: ImageVector = Icons.Outlined.Edit
    val Delete: ImageVector = Icons.Outlined.Delete
    val Check: ImageVector = Icons.Outlined.Check
    val Paid: ImageVector = Icons.Outlined.CheckCircle
    val DueSoon: ImageVector = Icons.Outlined.Event
    val Overdue: ImageVector = Icons.Outlined.Inbox
    val History: ImageVector = Icons.Outlined.History
    val Receipt: ImageVector = Icons.Outlined.ReceiptLong
    val Documents: ImageVector = Icons.Outlined.Description
    val Share: ImageVector = Icons.Outlined.Share
    val Lock: ImageVector = Icons.Outlined.Lock
    val Notifications: ImageVector = Icons.Outlined.Notifications
    val Swap: ImageVector = Icons.Outlined.SwapHoriz
    val Calculator: ImageVector = Icons.Outlined.Calculate
    val Wallet: ImageVector = Icons.Outlined.Wallet
    val Offer: ImageVector = Icons.Outlined.LocalOffer
    val Hotel: ImageVector = Icons.Outlined.Hotel
    val Verified: ImageVector = Icons.Outlined.Verified
    val Pie: ImageVector = Icons.Outlined.PieChart
    val AddPerson: ImageVector = Icons.Outlined.PersonAdd

    fun forPayable(type: PayableType): ImageVector = when (type) {
        PayableType.ShopCredit -> ShopCredit
        PayableType.Loan -> Loan
        PayableType.Emi -> Emi
        PayableType.Borrowing -> Borrowed
        PayableType.Lending -> Lent
    }

    fun forTarget(targetType: String): ImageVector = when (targetType) {
        AttachmentEntity.TARGET_SHOP -> ShopCredit
        AttachmentEntity.TARGET_LOAN -> Loan
        AttachmentEntity.TARGET_EMI -> Emi
        AttachmentEntity.TARGET_PERSON -> Person
        AttachmentEntity.TARGET_INCOME -> Income
        AttachmentEntity.TARGET_EXPENSE -> Expense
        else -> Receipt
    }
}
