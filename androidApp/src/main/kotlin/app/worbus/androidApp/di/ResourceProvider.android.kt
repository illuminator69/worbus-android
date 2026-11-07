package app.worbus.androidApp.di

import app.worbus.di.ResourceProvider

class AndroidResourceProvider(
	override val appIconDefault: Int = app.worbus.androidApp.R.mipmap.ic_launcher,
	override val appIconInverted: Int = app.worbus.androidApp.R.mipmap.ic_launcher_inverted,
	override val icNavic: Int = app.worbus.androidApp.R.drawable.ic_navic,
	override val animPause: Int = app.worbus.androidApp.R.drawable.anim_pause
) : ResourceProvider
