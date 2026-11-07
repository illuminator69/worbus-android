package app.worbus.di

// This class is a workaround for not being able to access :androidApp's R class inside :composeApp
interface ResourceProvider {
	val appIconDefault: Int
	val appIconTidepool: Int
	val icWorbus: Int
	val animPause: Int
}
