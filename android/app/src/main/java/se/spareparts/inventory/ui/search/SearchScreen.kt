package se.spareparts.inventory.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.qty
import se.spareparts.inventory.domain.PartMatcher
import se.spareparts.inventory.ui.components.EmptyState
import se.spareparts.inventory.ui.components.PartRow
import se.spareparts.inventory.ui.components.formatEur
import se.spareparts.inventory.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(c: AppContainer, initialQuery: String, lowOnly: Boolean, openPart: (String) -> Unit) {
    val repo = c.repository
    val parts by repo.parts.collectAsStateWithLifecycle()
    val categories by repo.categories.collectAsStateWithLifecycle()
    val pending by repo.pending.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val loaded by repo.loaded.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var lowFilter by rememberSaveable { mutableStateOf(lowOnly) }
    val listState = rememberLazyListState()

    val pendingPns = pending.mapTo(HashSet()) { it.pn }
    // A few hundred parts filter in well under a frame; keep it synchronous to avoid flicker.
    val filtered = remember(parts, query, category, lowFilter) {
        var list = parts
        if (category != null) list = list.filter { it.category == category }
        if (lowFilter) list = list.filter { it.isLow }
        list = PartMatcher.filter(query, list)
        if (lowOnly) list = list.sortedWith(compareByDescending<Part> { it.min - it.stock }.thenBy { it.pn })
        list
    }
    LaunchedEffect(query, category, lowFilter) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        if (lowOnly) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text("Low stock", style = MaterialTheme.typography.headlineMedium)
                val shortValue = filtered.sumOf { p -> (p.min - p.stock).coerceAtLeast(0.0) * (p.price ?: 0.0) }
                Text(
                    "${filtered.size} part${if (filtered.size == 1) "" else "s"} below minimum" +
                        if (shortValue > 0) " · ${formatEur(shortValue)} to refill" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(if (lowOnly) "Filter low-stock parts" else "Part no, name, model, location, spec…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!lowOnly) {
                item(key = "low") {
                    FilterChip(
                        selected = lowFilter, onClick = { lowFilter = !lowFilter },
                        label = { Text("Low stock") },
                        leadingIcon = { Icon(AppIcons.TrendingDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                }
            }
            items(categories, key = { it }) { cat ->
                val sel = category == cat
                FilterChip(
                    selected = sel,
                    onClick = { category = if (sel) null else cat },
                    label = { Text(cat) },
                    leadingIcon = if (sel) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${filtered.size} of ${parts.size}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lowOnly && filtered.isNotEmpty()) {
                Text(
                    "  ·  short ${filtered.sumOf { (it.min - it.stock).coerceAtLeast(0.0) }.qty()} units",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = status.syncing,
            onRefresh = { scope.launch { repo.sync() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loaded && parts.isEmpty() && !settings.configured -> EmptyState(
                    AppIcons.Inventory, "No parts yet",
                    "Open Settings and enter the address of your inventory server, e.g. http://192.168.1.20:8765",
                )
                loaded && parts.isEmpty() -> EmptyState(
                    AppIcons.Inventory, "No parts cached",
                    status.error?.let { "Could not sync: $it. Pull down to retry." } ?: "Pull down to sync with the server.",
                )
                filtered.isEmpty() && loaded -> EmptyState(
                    if (lowOnly) Icons.Filled.Check else Icons.Filled.Search,
                    if (lowOnly && query.isEmpty() && category == null) "All stocked up" else "Nothing found",
                    if (lowOnly && query.isEmpty() && category == null) "No part is below its minimum quantity."
                    else "Try another word, or clear the filters.",
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pn }) { p ->
                        PartRow(p, onClick = { openPart(p.pn) }, pending = p.pn in pendingPns,
                            modifier = Modifier.animateItem())
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}
