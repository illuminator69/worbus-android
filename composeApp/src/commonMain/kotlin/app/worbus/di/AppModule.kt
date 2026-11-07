package app.worbus.di

import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import app.worbus.ui.navigation.PersistentViewModelStoreOwner

val appModule = module {
	single { Settings() }
	single { initializeSingletonImageLoader(get()) }
	singleOf(::PersistentViewModelStoreOwner)
}
