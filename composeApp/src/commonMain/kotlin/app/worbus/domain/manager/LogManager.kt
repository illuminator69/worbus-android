package app.worbus.domain.manager

import app.worbus.domain.parser.LogLine

expect class LogManager {
	val logs: List<LogLine>
	fun startStreaming()
	fun stopStreaming()
	fun clearLogs()
}
