package app.filemanagerx.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A piece of user-facing text that can be created anywhere (view models, background tasks) and
 * resolved against the current locale only when it is displayed.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    data class Plain(val value: String) : UiText {
        override fun resolve(context: Context): String = value
    }

    class Res(@StringRes private val id: Int, private vararg val args: Any) : UiText {
        override fun resolve(context: Context): String = context.getString(id, *args)
    }

    class Plural(
        @PluralsRes private val id: Int,
        private val count: Int,
        private vararg val args: Any,
    ) : UiText {
        override fun resolve(context: Context): String = context.resources.getQuantityString(id, count, *args)
    }
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
