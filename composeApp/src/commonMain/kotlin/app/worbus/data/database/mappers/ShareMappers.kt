package app.worbus.data.database.mappers

import app.worbus.domain.models.DomainShare
import dev.zt64.subsonic.api.model.Share as ApiShare

fun ApiShare.toDomainModel() = DomainShare(
	id = id,
	url = url,
	description = description,
	username = username,
	createdAt = createdAt,
	expiresAt = expiresAt,
	lastVisited = lastVisited,
	visitCount = visitCount,
	items = items
)
