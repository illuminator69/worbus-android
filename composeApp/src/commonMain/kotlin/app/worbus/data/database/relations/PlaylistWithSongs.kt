package app.worbus.data.database.relations

import androidx.room3.Embedded
import androidx.room3.Relation
import app.worbus.data.database.entities.PlaylistEntity
import app.worbus.data.database.entities.PlaylistSongCrossRef
import app.worbus.data.database.entities.SongEntity

data class PlaylistWithSongs(
	@Embedded val playlist: PlaylistEntity,
	@Relation(
		entity = PlaylistSongCrossRef::class,
		parentColumns = ["playlistId"],
		entityColumns = ["playlistId"]
	)
	val songs: List<PlaylistSong>
)

data class PlaylistSong(
	@Embedded val crossRef: PlaylistSongCrossRef,
	@Relation(
		parentColumns = ["songId"],
		entityColumns = ["songId"]
	)
	val song: SongEntity
)
