package example.reservation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import example.kernel.ParticipantId
import example.reservation.core.ConfirmedDraft

@Composable
fun SessionDetailsScreen(
    state: DetailsState,
    onToggle: (ParticipantId) -> Unit,
    onRequestEligibility: () -> Unit,
    onConfirm: () -> Unit,
    onSubmit: (ConfirmedDraft) -> Unit,
) {
    val workshop = state.workshop
    if (workshop == null) {
        Text("開催回が選択されていません。一覧から選んでください。", Modifier.padding(16.dp))
        return
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workshop.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${formatStart(workshop.start)} 開始",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "取消は開始の24時間前より前まで受け付けます。24時間前ちょうどは取り消せません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RequirementText(workshop.requirement)
        }
        HorizontalDivider()
        Text(
            "参加者（${state.participants.count { it.selected }}名を選択中）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("participant-count"),
        )
        Column {
            state.participants.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = row.selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(row.id) },
                        )
                        .padding(vertical = 8.dp)
                        .testTag("participant-${row.id.value}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = row.selected, onCheckedChange = null)
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge)
                        row.qualification?.let {
                            Text(
                                qualificationLabel(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("qualification-${row.id.value}"),
                            )
                        }
                    }
                }
            }
        }
        if (workshop.requirement is RequirementView.Required) {
            FilledTonalButton(
                onClick = onRequestEligibility,
                enabled = state.canRequestEligibility,
                modifier = Modifier.fillMaxWidth().testTag("eligibility-button"),
            ) { Text("資格を確認する") }
        }
        StageContent(state, onConfirm, onSubmit)
    }
}

@Composable
private fun RequirementText(requirement: RequirementView) {
    val text = when (requirement) {
        RequirementView.NotRequired -> "資格は必要ありません。"
        is RequirementView.Required -> "参加には「${requirement.qualificationName}」の資格が必要です。参加者全員の確認が必要です。"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("requirement"),
    )
}

@Composable
private fun StageContent(state: DetailsState, onConfirm: () -> Unit, onSubmit: (ConfirmedDraft) -> Unit) {
    when (val stage = state.stage) {
        DetailsStage.Editing -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "編集中",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("stage-editing"),
            )
            Button(
                onClick = onConfirm,
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth().testTag("confirm-button"),
            ) { Text("内容を確認する") }
            Text(
                "確認しただけでは予約は送信されず、席も確保されません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is DetailsStage.Confirmed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "確認済み",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("stage-confirmed"),
            )
            Button(
                onClick = { onSubmit(stage.draft) },
                modifier = Modifier.fillMaxWidth().testTag("submit-button"),
            ) { Text("この内容で予約する") }
        }
    }
}
