package example.reservation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import example.reservation.ports.SessionId

@Composable
fun SessionListScreen(
    state: ListState,
    onSelect: (SessionId) -> Unit,
    onOpenStatus: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OngoingBanner(state.ongoing, onOpenStatus) }
        items(state.workshops, key = { it.id.value }) { workshop ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().testTag("session-${workshop.id.value}"),
                onClick = { onSelect(workshop.id) },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(workshop.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatStart(workshop.start)} 開始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "残席の目安 ${workshop.seatsHint}（参考。予約の成立は送信後の結果で決まります）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OngoingBanner(status: StatusState, onOpenStatus: () -> Unit) {
    val headline = status.request?.let(::requestHeadline)
        ?: status.reservations.takeIf { it.isNotEmpty() }?.let { "予約 ${it.size} 件の状況を確認できます" }
        ?: return
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("ongoing-banner").clickable(onClick = onOpenStatus),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "予約の状況を開く",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
