package com.hu.nav

import android.app.Application
import com.hu.nav.data.amap.AMapPrivacy
import com.hu.nav.di.AppContainer

class NavApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AMapPrivacy.agree(this)
        container = AppContainer(this)
    }
}
