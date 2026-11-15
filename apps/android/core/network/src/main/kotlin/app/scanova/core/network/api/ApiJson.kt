package app.scanova.core.network.api

import kotlinx.serialization.json.Json

/**
 * JSON settings for the API.
 * - Unknown fields are ignored, so a newer server does not break an older app.
 * - Defaults are written, because `client: "mobile"` and `platform: "android"`
 *   are defaults in the request classes and the server requires them.
 * - Nulls are omitted, so optional request fields are simply absent.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
