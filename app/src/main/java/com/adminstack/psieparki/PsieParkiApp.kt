package com.adminstack.psieparki

import android.app.Application
import com.adminstack.psieparki.data.LocationSource
import com.adminstack.psieparki.data.ParksRepository
import com.adminstack.psieparki.data.Settings

/** The three long-lived objects, created once per process. No DI framework for three. */
class PsieParkiApp : Application() {
    lateinit var parks: ParksRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var location: LocationSource
        private set

    override fun onCreate() {
        super.onCreate()
        parks = ParksRepository(this)
        settings = Settings(this)
        location = LocationSource(this)
    }
}
