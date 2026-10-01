package com.feiyu.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.R
import com.feiyu.notes.app
import com.feiyu.notes.ai.*
import com.feiyu.notes.settings.ApiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ModelChoiceFields(choice: ModelChoice, onChange: (ModelChoice) -> Unit, models: List<AiModel>, enabled: Boolean = true) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(value = choice.model, onValueChange = { onChange(choice.copy(model = it)) },
        enabled = enabled, singleLine = true, label = { Text(context.getString(R.string.model_id)) },
        modifier = Modifier.fillMaxWidth().testTag("model-id"))
    Box {
        TextButton(enabled = enabled && models.isNotEmpty(), onClick = { menu = true }) { Text(context.getString(R.string.available_models)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            models.forEach { model -> DropdownMenuItem(text = { Text("${model.name}\n${model.id}") }, onClick = {
                onChange(ModelChoice(model.id, effortLevels(model).first()))
                menu = false
            }) }
        }
    }
    val selected = models.firstOrNull { it.id == choice.model }
    val levels = effortLevels(selected)
    // A model switch or new provider metadata can leave a tier the model no longer offers.
    LaunchedEffect(levels, choice.effort) { if (choice.effort !in levels) onChange(choice.copy(effort = levels.first())) }
    TextButton(enabled = enabled, onClick = { expanded = !expanded }, modifier = Modifier.testTag("effort-toggle")) {
        Text(context.getString(R.string.reasoning_effort, choice.effort.replaceFirstChar { it.uppercase() }))
    }
    if (expanded && levels.size > 1) {
        Slider(value = levels.indexOf(choice.effort).coerceAtLeast(0).toFloat(),
            onValueChange = { onChange(choice.copy(effort = levels[it.roundToInt().coerceIn(levels.indices)])) },
            valueRange = 0f..levels.lastIndex.toFloat(), steps = (levels.size - 2).coerceAtLeast(0), enabled = enabled,
            modifier = Modifier.testTag("effort-slider"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            levels.forEach { Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (selected != null && selected.inputModalities.isNotEmpty() && "image" !in selected.inputModalities)
        Text(context.getString(R.string.text_only_model), style = MaterialTheme.typography.bodySmall)
}

/** Provider-listed tiers in provider order, else the official low/high/max. */
internal fun effortLevels(model: AiModel?): List<String> = model?.efforts.orEmpty().distinct().ifEmpty { AiDefaults.EFFORTS }

@Composable
fun ConnectionTest(settings: ApiSettings, typedKey: String? = null, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = enabled && !busy, modifier = Modifier.testTag("test-connection"), onClick = {
        scope.launch {
            busy = true; result = null
            try {
                val models = settings.refreshModels(typedKey)
                result = context.getString(R.string.connection_ok, models.size)
            } catch (e: TimeoutCancellationException) {
                result = context.getString(R.string.connection_failed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                result = context.getString(when (e) {
                    is AiError.MissingKey -> R.string.key_missing
                    is AiError.Auth -> R.string.connection_auth_failed
                    else -> R.string.connection_failed
                })
            } finally { busy = false }
        }
    }) { Text(context.getString(if (busy) R.string.testing_connection else R.string.test_connection)) }
    result?.let { Text(it, Modifier.testTag("connection-result"), style = MaterialTheme.typography.bodySmall) }
    Text(context.getString(R.string.connection_scope), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun SessionModel(lessonId: Long, busy: Boolean) {
    val context = LocalContext.current
    val app = context.app
    val revision by app.prefs.modelRevision.collectAsStateWithLifecycle()
    val models by app.apiSettings.models.collectAsStateWithLifecycle()
    val default = ModelChoice(app.apiSettings.model(), app.apiSettings.effort())
    val current = remember(lessonId, revision, default) { app.prefs.sessionModel(lessonId, default) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable(current) { mutableStateOf(current.model) }
    var effort by rememberSaveable(current) { mutableStateOf(current.effort) }
    TextButton(enabled = !busy, onClick = { model = current.model; effort = current.effort; editing = true }, modifier = Modifier.testTag("session-model")) {
        Text("${current.model} · ${current.effort.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelLarge)
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(context.getString(R.string.session_model)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            ModelChoiceFields(ModelChoice(model, effort), { model = it.model; effort = it.effort }, models, enabled = !busy)
            ConnectionTest(app.apiSettings, enabled = !busy)
            TextButton(enabled = !busy, onClick = { app.prefs.setSessionModel(lessonId, null); editing = false }) { Text(context.getString(R.string.use_default_model)) }
        } },
        confirmButton = { TextButton(enabled = model.isNotBlank() && !busy, onClick = {
            app.prefs.setSessionModel(lessonId, ModelChoice(model.trim(), effort)); editing = false
        }) { Text(context.getString(R.string.save)) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(context.getString(R.string.cancel)) } })
}
