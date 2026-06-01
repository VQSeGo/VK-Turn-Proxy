package com.freeturn.app

import android.app.Application
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Security.getProvider("EdDSA") == null) {
            Security.addProvider(EdDSASecurityProvider())
        }
        
        org.koin.core.context.startKoin {
            org.koin.android.ext.koin.androidLogger()
            org.koin.android.ext.koin.androidContext(this@App)
            org.koin.core.context.loadKoinModules(com.freeturn.app.di.appModule)
        }
    }
}
