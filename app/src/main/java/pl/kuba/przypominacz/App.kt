package pl.kuba.przypominacz

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Cats.reload(this)
    }
}
