package app.worbus.data.database.mappers

import app.worbus.data.database.entities.RadioEntity
import app.worbus.domain.models.DomainRadio
import dev.zt64.subsonic.api.model.InternetRadioStation as ApiRadio

fun ApiRadio.toEntity() = RadioEntity(
	radioId = id,
	name = name,
	streamUrl = streamUrl,
	homepageUrl = homepageUrl
)

fun RadioEntity.toDomainModel() = DomainRadio(
	id = radioId,
	name = name,
	streamUrl = streamUrl,
	homepageUrl = homepageUrl
)
