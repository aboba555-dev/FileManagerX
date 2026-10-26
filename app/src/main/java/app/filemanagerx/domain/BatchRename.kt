package app.filemanagerx.domain

import app.filemanagerx.domain.model.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.PatternSyntaxException

sealed interface RenameRule {
    val keepExtension: Boolean

    /**
     * Builds each name from [template]. Tokens: `{n}` counter, `{name}` original name without extension,
     * `{ext}` extension without the dot, `{date}` modification date as yyyyMMdd.
     */
    data class Pattern(
        val template: String,
        val start: Int = 1,
        val padding: Int = 2,
        override val keepExtension: Boolean = true,
    ) : RenameRule

    /** Replaces [find] inside each name, literally or as a regular expression (`$1` groups work). */
    data class Replace(
        val find: String,
        val replacement: String,
        val useRegex: Boolean = false,
        override val keepExtension: Boolean = true,
    ) : RenameRule
}

enum class RenameProblem { Empty, InvalidName, Duplicate, Exists, BadPattern }

data class RenamePreview(val item: FileItem, val newName: String, val problem: RenameProblem?) {
    val changed: Boolean get() = newName != item.name
}

object BatchRename {
    /**
     * Computes the new name of every item without touching the disk. [folderNames] are all names
     * currently in the folder; a result that lands on one of them (other than the item's own name) is
     * reported as [RenameProblem.Exists], which also rules out chains such as a→b, b→c.
     */
    fun preview(items: List<FileItem>, rule: RenameRule, folderNames: Set<String>): List<RenamePreview> {
        val compiled: Regex? = (rule as? RenameRule.Replace)?.takeIf { it.useRegex && it.find.isNotEmpty() }?.let {
            try {
                Regex(it.find)
            } catch (_: PatternSyntaxException) {
                return items.map { item -> RenamePreview(item, item.name, RenameProblem.BadPattern) }
            }
        }
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

        val drafts = items.mapIndexed { index, item ->
            val (base, dottedExtension) = if (rule.keepExtension && !item.isDirectory) {
                PathUtils.splitName(item.name)
            } else {
                item.name to ""
            }
            val newBase = when (rule) {
                is RenameRule.Pattern -> expand(rule, item, base, dottedExtension, index, dateFormat)
                is RenameRule.Replace -> when {
                    rule.find.isEmpty() -> base
                    compiled != null -> base.replace(compiled, rule.replacement)
                    else -> base.replace(rule.find, rule.replacement)
                }
            }
            item to newBase + dottedExtension
        }

        val counts = drafts.groupingBy { it.second }.eachCount()
        return drafts.map { (item, newName) ->
            val problem = when {
                newName == item.name -> null
                PathUtils.validateName(newName) == PathUtils.NameError.Empty -> RenameProblem.Empty
                PathUtils.validateName(newName) != null -> RenameProblem.InvalidName
                counts.getValue(newName) > 1 -> RenameProblem.Duplicate
                newName in folderNames -> RenameProblem.Exists
                else -> null
            }
            RenamePreview(item, newName, problem)
        }
    }

    private fun expand(
        rule: RenameRule.Pattern,
        item: FileItem,
        base: String,
        dottedExtension: String,
        index: Int,
        dateFormat: SimpleDateFormat,
    ): String {
        val counter = (rule.start + index).toString().padStart(rule.padding.coerceIn(0, 9), '0')
        return rule.template
            .replace("{n}", counter)
            .replace("{name}", base)
            .replace("{ext}", dottedExtension.removePrefix("."))
            .replace("{date}", if (item.modifiedAt > 0L) dateFormat.format(Date(item.modifiedAt)) else "")
    }
}
