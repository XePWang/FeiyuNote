package com.feiyu.notes.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.app
import com.feiyu.notes.ai.AiDefaults
import com.feiyu.notes.settings.ApiSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: ApiSettings, onOpenTemplates: () -> Unit, navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(settings.hasKey()) }
    // Credentials stay in memory; never put a typed key in saved-instance-state.
    var newKey by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(settings.model()) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var avatarBusy by remember { mutableStateOf(false) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }
    var showLicense by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            avatarBusy = true
            avatarMessage = runCatching { app.avatars.import(context.contentResolver, uri) }
                .fold({ context.getString(R.string.avatar_saved) }, { context.getString(R.string.avatar_failed) })
            avatarBusy = false
        }
    }
    fun openPlatform() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }
            .onFailure { error = context.getString(R.string.browser_unavailable) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(context.getString(R.string.settings)) }, navigationIcon = navigationIcon) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OutlinedButton(onClick = onOpenTemplates, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.templates)) }
                SettingsSection(context.getString(R.string.deepseek_connection)) {
                    Text(context.getString(if (hasKey) R.string.key_configured else R.string.key_missing), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it; saved = false; error = null },
                        label = { Text(if (hasKey) context.getString(R.string.replace_key) else "API Key") },
                        supportingText = { Text(context.getString(R.string.key_private)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("api-key"),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; saved = false },
                        label = { Text(context.getString(R.string.model_name, AiDefaults.MODEL)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching { settings.save(newKey.takeIf { it.isNotBlank() }, model.ifBlank { AiDefaults.MODEL }) }
                                .onSuccess {
                                    newKey = ""; hasKey = settings.hasKey(); model = settings.model(); saved = true; error = null
                                }.onFailure { error = context.getString(R.string.save_failed) }
                        }) { Text(context.getString(R.string.save)) }
                        if (hasKey) TextButton(onClick = {
                            runCatching { settings.save("", model) }.onSuccess { hasKey = false; saved = false }
                                .onFailure { error = context.getString(R.string.save_failed) }
                        }) { Text(context.getString(R.string.clear_key)) }
                    }
                    if (saved) Text(context.getString(R.string.saved), color = MaterialTheme.colorScheme.primary)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                    Text(context.getString(R.string.api_guide_title), style = MaterialTheme.typography.titleSmall)
                    Text(context.getString(R.string.api_guide), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = ::openPlatform) { Text(context.getString(R.string.open_deepseek)) }
                }
                SettingsSection(context.getString(R.string.deepseek_avatar)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WhaleAvatar(app.prefs.lastLesson?.second ?: 0, Modifier.size(64.dp))
                        Text(context.getString(R.string.avatar_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !avatarBusy, onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, modifier = Modifier.testTag("choose-avatar")) { Text(context.getString(R.string.choose_avatar)) }
                        TextButton(enabled = !avatarBusy, onClick = {
                            app.avatars.reset(); avatarMessage = context.getString(R.string.avatar_reset)
                        }, modifier = Modifier.testTag("reset-avatar")) { Text(context.getString(R.string.random_avatar)) }
                    }
                    if (avatarBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    avatarMessage?.let { Text(it, Modifier.testTag("avatar-status"), style = MaterialTheme.typography.bodySmall) }
                }
                SettingsSection(context.getString(R.string.about)) {
                    Text(context.getString(R.string.language_auto), style = MaterialTheme.typography.bodyMedium)
                    Text(context.getString(R.string.art_credit), style = MaterialTheme.typography.bodySmall)
                    Text("Noto Sans SC · SIL Open Font License 1.1", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showLicense = true }) { Text(context.getString(R.string.font_license)) }
                }
            }
        }
    }
    if (showLicense) AlertDialog(
        onDismissRequest = { showLicense = false }, title = { Text("SIL Open Font License 1.1") },
        text = {
            val license = remember { context.assets.open("licenses/NotoSansSC-OFL.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text(context.getString(R.string.close)) } },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
