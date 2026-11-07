package app.worbus.domain.manager

import app.worbus.domain.models.settings.AppIconVariant

expect class AppIconManager {
	fun setVariant(newVariant: AppIconVariant)
	fun getIcon(variant: AppIconVariant): Any?
}
