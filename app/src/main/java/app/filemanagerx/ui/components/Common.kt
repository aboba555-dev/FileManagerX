package app.filemanagerx.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.filemanagerx.R
import app.filemanagerx.appContainer
import app.filemanagerx.core.asString
import app.filemanagerx.data.tasks.TaskProgress
import app.filemanagerx.di.AppContainer
import app.filemanagerx.domain.model.FileKind
import app.filemanagerx.ui.theme.kindColors
import java.text.DateFormat
import java.util.Date

@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

fun glyphFor(kind: FileKind): String = when (kind) {
    FileKind.Folder -> Fa.Folder
    FileKind.Image -> Fa.FileImage
    FileKind.Video -> Fa.FileVideo
    FileKind.Audio -> Fa.FileAudio
    FileKind.Archive -> Fa.FileZipper
    FileKind.Document -> Fa.FilePdf
    FileKind.Text -> Fa.FileLines
    FileKind.Code -> Fa.FileCode
    FileKind.Apk -> Fa.Cube
    FileKind.Other -> Fa.File
}

@Composable
fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0L))

private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

fun formatDate(millis: Long): String = if (millis <= 0L) "" else dateFormat.format(Date(millis))

/** The rounded tile with a file-kind glyph that leads every row. */
@Composable
fun KindTile(
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isLink: Boolean = false,
    thumbnail: ImageBitmap? = null,
) {
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.34f)),
        )
        return
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val (container, content) = if (kind == FileKind.Folder) {
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer
    } else {
        kindColors(kind, dark).let { it.container to it.content }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        FaIcon(glyph = if (isLink) Fa.Link else glyphFor(kind), size = size * 0.46f, tint = content)
    }
}

@Composable
fun IconAction(glyph: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val color = if (tint == Color.Unspecified) colorScheme.onSurfaceVariant else tint
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        FaIcon(glyph = glyph, size = 20.dp, tint = color)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colorScheme.primary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(glyph: String, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            FaIcon(glyph, size = 34.dp, tint = colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colorScheme.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
        }
    }
}

/** Floating card listing running background tasks with a cancel button each. */
@Composable
fun TaskOverlay(tasks: List<TaskProgress>, onCancel: (Long) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = tasks.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colorScheme.inverseSurface,
            contentColor = colorScheme.inverseOnSurface,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                tasks.forEach { task ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title.asString(), style = MaterialTheme.typography.titleSmall)
                                if (task.detail.isNotEmpty()) {
                                    Text(
                                        task.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconAction(Fa.Xmark, stringResource(R.string.action_cancel), { onCancel(task.id) }, tint = colorScheme.inverseOnSurface)
                        }
                        Spacer(Modifier.height(8.dp))
                        val fraction = task.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colorScheme.inversePrimary, trackColor = colorScheme.inverseOnSurface.copy(alpha = 0.2f))
                        } else {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.inversePrimary,
                                trackColor = colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) colorScheme.error else colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One-line text prompt with live validation: [errorFor] returns an error string or `null`. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    errorFor: @Composable (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val error = errorFor(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = label?.let { { Text(it) } },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = error == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun PasswordDialog(
    title: String,
    wrong: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.label_password)) },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) ({ Text(stringResource(R.string.error_wrong_password)) }) else null,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconAction(
                        glyph = if (visible) Fa.EyeSlash else Fa.Eye,
                        contentDescription = stringResource(R.string.action_show_password),
                        onClick = { visible = !visible },
                    )
                },
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toCharArray()) }, enabled = value.isNotEmpty()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
