package app.scanova.core.data.serverai

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.network.ScanovaRemote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Page clean-up on the server (GCDRNet, docs/ai-image-research.md): the
 * flattened page goes to the owner's server and comes back without shadows,
 * colour casts and bleed-through. Only for an owner, and only while their
 * "Page clean-up" switch is on and the model is loaded.
 */
interface PageCleanupRepository {
    /** True while this account can clean pages: an owner whose clean-up switch is on and ready. */
    val available: Flow<Boolean>

    /** [jpeg] (the flattened page) cleaned by the server, as JPEG. Fails with AI_NOT_READY once it is off. */
    suspend fun clean(jpeg: ByteArray): AppResult<ByteArray>
}

internal class PageCleanupRepositoryImpl(
    private val remote: ScanovaRemote,
    serverAi: ServerAiRepository,
    private val dispatchers: AppDispatchers,
) : PageCleanupRepository {

    override val available: Flow<Boolean> = serverAi.access
        .map { access -> (access as? ServerAiAccess.Owner)?.cleanup?.state == ServerAiState.READY }
        .distinctUntilChanged()

    override suspend fun clean(jpeg: ByteArray): AppResult<ByteArray> =
        withContext(dispatchers.io) { remote.enhancePage(jpeg) }
}
