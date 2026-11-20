package example.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import example.app.composition.AppContainer
import example.app.debug.DebugControlScreen
import example.reservation.ui.ReservationApp
import example.reservation.ui.ReservationTheme
import example.reservation.ui.ReservationViewModel

@Composable
fun ReservationEntry(container: AppContainer) {
    ReservationTheme {
        val viewModel: ReservationViewModel = viewModel(
            factory = viewModelFactory {
                initializer { ReservationViewModel(container.core, container.eligibility, container.checkIn, container.catalog) }
            },
        )
        ReservationApp(viewModel)
    }
}

@Composable
fun DebugControlEntry(container: AppContainer) {
    ReservationTheme {
        DebugControlScreen(container.simulatorControls, container.clock, container.catalog)
    }
}
