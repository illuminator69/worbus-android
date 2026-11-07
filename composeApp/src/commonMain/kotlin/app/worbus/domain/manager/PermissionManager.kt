package app.worbus.domain.manager

expect class PermissionManager {
	fun openPermissionsSettings()
	suspend fun requestLocalNetworkPermission(): Boolean
	suspend fun requestNotificationsPermission(): Boolean
}
