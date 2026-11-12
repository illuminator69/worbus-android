package app.worbus.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.worbus.data.database.entities.DownloadOriginEntity

/** Q-165: what each download was made from ([DownloadOriginEntity]), in `writes.db`. */
@Dao
interface DownloadOriginDao {
	@Query("SELECT * FROM DownloadOrigin")
	suspend fun all(): List<DownloadOriginEntity>

	@Upsert
	suspend fun upsert(rows: List<DownloadOriginEntity>)

	/** The ids are ONE JSON argument, so a long list passes no bind-variable ceiling. */
	@Query("DELETE FROM DownloadOrigin WHERE songId IN (SELECT value FROM json_each(:songIdsJson))")
	suspend fun delete(songIdsJson: String)
}
