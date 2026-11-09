package app.worbus.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * An androidx [SQLiteConnection] over a sqlite-jdbc [Connection], for running the app's own
 * migration and callback code on a real SQLite file here (the bundled driver's natives are
 * Android's). Enough of the interface for that code: prepare, bind, step, read text and numbers,
 * close. [failPrepare] lets a test make a given `prepare` throw, as an I/O error would.
 */
internal fun jdbcSQLiteConnection(
	jdbc: Connection,
	failPrepare: (sql: String, call: Int) -> Boolean = { _, _ -> false }
): SQLiteConnection {
	var calls = 0
	return Proxy.newProxyInstance(
		SQLiteConnection::class.java.classLoader, arrayOf(SQLiteConnection::class.java)
	) { _, method, args ->
		when (method.name) {
			"prepare" -> {
				val sql = args!![0] as String
				val call = calls++
				if (failPrepare(sql, call)) throw IllegalStateException("disk I/O error (code 10) on: $sql")
				jdbcStatement(jdbc.prepareStatement(sql))
			}
			"inTransaction" -> !jdbc.autoCommit
			"close" -> Unit
			else -> throw UnsupportedOperationException(method.name)
		}
	} as SQLiteConnection
}

private fun jdbcStatement(statement: PreparedStatement): SQLiteStatement {
	var rows: ResultSet? = null
	var executed = false
	return Proxy.newProxyInstance(
		SQLiteStatement::class.java.classLoader, arrayOf(SQLiteStatement::class.java)
	) { _, method, args ->
		when (method.name) {
			"bindText" -> statement.setString(args!![0] as Int, args[1] as String)
			"bindLong" -> statement.setLong(args!![0] as Int, args[1] as Long)
			"bindInt" -> statement.setInt(args!![0] as Int, args[1] as Int)
			"bindDouble" -> statement.setDouble(args!![0] as Int, args[1] as Double)
			"bindNull" -> statement.setNull(args!![0] as Int, java.sql.Types.NULL)
			"step" -> {
				if (!executed) {
					executed = true
					if (statement.execute()) rows = statement.resultSet
				}
				rows?.next() ?: false
			}
			"getText" -> rows!!.getString((args!![0] as Int) + 1)
			"getLong" -> rows!!.getLong((args!![0] as Int) + 1)
			"getInt" -> rows!!.getInt((args!![0] as Int) + 1)
			"getColumnCount" -> rows?.metaData?.columnCount ?: 0
			"isNull" -> rows!!.getObject((args!![0] as Int) + 1) == null
			"reset" -> { rows?.close(); rows = null; executed = false }
			"clearBindings" -> statement.clearParameters()
			"close" -> { rows?.close(); statement.close() }
			else -> throw UnsupportedOperationException(method.name)
		}.let { if (method.returnType == Void.TYPE) null else it }
	} as SQLiteStatement
}
