package example.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import example.app.DcaApplication
import example.reservation.ui.ReservationTheme

class DebugControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DcaApplication).container
        setContent {
            ReservationTheme {
                DebugControlScreen(container.simulatorControls, container.clock, container.catalog)
            }
        }
    }
}
