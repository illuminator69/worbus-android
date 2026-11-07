package app.worbus.data.database.relations

import androidx.room3.Embedded
import androidx.room3.Relation
import app.worbus.data.database.entities.AlbumEntity
import app.worbus.data.database.entities.SongEntity

data class AlbumWithSongs(
	@Embedded val album: AlbumEntity,
	@Relation(
		parentColumns = ["albumId"],
		entityColumns = ["belongsToAlbumId"]
	)
	val songs: List<SongEntity>
)
