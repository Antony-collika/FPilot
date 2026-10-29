package com.miniichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miniichat.data.HistoryBranchMetadata

/** Global selector: changing it switches the whole transcript, not an individual message. */
@Composable
fun HistoryBranchSelector(
    branches: List<HistoryBranchMetadata>,
    activeBranchId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = branches.sortedBy {
        it.id.removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE
    }
    if (ordered.size <= 1) return
    val selected = ordered.indexOfFirst { it.id == activeBranchId }.takeIf { it >= 0 } ?: 0
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            enabled = enabled && selected > 0,
            onClick = { onSelect(ordered[selected - 1].id) }
        ) { Text("‹") }
        Text(
            "${selected + 1} / ${ordered.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            enabled = enabled && selected < ordered.lastIndex,
            onClick = { onSelect(ordered[selected + 1].id) }
        ) { Text("›") }
    }
}
