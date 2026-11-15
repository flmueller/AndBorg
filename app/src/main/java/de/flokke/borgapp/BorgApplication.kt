package de.flokke.borgapp

import android.app.Application
import de.flokke.borgapp.data.HistoryRepo

class BorgApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        HistoryRepo.init(this)
    }
}
