package app.worbus.domain.repositories

import app.worbus.data.database.mappers.toDomainModel
import app.worbus.domain.manager.SessionManager

class ShareRepository(
	private val sessionManager: SessionManager
) {
	suspend fun getShares() = sessionManager.api.getShares().map { it.toDomainModel() }
}
