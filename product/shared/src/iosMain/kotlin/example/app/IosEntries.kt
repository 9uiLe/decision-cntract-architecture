package example.app

import androidx.compose.ui.window.ComposeUIViewController
import example.app.composition.AppContainers
import platform.UIKit.UIViewController

fun reservationViewController(): UIViewController = ComposeUIViewController { ReservationEntry(AppContainers.shared) }

fun debugControlViewController(): UIViewController = ComposeUIViewController { DebugControlEntry(AppContainers.shared) }
