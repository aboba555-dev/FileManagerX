package app.filemanagerx.domain

import app.filemanagerx.domain.model.FileKind

/** Maps file extensions to [FileKind]. */
object FileKinds {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "tiff", "dng", "raw")
    private val videos = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv", "ts", "mpg", "mpeg")
    private val audio = setOf("mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "wma", "amr", "mid", "midi")
    private val archives = setOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "lz4", "zst", "cpio", "ar", "arj", "z",
        "jar", "war", "aar", "xapk", "apks", "apkm",
    )
    private val documents = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "fb2", "djvu", "csv")
    private val text = setOf("txt", "log", "md", "ini", "conf", "cfg", "properties", "nfo", "rc", "prop", "list")
    private val code = setOf(
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "rs", "go", "py", "js", "mjs", "ts", "tsx", "jsx", "json", "xml",
        "html", "htm", "css", "scss", "sh", "bash", "yml", "yaml", "toml", "gradle", "sql", "smali", "lua", "php", "rb", "swift",
    )

    fun of(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.Folder
        return when (val extension = PathUtils.extension(name)) {
            "apk" -> FileKind.Apk
            in images -> FileKind.Image
            in videos -> FileKind.Video
            in audio -> FileKind.Audio
            in archives -> FileKind.Archive
            in documents -> FileKind.Document
            in text -> FileKind.Text
            in code -> FileKind.Code
            else -> if (extension.isEmpty() && name.startsWith('.')) FileKind.Text else FileKind.Other
        }
    }

    /** Kinds that the built-in editor is happy to open. */
    fun isEditable(kind: FileKind): Boolean = kind == FileKind.Text || kind == FileKind.Code
}
