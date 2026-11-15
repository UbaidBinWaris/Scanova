package app.scanova.feature.tools.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.scanova.core.common.result.AppError
import app.scanova.core.designsystem.component.message
import app.scanova.feature.tools.R
import kotlin.coroutines.cancellation.CancellationException

/** Why a tool could not finish, in terms a screen can explain. */
internal sealed interface ToolError {
    /** Reading or writing the library failed (or the server could not be reached for a PDF). */
    data class Storage(val error: AppError) : ToolError

    /** A picked file is not an image or PDF Scanova can read, or it is damaged. */
    data class Unreadable(val fileName: String?) : ToolError

    /** A picked PDF needs a password. */
    data class PasswordProtected(val fileName: String?) : ToolError

    /** Drawing a PDF that is only stored encrypted needs Android 11 (it must never be written out in plaintext). */
    data object NeedsNewerAndroid : ToolError

    /** The phone ran out of memory for a very large picture or page. */
    data object TooLarge : ToolError
}

/** Carries a [ToolError] out of a long-running tool step. */
internal class ToolException(val error: ToolError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** Any failure of a tool step as a [ToolError]. Cancellation is never converted. */
internal fun Throwable.toToolError(): ToolError {
    if (this is CancellationException) throw this
    return when (this) {
        is ToolException -> error
        is OutOfMemoryError -> ToolError.TooLarge
        else -> ToolError.Storage(AppError.Unexpected(this::class.java.simpleName))
    }
}

@Composable
internal fun ToolError.message(): String = when (this) {
    is ToolError.Storage -> error.message()
    is ToolError.Unreadable -> if (fileName != null) {
        stringResource(R.string.feature_tools_error_unreadable_named, fileName)
    } else {
        stringResource(R.string.feature_tools_error_unreadable)
    }
    is ToolError.PasswordProtected -> if (fileName != null) {
        stringResource(R.string.feature_tools_error_password_named, fileName)
    } else {
        stringResource(R.string.feature_tools_error_password)
    }
    ToolError.NeedsNewerAndroid -> stringResource(R.string.feature_tools_error_needs_android_11)
    ToolError.TooLarge -> stringResource(R.string.feature_tools_error_too_large)
}
