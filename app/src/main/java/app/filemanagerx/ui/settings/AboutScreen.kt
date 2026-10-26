package app.filemanagerx.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.filemanagerx.BuildConfig
import app.filemanagerx.R
import app.filemanagerx.ui.components.Fa
import app.filemanagerx.ui.components.FaIcon
import app.filemanagerx.ui.components.IconAction
import app.filemanagerx.ui.components.SectionLabel
import app.filemanagerx.ui.theme.JetBrainsMono

private data class License(val title: String, val asset: String)

private val bundledLicenses = listOf(
    License("Font Awesome Free", "font-awesome-free.txt"),
    License("Manrope", "manrope-ofl.txt"),
    License("JetBrains Mono", "jetbrains-mono-ofl.txt"),
)

private val libraries = listOf(
    "libsu · Apache-2.0",
    "Apache Commons Compress · Apache-2.0",
    "XZ for Java · public domain",
    "zip4j · Apache-2.0",
    "junrar · UnRAR license",
    "AndroidX, Jetpack Compose, Kotlin · Apache-2.0",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Fa.ArrowLeft, stringResource(R.string.action_back), onBack)
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(88.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { FaIcon(Fa.FolderOpen, size = 40.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.about_free),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.about_libraries)) }
        items(libraries.size) { index ->
            Text(
                libraries[index],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
            )
        }
        item { SectionLabel(stringResource(R.string.about_licenses)) }
        items(bundledLicenses.size) { index -> LicenseCard(bundledLicenses[index]) }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun LicenseCard(license: License) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val text = remember(expanded) {
        if (!expanded) "" else runCatching {
            context.assets.open("licenses/${license.asset}").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize(),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(license.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                FaIcon(if (expanded) Fa.ArrowUp else Fa.ArrowDown, size = 14.dp, tint = MaterialTheme.colorScheme.outline)
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono))
            }
        }
    }
}
