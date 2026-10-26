package app.filemanagerx.domain

/**
 * Pure string helpers for absolute POSIX paths. Nothing in here touches the file system,
 * which keeps it trivially unit-testable.
 */
object PathUtils {
    const val ROOT = "/"

    /** Maximum length of one path component on virtually every Android file system (ext4, f2fs, FUSE). */
    const val MAX_NAME_BYTES = 255

    private val COMPOUND_EXTENSIONS = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.z")

    /** Collapses `.`, `..`, duplicate and trailing separators. The result always starts with `/`. */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return if (parts.isEmpty()) ROOT else parts.joinToString(separator = "/", prefix = "/")
    }

    /** Returns the parent directory, or `null` for the file system root. */
    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == ROOT) return null
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) ROOT else normalized.substring(0, index)
    }

    /** Returns the last path component (`/` for the root). */
    fun name(path: String): String {
        val normalized = normalize(path)
        return if (normalized == ROOT) ROOT else normalized.substringAfterLast('/')
    }

    fun join(parent: String, child: String): String = normalize("$parent/$child")

    /** True when [path] equals [ancestor] or lives anywhere below it. */
    fun isSameOrDescendant(path: String, ancestor: String): Boolean {
        val normalizedPath = normalize(path)
        val normalizedAncestor = normalize(ancestor)
        if (normalizedAncestor == ROOT) return true
        return normalizedPath == normalizedAncestor || normalizedPath.startsWith("$normalizedAncestor/")
    }

    /** Lower-case extension without the dot. Dot-files such as `.bashrc` have no extension. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** Splits `archive.tar.gz` into (`archive`, `.tar.gz`) and `photo.jpg` into (`photo`, `.jpg`). */
    fun splitName(name: String): Pair<String, String> {
        val lower = name.lowercase()
        for (compound in COMPOUND_EXTENSIONS) {
            if (lower.endsWith(compound) && name.length > compound.length) {
                return name.dropLast(compound.length) to name.takeLast(compound.length)
            }
        }
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** Returns [name], or `name (1)`, `name (2)`, ... until [exists] says the candidate is free. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, extension) = splitName(name)
        var index = 1
        while (true) {
            val candidate = "$base ($index)$extension"
            if (!exists(candidate)) return candidate
            index++
        }
    }

    data class Crumb(val label: String, val path: String)

    /** One entry per path component, starting with the root. */
    fun breadcrumbs(path: String): List<Crumb> {
        val normalized = normalize(path)
        val crumbs = mutableListOf(Crumb(ROOT, ROOT))
        if (normalized == ROOT) return crumbs
        var current = ""
        for (segment in normalized.removePrefix("/").split('/')) {
            current += "/$segment"
            crumbs += Crumb(segment, current)
        }
        return crumbs
    }

    enum class NameError { Empty, InvalidCharacters, Reserved, TooLong }

    /** Validates a single file or folder name typed by the user. */
    fun validateName(name: String): NameError? = when {
        name.isBlank() -> NameError.Empty
        name == "." || name == ".." -> NameError.Reserved
        name.contains('/') || name.contains('\u0000') -> NameError.InvalidCharacters
        name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> NameError.TooLong
        else -> null
    }
}
