package com.adminstack.psieparki

import android.app.Application
import com.adminstack.psieparki.data.HelpersRepository
import com.adminstack.psieparki.data.LocationSource
import com.adminstack.psieparki.data.ParksRepository
import com.adminstack.psieparki.data.Settings
import com.adminstack.psieparki.data.Submissions

/** The long-lived objects, created once per process. No DI framework for five. */
class PsieParkiApp : Application() {
    lateinit var parks: ParksRepository
        private set
    lateinit var helpers: HelpersRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var location: LocationSource
        private set
    lateinit var submissions: Submissions
        private set

    override fun onCreate() {
        super.onCreate()
        parks = ParksRepository(this)
        helpers = HelpersRepository(this)
        settings = Settings(this)
        location = LocationSource(this)
        submissions = Submissions(this, BuildConfig.SUBMIT_URL, BuildConfig.VERSION_NAME)
    }
}
