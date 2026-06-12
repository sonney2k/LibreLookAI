package com.librelookai

import android.app.Application
import com.librelookai.data.session.StaticPreferenceMirrors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LibreLookAIApp : Application() {

    @Inject lateinit var staticPreferenceMirrors: StaticPreferenceMirrors

    override fun onCreate() {
        super.onCreate()
        staticPreferenceMirrors.start()
    }
}
