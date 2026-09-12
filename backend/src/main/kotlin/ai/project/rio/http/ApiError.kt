package ai.project.rio.http

import kotlinx.serialization.Serializable

/** The one error shape every non-2xx API response uses. See contracts/schemas/api-error.schema.json. */
@Serializable
data class ApiError(val code: String, val message: String) {
    companion object {
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val NOT_FOUND = "NOT_FOUND"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

/** Invalid request or domain rule violation -> HTTP 400. */
class ValidationException(message: String) : RuntimeException(message)

/** No representation the request accepts can carry the response -> HTTP 406. */
class NotAcceptableException(message: String) : RuntimeException(message)

/** Referenced resource does not exist -> HTTP 404. */
class NotFoundException(message: String) : RuntimeException(message)
