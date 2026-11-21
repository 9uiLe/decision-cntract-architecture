package example.reservation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import example.reservation.core.Acceptance
import example.reservation.core.Rejection

// 受付終了で拒否された送信も、対象の下書きに結び付いた結果を予約状況に残す。
private fun Acceptance.showsStatus(): Boolean = when (this) {
    Acceptance.Accepted -> true
    is Acceptance.Rejected -> reason == Rejection.RegistrationClosed
}

enum class Route(val title: String) {
    List("ワークショップ予約"),
    Details("予約内容の確認"),
    Status("予約の状況"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ReservationApp(viewModel: ReservationViewModel) {
    var route by rememberSaveable { mutableStateOf(Route.List) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(current.message)
        viewModel.consumeNotice(current)
    }
    BackHandler(enabled = route != Route.List) { route = Route.List }

    Surface(modifier = Modifier.fillMaxSize().exposeTestTags()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(route.title) },
                    navigationIcon = {
                        if (route != Route.List) {
                            TextButton(onClick = { route = Route.List }, modifier = Modifier.testTag("back")) {
                                Text("戻る")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (route) {
                    Route.List -> SessionListScreen(
                        state = state.list,
                        onSelect = {
                            if (viewModel.selectWorkshop(it) == Acceptance.Accepted) route = Route.Details
                        },
                        onOpenStatus = { route = Route.Status },
                    )
                    Route.Details -> SessionDetailsScreen(
                        state = state.details,
                        onToggle = { viewModel.toggleParticipant(it) },
                        onRequestEligibility = { viewModel.requestEligibility() },
                        onConfirm = { viewModel.confirm() },
                        onSubmit = {
                            if (viewModel.submit(it).showsStatus()) route = Route.Status
                        },
                    )
                    Route.Status -> ReservationStatusScreen(
                        state = state.status,
                        endpoint = viewModel,
                        onCancel = { viewModel.cancel(it) },
                        onCheckIn = { viewModel.checkInParticipant(it) },
                        onLookUpCheckIn = { viewModel.lookUpCheckIn(it) },
                        onRetryCheckIn = { viewModel.retryCheckIn(it) },
                        onChooseAgain = { route = Route.List },
                    )
                }
            }
        }
    }
}
