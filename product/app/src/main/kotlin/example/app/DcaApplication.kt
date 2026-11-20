package example.app

import android.app.Application
import example.app.composition.AppContainer

class DcaApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
