package example.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import example.reservation.ui.ReservationApp
import example.reservation.ui.ReservationTheme
import example.reservation.ui.ReservationViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DcaApplication).container
        setContent {
            ReservationTheme {
                val viewModel: ReservationViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ReservationViewModel(container.core, container.catalog) }
                    },
                )
                ReservationApp(viewModel)
            }
        }
    }
}
