package app.worbus.androidApp.di

import app.worbus.di.ResourceProvider

class AndroidResourceProvider(
	override val appIconDefault: Int = app.worbus.androidApp.R.mipmap.ic_launcher,
	override val appIconTidepool: Int = app.worbus.androidApp.R.mipmap.ic_launcher_paper,
	override val icWorbus: Int = app.worbus.androidApp.R.drawable.ic_worbus,
	override val animPause: Int = app.worbus.androidApp.R.drawable.anim_pause
) : ResourceProvider
