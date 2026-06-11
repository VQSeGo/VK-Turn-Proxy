package com.freeturn.app.di

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.AppUpdater
import com.freeturn.app.domain.LocalProxyManager
import com.freeturn.app.domain.ProxyOrchestrator
import com.freeturn.app.viewmodel.ProxyViewModel
import com.freeturn.app.viewmodel.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { AppPreferences(androidContext()) }
    single { LocalProxyManager(androidContext()) }
    single { AppUpdater(androidContext(), get()) }
    single { ProxyOrchestrator(get(), get()) }

    viewModel { ProxyViewModel(get(), get(), androidContext()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), androidContext()) }
}
