package app.filemanagerx.data.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.filemanagerx.domain.model.SortField
import app.filemanagerx.domain.model.SortSpec
import app.filemanagerx.domain.model.ThemeMode
import app.filemanagerx.domain.model.ViewMode
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val showHidden: Boolean = false,
    val sort: SortSpec = SortSpec(),
    val viewMode: ViewMode = ViewMode.List,
    val confirmDelete: Boolean = true,
    val rootModeEnabled: Boolean = false,
    /** Charset for ZIP entry names that do not carry the UTF-8 flag (old Windows tools used CP866 / CP1251). */
    val zipCharset: String = DEFAULT_ZIP_CHARSET,
    val welcomeDone: Boolean = false,
    val thumbnails: Boolean = true,
    val useTrash: Boolean = true,
    val bookmarks: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_ZIP_CHARSET = "UTF-8"
        const val MAX_RECENTS = 8
        val ZIP_CHARSETS = listOf("UTF-8", "IBM866", "windows-1251", "IBM437")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun setThemeMode(value: ThemeMode) = write(Keys.THEME_MODE, value.name)

    suspend fun setDynamicColor(value: Boolean) = write(Keys.DYNAMIC_COLOR, value)

    suspend fun setAmoled(value: Boolean) = write(Keys.AMOLED, value)

    suspend fun setShowHidden(value: Boolean) = write(Keys.SHOW_HIDDEN, value)

    suspend fun setViewMode(value: ViewMode) = write(Keys.VIEW_MODE, value.name)

    suspend fun setConfirmDelete(value: Boolean) = write(Keys.CONFIRM_DELETE, value)

    suspend fun setRootModeEnabled(value: Boolean) = write(Keys.ROOT_MODE, value)

    suspend fun setZipCharset(value: String) = write(Keys.ZIP_CHARSET, value)

    suspend fun setWelcomeDone(value: Boolean) = write(Keys.WELCOME_DONE, value)

    suspend fun setThumbnails(value: Boolean) = write(Keys.THUMBNAILS, value)

    suspend fun setUseTrash(value: Boolean) = write(Keys.USE_TRASH, value)

    /** Pins [path], or unpins it when it is already pinned. */
    suspend fun toggleBookmark(path: String) {
        store.edit { prefs ->
            val current = decodeList(prefs[Keys.BOOKMARKS])
            prefs[Keys.BOOKMARKS] = encodeList(if (path in current) current - path else current + path)
        }
    }

    /** Moves [path] to the front of the recent list. */
    suspend fun pushRecent(path: String) {
        store.edit { prefs ->
            val current = decodeList(prefs[Keys.RECENTS])
            prefs[Keys.RECENTS] = encodeList((listOf(path) + current.filter { it != path }).take(AppSettings.MAX_RECENTS))
        }
    }

    suspend fun clearRecents() = write(Keys.RECENTS, "")

    suspend fun setSort(value: SortSpec) {
        store.edit { prefs ->
            prefs[Keys.SORT_FIELD] = value.field.name
            prefs[Keys.SORT_ASCENDING] = value.ascending
            prefs[Keys.SORT_FOLDERS_FIRST] = value.foldersFirst
        }
    }

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            amoled = this[Keys.AMOLED] ?: defaults.amoled,
            showHidden = this[Keys.SHOW_HIDDEN] ?: defaults.showHidden,
            sort = SortSpec(
                field = enumOrDefault(this[Keys.SORT_FIELD], defaults.sort.field),
                ascending = this[Keys.SORT_ASCENDING] ?: defaults.sort.ascending,
                foldersFirst = this[Keys.SORT_FOLDERS_FIRST] ?: defaults.sort.foldersFirst,
            ),
            viewMode = enumOrDefault(this[Keys.VIEW_MODE], defaults.viewMode),
            confirmDelete = this[Keys.CONFIRM_DELETE] ?: defaults.confirmDelete,
            rootModeEnabled = this[Keys.ROOT_MODE] ?: defaults.rootModeEnabled,
            zipCharset = this[Keys.ZIP_CHARSET] ?: defaults.zipCharset,
            welcomeDone = this[Keys.WELCOME_DONE] ?: defaults.welcomeDone,
            thumbnails = this[Keys.THUMBNAILS] ?: defaults.thumbnails,
            useTrash = this[Keys.USE_TRASH] ?: defaults.useTrash,
            bookmarks = decodeList(this[Keys.BOOKMARKS]),
            recents = decodeList(this[Keys.RECENTS]),
        )
    }

    // Paths may contain any character except NUL, so each one is URL-encoded and the list is newline separated.
    private fun encodeList(values: List<String>): String = values.joinToString("\n") { URLEncoder.encode(it, "UTF-8") }

    private fun decodeList(raw: String?): List<String> =
        raw.orEmpty().split('\n').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED = booleanPreferencesKey("amoled")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        val SORT_FOLDERS_FIRST = booleanPreferencesKey("sort_folders_first")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val ROOT_MODE = booleanPreferencesKey("root_mode")
        val ZIP_CHARSET = stringPreferencesKey("zip_charset")
        val WELCOME_DONE = booleanPreferencesKey("welcome_done")
        val THUMBNAILS = booleanPreferencesKey("thumbnails")
        val USE_TRASH = booleanPreferencesKey("use_trash")
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val RECENTS = stringPreferencesKey("recents")
    }
}
