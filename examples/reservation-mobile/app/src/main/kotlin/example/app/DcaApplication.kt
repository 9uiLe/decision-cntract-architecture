package example.app

import android.app.Application
import example.app.composition.AppContainer
import example.app.composition.AppContainers

class DcaApplication : Application() {
    val container: AppContainer get() = AppContainers.shared
}
