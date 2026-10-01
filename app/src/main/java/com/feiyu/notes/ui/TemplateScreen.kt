package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.Template
import kotlinx.coroutines.launch

/** Explanation templates: a name plus instruction text appended after built-in instructions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(store: NotebookStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val templates by rememberStoreValue(store) { listTemplates() }
    val scope = rememberCoroutineScope()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) } // 0 = new
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.templates)) },
                navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
                actions = { TextButton(onClick = { editingId = 0 }) { Text(context.getString(R.string.create)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (templates?.isEmpty() == true) item {
                Text(context.getString(R.string.templates_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            items(templates.orEmpty(), key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = {
                        val origin = when {
                            t.source == Template.BUILTIN_GUIDED -> context.getString(R.string.template_builtin) + " · "
                            t.isSkill -> context.getString(R.string.template_skill, t.source!!.removePrefix("https://raw.githubusercontent.com/")) + "\n"
                            else -> ""
                        }
                        Text(origin + t.instruction.take(60))
                    },
                    trailingContent = { TextButton(onClick = { deletingId = t.id }) { Text(context.getString(R.string.delete)) } },
                    modifier = Modifier.clickable { editingId = t.id },
                )
            }
        }
    }

    editingId?.let { id ->
        val existing = templates?.firstOrNull { it.id == id }
        var instruction by rememberSaveable(id) { mutableStateOf(existing?.instruction.orEmpty()) }
        TextInputDialog(
            title = if (id == 0L) context.getString(R.string.new_template) else context.getString(R.string.edit_template),
            initial = existing?.name.orEmpty(),
            label = context.getString(R.string.name),
            onConfirm = { name ->
                if (instruction.isNotBlank()) scope.launch {
                    store.saveTemplate(Template(existing?.id ?: 0, name, instruction.trim(), existing?.createdAt ?: 0))
                }
            },
            onDismiss = { editingId = null },
            extra = {
                Column {
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text(context.getString(R.string.instruction)) },
                        minLines = 4,
                        modifier = Modifier.padding(top = 8.dp).testTag("template-instruction"),
                    )
                }
            },
        )
    }
    deletingId?.let { id ->
        ConfirmDialog(
            title = context.getString(R.string.delete_template_title),
            text = context.getString(R.string.delete_template_body),
            onConfirm = { scope.launch { store.deleteTemplate(id) } },
            onDismiss = { deletingId = null },
        )
    }
}
