import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class StatusCheckTicket

@Composable
fun StatusCard(message: String, ticket: StatusCheckTicket, check: (StatusCheckTicket) -> Unit) {
    val onClick = remember(message) { { check(ticket) } }
    Text(message)
    Button(onClick = onClick) { Text("状況を照会する") }
}
