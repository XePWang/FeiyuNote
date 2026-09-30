package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.feiyu.notes.data.Notebook
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.Template
import com.feiyu.notes.study.Generator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookListScreen(
    store: NotebookStore,
    generator: Generator,
    onOpen: (Notebook) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val notebooks by rememberStoreValue(store) { listNotebooks() }
    val templates by rememberStoreValue(store) { listTemplates() }
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf<NotebookKind?>(null) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val all = notebooks.orEmpty()
    val courses = all.filter { it.kind == NotebookKind.COURSE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                actions = { ActionIcon(R.drawable.ic_settings, context.getString(R.string.settings), onSettings) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item(key = "welcome") { WelcomeCard(compact = true) }
            for (kind in NotebookKind.entries) {
                val label = if (kind == NotebookKind.COURSE) context.getString(R.string.courses) else context.getString(R.string.practice_books)
                item(key = "header-$kind") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        TextButton(onClick = { creating = kind }) { Text(context.getString(if (kind == NotebookKind.COURSE) R.string.new_course else R.string.new_practice)) }
                    }
                }
                val rows = all.filter { it.kind == kind }
                if (rows.isEmpty()) item(key = "empty-$kind") {
                    Text(context.getString(R.string.notebooks_empty, label), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                items(rows, key = { it.id }) { notebook ->
                    NotebookRow(
                        notebook = notebook,
                        linkedName = notebook.linkedCourseId?.let { id -> courses.firstOrNull { it.id == id }?.name },
                        onOpen = { onOpen(notebook) },
                        onEdit = { editingId = notebook.id },
                        onDelete = { deletingId = notebook.id },
                    )
                }
            }
        }
    }

    creating?.let { kind ->
        NotebookDialog(
            title = if (kind == NotebookKind.COURSE) context.getString(R.string.new_course) else context.getString(R.string.new_practice),
            initial = Notebook(0, kind, "", null, null, 0),
            courses = courses,
            templates = templates.orEmpty(),
            onConfirm = { draft ->
                scope.launch {
                    store.createNotebook(kind, draft.name, draft.linkedCourseId)?.let { created ->
                        if (draft.defaultTemplateId != null) store.updateNotebook(created.copy(defaultTemplateId = draft.defaultTemplateId))
                    }
                }
            },
            onDismiss = { creating = null },
        )
    }
    editingId?.let { id ->
        val notebook = all.firstOrNull { it.id == id }
        if (notebook == null) editingId = null else NotebookDialog(
            title = context.getString(if (notebook.kind == NotebookKind.COURSE) R.string.edit_course else R.string.edit_practice),
            initial = notebook,
            courses = courses.filter { it.id != id },
            templates = templates.orEmpty(),
            onConfirm = { draft -> scope.launch { store.updateNotebook(draft) } },
            onDismiss = { editingId = null },
        )
    }
    deletingId?.let { id ->
        val notebook = all.firstOrNull { it.id == id }
        if (notebook == null) deletingId = null else ConfirmDialog(
            title = context.getString(R.string.delete_named, notebook.name),
            text = context.getString(R.string.delete_notebook_body),
            onConfirm = {
                generator.cancelIfAffected(notebookId = id)
                scope.launch { store.deleteNotebook(id) }
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
private fun NotebookRow(notebook: Notebook, linkedName: String?, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_book), null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(notebook.name) },
        supportingContent = linkedName?.let { { Text(context.getString(R.string.linked_course, it)) } },
        trailingContent = {
            Column {
                ActionIcon(R.drawable.ic_more, context.getString(R.string.more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(context.getString(R.string.edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(context.getString(R.string.delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onOpen),
    )
}

/** Name, optional linked course (practice books) and default template. */
@Composable
private fun NotebookDialog(
    title: String,
    initial: Notebook,
    courses: List<Notebook>,
    templates: List<Template>,
    onConfirm: (Notebook) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var linked by rememberSaveable { mutableStateOf(initial.linkedCourseId) }
    var template by rememberSaveable { mutableStateOf(initial.defaultTemplateId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(context.getString(R.string.name)) }, singleLine = true, modifier = Modifier.testTag("notebook-name"))
                if (initial.kind == NotebookKind.PRACTICE) {
                    Text(context.getString(R.string.link_course), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(options = courses.map { it.id to it.name }, selected = linked, onSelect = { linked = it })
                }
                Text(context.getString(R.string.default_template), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(options = templates.map { it.id to it.name }, selected = template, onSelect = { template = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onConfirm(initial.copy(name = name.trim(), linkedCourseId = linked, defaultTemplateId = template))
                onDismiss()
            }) { Text(context.getString(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}

/** context.getString(R.string.none) plus one chip per option; tapping the selected chip again keeps it. */
@Composable
fun ChoiceChips(options: List<Pair<Long, String>>, selected: Long?, onSelect: (Long?) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(context.getString(R.string.none)) })
        options.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
        }
    }
}
