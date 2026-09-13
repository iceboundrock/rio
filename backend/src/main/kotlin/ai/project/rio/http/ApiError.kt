package ai.project.rio.http

/** The one error shape every non-2xx API response uses. See contracts/schemas/api-error.schema.json. */
data class ApiError(val code: String, val message: String) {
    companion object {
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val NOT_FOUND = "NOT_FOUND"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

/** Invalid request or domain rule violation -> HTTP 400. */
class ValidationException(message: String) : RuntimeException(message)

/**
 * Runs [block] for item [index] of an array request body, so a ValidationException it throws names
 * the item: `[1]: amount must be positive`. The single-object paths do not use this and keep the
 * bare message.
 */
inline fun <T> atItemIndex(index: Int, block: () -> T): T =
    try {
        block()
    } catch (e: ValidationException) {
        throw ValidationException("[$index]: ${e.message}")
    }

/** No representation the request accepts can carry the response -> HTTP 406. */
class NotAcceptableException(message: String) : RuntimeException(message)

/** Referenced resource does not exist -> HTTP 404. */
class NotFoundException(message: String) : RuntimeException(message)
