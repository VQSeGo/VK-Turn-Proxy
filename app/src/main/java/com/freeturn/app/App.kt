package com.freeturn.app

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import com.freeturn.app.di.appModule

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // ed25519/curve25519 обеспечивает Bouncy Castle на classpath — mwiede/jsch 2.x
        // подхватывает его сам, регистрация Security-провайдера не нужна (старый
        // net.i2p EdDSASecurityProvider jsch 2.x игнорировал, потому ed25519 не работал).
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule)
        }
    }
}
