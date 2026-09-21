package se.spareparts.inventory.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.data.Movement
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.qty
import se.spareparts.inventory.domain.Permissions
import se.spareparts.inventory.ui.components.EmptyState
import se.spareparts.inventory.ui.components.formatEur
import se.spareparts.inventory.ui.components.stockColors
import se.spareparts.inventory.ui.components.stockLabel
import se.spareparts.inventory.ui.components.stockState
import se.spareparts.inventory.ui.theme.AppIcons
import se.spareparts.inventory.ui.theme.CodeStyle

private sealed interface DetailDialog {
    data class Quantity(val mode: QtyMode) : DetailDialog
    data object Location : DetailDialog
    data object Notes : DetailDialog
    data object Min : DetailDialog
}

enum class QtyMode(val title: String, val action: String, val hint: String) {
    TAKE("Take from stock", "Take", "e.g. replaced on 1469_Z040"),
    RETURN("Return to stock", "Return", "e.g. unused, back on shelf"),
    RECEIVE("Receive order", "Receive", "order / packing slip"),
    SET("Set count", "Set", "e.g. stocktaking"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartDetailScreen(c: AppContainer, pn: String, onBack: () -> Unit, snackbar: SnackbarHostState) {
    val vm: PartDetailViewModel = viewModel(key = "part-$pn",
        factory = viewModelFactory { initializer { PartDetailViewModel(c.repository, pn) } })
    val part by vm.part.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    // Permission-aware UI: controls the account may not use are not drawn at all.
    val session by c.session.session.collectAsStateWithLifecycle()
    val perms = session?.permissions ?: Permissions.NONE
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        TopAppBar(
            title = { Text(part?.pn ?: pn, style = CodeStyle, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                part?.let { p ->
                    IconButton(onClick = { share(context, p, vm.shareUrl()) }) { Icon(Icons.Filled.Share, "Share") }
                }
            },
            windowInsets = WindowInsets(0),
            scrollBehavior = scroll,
        )
        if (extras.loading || extras.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else Spacer(Modifier.height(2.dp))

        val p = part
        if (p == null) {
            if (extras.loading) return@Column
            EmptyState(Icons.Filled.Warning, "Part not found", extras.error ?: "$pn is not in the inventory.") {
                OutlinedButton(onClick = vm::refresh) { Text("Retry") }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { Header(p, extras.error.takeIf { !extras.refreshed }) }
            item(key = "stock") {
                StockCard(
                    p, pending, perms,
                    onDelta = { d -> vm.adjust(delta = d, reason = if (d < 0) "Taken" else "Returned") },
                    onDialog = { dialog = DetailDialog.Quantity(it) },
                    onEditMin = { dialog = DetailDialog.Min },
                )
            }
            item(key = "location") {
                InfoCard {
                    ListItem(
                        headlineContent = {
                            Text(p.location?.takeIf { it.isNotBlank() } ?: "No location set",
                                style = MaterialTheme.typography.titleMedium)
                        },
                        overlineContent = { Text("Location") },
                        leadingContent = { Icon(Icons.Filled.Place, null) },
                        trailingContent = { if (perms.canEditLight) Icon(Icons.Filled.Edit, "Edit location") },
                        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                        modifier = if (perms.canEditLight) Modifier.clickable { dialog = DetailDialog.Location } else Modifier,
                    )
                }
            }
            item(key = "money") { MoneyCard(p) }
            if (p.installed != null || p.positionList.isNotEmpty()) {
                item(key = "machine") { MachineCard(p) }
            }
            if (p.specs.isNotEmpty()) item(key = "specs") { SpecsCard(p) }
            item(key = "notes") {
                InfoCard(title = "Notes", action = if (!perms.canEditLight) null else {
                    { IconButton(onClick = { dialog = DetailDialog.Notes }) { Icon(Icons.Filled.Edit, "Edit notes") } }
                }) {
                    Text(
                        p.notes?.takeIf { it.isNotBlank() } ?: "No notes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (p.notes.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    )
                }
            }
            if (p.links.isNotEmpty() || extras.files.isNotEmpty()) {
                item(key = "docs") {
                    InfoCard(title = "Documentation") {
                        extras.files.forEach { f ->
                            DocRow(AppIcons.Description, f.title.ifBlank { f.url }, f.size?.let { humanSize(it) }) {
                                vm.fileUrl(f.url)?.let { openUrl(context, it) }
                            }
                        }
                        p.links.forEach { l ->
                            DocRow(AppIcons.Link, l.title?.takeIf { it.isNotBlank() } ?: l.url, Uri.parse(l.url).host) {
                                (if (l.url.startsWith("/")) vm.fileUrl(l.url) else l.url)?.let { openUrl(context, it) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item(key = "history") { HistoryCard(extras.movements, p.unit) }
        }
    }

    val p = part
    when (val d = dialog) {
        is DetailDialog.Quantity -> if (p != null) QuantityDialog(p, d.mode, onDismiss = { dialog = null }) { qty, reason ->
            dialog = null
            when (d.mode) {
                QtyMode.TAKE -> vm.adjust(delta = -qty, reason = reason.ifBlank { "Taken" }, label = "Took ${qty.qty()}")
                QtyMode.RETURN -> vm.adjust(delta = qty, reason = reason.ifBlank { "Returned" }, label = "Returned ${qty.qty()}")
                QtyMode.RECEIVE -> vm.adjust(delta = qty, reason = reason.ifBlank { "Received order ${p.orderNo.orEmpty()}".trim() }, label = "Received ${qty.qty()}")
                QtyMode.SET -> vm.adjust(set = qty, reason = reason.ifBlank { "Stock count" }, label = "Count set")
            }
        }
        DetailDialog.Location -> TextEditDialog("Location", p?.location.orEmpty(), singleLine = true,
            onDismiss = { dialog = null }) { dialog = null; vm.saveLocation(it) }
        DetailDialog.Notes -> TextEditDialog("Notes", p?.notes.orEmpty(), singleLine = false,
            onDismiss = { dialog = null }) { dialog = null; vm.saveNotes(it) }
        DetailDialog.Min -> TextEditDialog("Minimum quantity", p?.minQty?.qty()?.takeIf { it != "–" }.orEmpty(), singleLine = true,
            numeric = true, onDismiss = { dialog = null }) {
            dialog = null; vm.saveMin(it.replace(',', '.').toDoubleOrNull())
        }
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(p: Part, note: String?) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(p.pn, style = CodeStyle.copy(fontSize = 34.sp), color = MaterialTheme.colorScheme.primary)
        Text(p.name.orEmpty().ifBlank { "(no name)" }, style = MaterialTheme.typography.headlineSmall)
        if (p.manufacturerLine.isNotBlank()) {
            Text(p.manufacturerLine, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.category?.takeIf { it.isNotBlank() }?.let { SuggestionChip(onClick = {}, label = { Text(it) }) }
            p.mpn?.takeIf { it.isNotBlank() && it != p.model }?.let {
                SuggestionChip(onClick = {}, label = { Text("MPN $it", style = CodeStyle) })
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StockCard(p: Part, pending: Int, perms: Permissions, onDelta: (Double) -> Unit,
                      onDialog: (QtyMode) -> Unit, onEditMin: () -> Unit) {
    val state = p.stockState
    val (bg, fg) = stockColors(state)
    val view = LocalView.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
                    Text(stockLabel(state), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                if (pending > 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(AppIcons.Sync, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(" $pending pending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
                if (!perms.canAdjust) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(50)) {
                        Text("Read only", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (perms.canEditLight) TextButton(onClick = onEditMin) { Text("min ${p.min.qty()}") }
                else Text("min ${p.min.qty()}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (perms.canAdjust) FilledIconButton(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onDelta(-1.0) },
                    enabled = p.stock >= 1,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) { Icon(AppIcons.Remove, "Take one", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = p.stock,
                        transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                (slideOutVertically { if (up) -it else it } + fadeOut())
                        },
                        label = "stock",
                    ) { v ->
                        Text(v.qty(), style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold))
                    }
                    Text("on hand" + (p.unit?.takeIf { it.isNotBlank() }?.let { " · ${it.lowercase()}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (perms.canAdjust) FilledIconButton(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onDelta(1.0) },
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Filled.Add, "Add one", Modifier.size(32.dp)) }
            }
            if (p.min > 0) {
                val frac by animateFloatAsState((p.stock / p.min).toFloat().coerceIn(0f, 1f), label = "fill")
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = fg, trackColor = bg, strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(12.dp))
            }
            if (perms.canAdjust) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { onDialog(QtyMode.TAKE) }, enabled = p.stock > 0, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Take") }
                FilledTonalButton(onClick = { onDialog(QtyMode.RETURN) }, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(AppIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Return")
                }
                OutlinedButton(onClick = { onDialog(QtyMode.SET) }, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Set count") }
            }
            if (perms.canAdjust && (p.qtyOrdered ?: 0.0) > 0) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onDialog(QtyMode.RECEIVE) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.LocalShipping, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Receive order (+${p.qtyOrdered.qty()})")
                }
            }
        }
    }
}

@Composable
private fun MoneyCard(p: Part) {
    InfoCard(title = "Purchasing") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValue("Unit price", formatEur(p.price))
            KeyValue("Stock value", formatEur(p.stockValue))
            KeyValue("Ordered", p.qtyOrdered.qty())
            KeyValue("On packing slip", p.qtyOnSlip.qty())
            KeyValue("Order no", p.orderNo?.takeIf { it.isNotBlank() } ?: "–", mono = true)
            KeyValue("Packing slip", p.packingSlip?.takeIf { it.isNotBlank() } ?: "–", mono = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineCard(p: Part) {
    InfoCard(title = "In the machine") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            p.installed?.let { KeyValue("Installed", "${it.qty()} pcs") }
            if (p.positionList.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.positionList.forEach { pos ->
                        AssistChip(onClick = {}, label = { Text(pos, style = CodeStyle) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecsCard(p: Part) {
    InfoCard(title = "Specifications") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            p.specs.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(s.key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.45f))
                    Spacer(Modifier.width(8.dp))
                    Text(s.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(movements: List<Movement>?, unit: String?) {
    InfoCard(title = "History") {
        Column(Modifier.padding(bottom = 8.dp)) {
            when {
                movements == null -> Text("History is available when connected.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                movements.isEmpty() -> Text("No stock movements yet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                else -> movements.forEach { m ->
                    val d = m.delta ?: 0.0
                    val color = when {
                        d > 0 -> MaterialTheme.colorScheme.primary
                        d < 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ListItem(
                        leadingContent = {
                            Text((if (d > 0) "+" else "") + d.qty(), style = MaterialTheme.typography.titleLarge,
                                color = color, modifier = Modifier.width(56.dp))
                        },
                        headlineContent = {
                            Text(m.reason?.takeIf { it.isNotBlank() } ?: if (d == 0.0) "Count confirmed" else "Stock change",
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val who = listOfNotNull(m.user?.takeIf { it.isNotBlank() }, m.source?.takeIf { it.isNotBlank() })
                                .joinToString(" via ")
                            Text(listOf(relTime(m.ts), who).filter { it.isNotBlank() }.joinToString(" · "))
                        },
                        trailingContent = { Text("→ ${m.after.qty()}", style = MaterialTheme.typography.labelLarge) },
                        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = if (action == null) 14.dp else 2.dp,
                bottom = if (action == null) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
        }
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = if (mono) CodeStyle else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DocRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = sub?.let { { Text(it, maxLines = 1) } },
        trailingContent = { Icon(AppIcons.OpenInNew, "Open", Modifier.size(18.dp)) },
        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun QuantityDialog(p: Part, mode: QtyMode, onDismiss: () -> Unit, onConfirm: (Double, String) -> Unit) {
    val initial = when (mode) {
        QtyMode.RECEIVE -> p.qtyOrdered ?: 1.0
        QtyMode.SET -> p.stock
        else -> 1.0
    }
    var amount by rememberSaveable { mutableStateOf(initial.qty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    val value = amount.replace(',', '.').toDoubleOrNull()
    val valid = value != null && value >= 0 && (mode == QtyMode.SET || value > 0) &&
        (mode != QtyMode.TAKE || value <= p.stock)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mode.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${p.pn} · ${p.stock.qty()} on hand", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { amount = ((value ?: 0.0) - 1).coerceAtLeast(0.0).qty() }) {
                        Icon(AppIcons.Remove, "Less")
                    }
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                        singleLine = true, label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = !valid,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        textStyle = MaterialTheme.typography.headlineSmall,
                    )
                    FilledIconButton(onClick = { amount = ((value ?: 0.0) + 1).qty() }) { Icon(Icons.Filled.Add, "More") }
                }
                if (mode == QtyMode.TAKE && value != null && value > p.stock) {
                    Text("Only ${p.stock.qty()} in stock", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it }, singleLine = true,
                    label = { Text("Reason (optional)") }, placeholder = { Text(mode.hint) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value ?: 0.0, reason.trim()) }, enabled = valid) { Text(mode.action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextEditDialog(
    title: String, initial: String, singleLine: Boolean, numeric: Boolean = false,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun relTime(ts: Double): String {
    if (ts <= 0) return ""
    val ms = (ts * 1000).toLong()
    return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, p: Part, url: String?) {
    val text = buildString {
        appendLine("${p.pn} – ${p.name.orEmpty()}")
        if (p.manufacturerLine.isNotBlank()) appendLine(p.manufacturerLine)
        appendLine("On hand: ${p.stock.qty()} (min ${p.min.qty()})")
        p.location?.takeIf { it.isNotBlank() }?.let { appendLine("Location: $it") }
        url?.let { appendLine(it) }
    }.trim()
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "${p.pn} ${p.name.orEmpty()}")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share part").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

