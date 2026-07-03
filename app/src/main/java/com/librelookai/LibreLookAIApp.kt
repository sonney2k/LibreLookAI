package com.librelookai

import android.app.Application
import com.librelookai.data.drive.SyncConnectivityCatchUp
import com.librelookai.data.session.StaticPreferenceMirrors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LibreLookAIApp : Application() {

    @Inject lateinit var staticPreferenceMirrors: StaticPreferenceMirrors
    @Inject lateinit var syncCatchUp: SyncConnectivityCatchUp

    override fun onCreate() {
        super.onCreate()
        staticPreferenceMirrors.start()
        syncCatchUp.start()
    }
}
