package app.worbus.data.database.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable
import app.worbus.domain.models.DomainContributor
import app.worbus.domain.models.DomainExplicitStatus
import app.worbus.domain.models.DomainReplayGain
import app.worbus.domain.models.DomainSongArtist
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
// CacheDatabase 24 (fork; MIGRATION_CACHE_23_24): indices the relations and the Liked lists scan by.
@Entity(
	indices = [
		Index(value = ["belongsToAlbumId"]),
		Index(value = ["artistId"]),
		Index(value = ["starredAt"])
	]
)
data class SongEntity(
	@PrimaryKey val songId: String,
	val title: String,
	val artistName: String?,
	val artistId: String,
	val albumTitle: String?,
	val belongsToAlbumId: String?,
	val parentId: String?,
	val comment: String?,
	val trackNumber: Int?,
	val discNumber: Int?,
	val isrc: List<String>,
	val year: Int?,
	val genre: String?,
	val genres: List<String>,
	val moods: List<String>,
	val duration: Duration,
	val bpm: Int?,
	val contributors: List<DomainContributor>,
	val playCount: Int = 0,
	val userRating: Int?,
	val averageRating: Float?,
	val bitRate: Int?,
	val bitDepth: Int?,
	val sampleRate: Int?,
	val audioChannelCount: Int?,
	val replayGain: DomainReplayGain?,
	val fileSize: Long,
	val fileExtension: String?,
	val mimeType: String?,
	val filePath: String?,
	val starredAt: Instant?,
	val coverArtId: String?,
	val musicBrainzId: String?,
	val explicitStatus: DomainExplicitStatus,
	val artists: List<DomainSongArtist>,
	val albumArtists: List<DomainSongArtist>,
	val isExternal: Boolean
)
