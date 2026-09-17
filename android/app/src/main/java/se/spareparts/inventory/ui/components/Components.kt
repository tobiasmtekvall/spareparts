package se.spareparts.inventory.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.qty
import se.spareparts.inventory.ui.theme.AppIcons
import se.spareparts.inventory.ui.theme.CodeStyle
import se.spareparts.inventory.ui.theme.LocalStatusColors

enum class StockState { OK, LOW, OUT, UNTRACKED }

val Part.stockState: StockState
    get() = when {
        isOut && min > 0 -> StockState.OUT
        isLow -> StockState.LOW
        min <= 0 && stock <= 0 -> StockState.UNTRACKED
        else -> StockState.OK
    }

@Composable
fun stockColors(state: StockState): Pair<Color, Color> {
    val c = LocalStatusColors.current
    return when (state) {
        StockState.OK -> c.ok to c.onOk
        StockState.LOW -> c.low to c.onLow
        StockState.OUT -> c.out to c.onOut
        StockState.UNTRACKED -> c.none to c.onNone
    }
}

fun stockLabel(state: StockState) = when (state) {
    StockState.OK -> "In stock"
    StockState.LOW -> "Low stock"
    StockState.OUT -> "Out of stock"
    StockState.UNTRACKED -> "No stock"
}

@Composable
fun StockBadge(part: Part, modifier: Modifier = Modifier) {
    val (bg, fg) = stockColors(part.stockState)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(
            Modifier.widthIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(part.stock.qty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (part.min > 0) {
                Text("min ${part.min.qty()}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
fun PartRow(part: Part, onClick: () -> Unit, modifier: Modifier = Modifier, pending: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.pn, style = CodeStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                if (!part.location.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Place, null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(part.location!!, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pending) {
                    Spacer(Modifier.width(6.dp))
                    Icon(AppIcons.Sync, "Pending sync", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Text(part.name.orEmpty().ifBlank { "(no name)" }, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(part.manufacturerLine.takeIf { it.isNotBlank() }, part.category?.takeIf { it.isNotBlank() })
                .joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        StockBadge(part)
    }
}

/** Thin strip shown under the top bar while offline or while changes wait to be sent. */
@Composable
fun SyncBanner(online: Boolean, reachable: Boolean?, pending: Int, error: String?, onClick: () -> Unit) {
    val show = !online || pending > 0 || reachable == false
    AnimatedVisibility(show, enter = expandVertically(), exit = shrinkVertically()) {
        val offline = !online || reachable == false
        val bg = if (offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        val fg = if (offline) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        Row(
            Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (offline) AppIcons.CloudOff else AppIcons.Sync, null, tint = fg, modifier = Modifier.size(16.dp))
            val text = buildString {
                append(
                    when {
                        !online -> "Offline"
                        reachable == false -> error ?: "Server unreachable"
                        else -> "Syncing"
                    }
                )
                if (pending > 0) append(" · $pending change${if (pending == 1) "" else "s"} pending")
                if (offline) append(" · using cached data")
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

fun formatEur(v: Double?): String =
    if (v == null) "–" else "€ " + String.format(java.util.Locale.ROOT, "%,.2f", v).replace(',', ' ')
