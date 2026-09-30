package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Mastery
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.data.Template
import com.feiyu.notes.study.StudyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: StudyViewModel,
    focusEntryId: Long?,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenArchived: () -> Unit,
) {
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var summarizing by rememberSaveable { mutableStateOf(false) }
    var retryTemplateFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val d = data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.lesson?.title ?: "", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
                actions = {
                    TextButton(enabled = d?.lesson != null && status.running == null, onClick = { summarizing = true }) { Text(context.getString(R.string.summarize)) }
                    TextButton(enabled = d?.lesson != null, onClick = onOpenArchived) { Text(context.getString(R.string.archived)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                d == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(context.getString(R.string.loading)) }
                d.lesson == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(context.getString(R.string.lesson_deleted)) }
                else -> {
                    val practice = d.notebook?.kind == NotebookKind.PRACTICE
                    val notes = d.entries.filter { it.kind == EntryKind.NOTE }
                    val rows = threadRows(d.entries, archived = false)
                    val listState = rememberLazyListState()
                    LaunchedEffect(focusEntryId, rows.size) {
                        val index = rows.indexOfFirst { it.first.id == focusEntryId }
                        // Jump to a requested source entry, otherwise follow the newest entry.
                        if (index >= 0) listState.scrollToItem(index + notes.size + 1)
                        else if (rows.isNotEmpty()) listState.scrollToItem(notes.size + rows.size)
                    }
                    // A freshly summarized note sits at the top; bring it into view.
                    var seenNotes by remember { mutableStateOf(notes.size) }
                    LaunchedEffect(notes.size) {
                        if (notes.size > seenNotes) listState.scrollToItem(0)
                        seenNotes = notes.size
                    }
                    LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().testTag("chat-list"), state = listState) {
                        item(key = "notes") {
                            if (rows.isEmpty() && notes.isEmpty()) WelcomeCard()
                            if (notes.isNotEmpty()) Text(context.getString(R.string.lesson_notes), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
                        }
                        items(notes, key = { "note-${it.id}" }) { note ->
                            ListItem(
                                headlineContent = { Text(note.text.lineSequence().firstOrNull().orEmpty().take(40)) },
                                supportingContent = { Text(context.getString(R.string.note_number, note.id)) },
                                modifier = Modifier.clickable { onOpenNote(note.id) },
                            )
                        }
                        items(rows, key = { it.first.id }) { (entry, depth) ->
                            EntryCard(
                                entry = entry,
                                depth = depth,
                                all = d.entries,
                                templates = d.templates,
                                practice = practice,
                                highlighted = entry.id == focusEntryId,
                                vm = vm,
                                readOnly = false,
                                onRetry = { user ->
                                    if (user.templateId != null && d.templates.none { it.id == user.templateId }) retryTemplateFor = user.id
                                    else vm.retry(user.id)
                                },
                            )
                        }
                    }
                    StatusBar(status.running?.lessonId == vm.lessonId, status.running != null, status.message, notice, vm)
                    Composer(vm, practice, d.referenceNotes, d.templates, d.notebook?.defaultTemplateId, busy = status.running != null)
                }
            }
        }
    }

    if (summarizing && d != null) ChoiceDialog(
        title = context.getString(R.string.summary_template),
        options = d.templates.map { it.id to it.name },
        selected = null,
        noneLabel = context.getString(R.string.summary_default),
        confirmLabel = context.getString(R.string.start_summary),
        onConfirm = { vm.summarize(it) },
        onDismiss = { summarizing = false },
    )
    retryTemplateFor?.let { userId ->
        ChoiceDialog(
            title = context.getString(R.string.retry_template),
            options = d?.templates.orEmpty().map { it.id to it.name },
            selected = null,
            noneLabel = context.getString(R.string.no_template),
            confirmLabel = context.getString(R.string.retry),
            onConfirm = { vm.retry(userId, replaceTemplate = true, templateId = it) },
            onDismiss = { retryTemplateFor = null },
        )
    }
}

/** Depth-first rows of non-note entries: roots (archived or not) followed by their replies. */
fun threadRows(entries: List<Entry>, archived: Boolean): List<Pair<Entry, Int>> {
    val children = entries.filter { it.kind != EntryKind.NOTE }.groupBy { it.parentEntryId }
    val out = mutableListOf<Pair<Entry, Int>>()
    fun visit(e: Entry, depth: Int) {
        out += e to depth
        children[e.id].orEmpty().forEach { visit(it, depth + 1) }
    }
    children[null].orEmpty().filter { it.isRoot && it.archived == archived }.forEach { visit(it, 0) }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryCard(
    entry: Entry,
    depth: Int,
    all: List<Entry>,
    templates: List<Template>,
    practice: Boolean,
    highlighted: Boolean,
    vm: StudyViewModel,
    readOnly: Boolean,
    onRetry: (Entry) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isUser = entry.kind == EntryKind.USER
    Surface(
        color = when {
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            isUser -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()
            .padding(start = 12.dp + (depth.coerceAtMost(2) * 8).dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isUser) WhaleAvatar(vm.lessonId)
                Text(label(context, entry), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (entry.isRoot && practice && entry.mastery != null) FilterChip(
                    selected = entry.mastery == Mastery.MASTERED,
                    onClick = { if (!readOnly) vm.setMastery(entry.id, if (entry.mastery == Mastery.MASTERED) Mastery.UNMASTERED else Mastery.MASTERED) },
                    label = { Text(if (entry.mastery == Mastery.MASTERED) context.getString(R.string.mastered) else context.getString(R.string.unmastered)) },
                )
                if (entry.isRoot) Box {
                    ActionIcon(R.drawable.ic_more, context.getString(R.string.more_options), { menu = true }, Modifier.testTag("thread-menu"))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (entry.archived) context.getString(R.string.restore) else context.getString(R.string.archive)) },
                            onClick = { menu = false; vm.setArchived(entry.id, !entry.archived) },
                        )
                        DropdownMenuItem(text = { Text(context.getString(R.string.delete_thread)) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            if (entry.text.isNotBlank()) SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodyLarge) }
            vm.photoFile(entry)?.let { PhotoThumb(it) }
            if (isUser) UserMeta(entry, all, templates)
            if (entry.kind == EntryKind.ASSISTANT && !readOnly) AssistantActions(entry, all, practice, vm, onRetry)
        }
    }
    if (confirmDelete) ConfirmDialog(
        title = context.getString(R.string.delete_thread_title),
        text = context.getString(R.string.delete_thread_body),
        onConfirm = { vm.deleteThread(entry.id) },
        onDismiss = { confirmDelete = false },
    )
}

private fun label(context: android.content.Context, entry: Entry): String = when (entry.kind) {
    EntryKind.USER -> when (entry.action) {
        EntryAction.EXPAND -> context.getString(R.string.expand_number, entry.id)
        EntryAction.MISTAKE -> context.getString(R.string.solution_number, entry.id)
        else -> if (entry.isRoot) context.getString(R.string.question_number, entry.id) else context.getString(R.string.follow_number, entry.id)
    }
    EntryKind.ASSISTANT -> when (entry.state) {
        EntryState.PENDING -> context.getString(R.string.reply_pending)
        EntryState.FAILED -> context.getString(R.string.reply_failed)
        EntryState.CANCELLED -> context.getString(R.string.reply_cancelled)
        EntryState.INTERRUPTED -> context.getString(R.string.reply_interrupted)
        else -> context.getString(R.string.reply_number, entry.id)
    }
    EntryKind.NOTE -> context.getString(R.string.note_number, entry.id)
}

@Composable
private fun UserMeta(entry: Entry, all: List<Entry>, templates: List<Template>) {
    val context = LocalContext.current
    val parts = buildList {
        if (entry.attachedImageEntryIds.isNotEmpty()) add(context.getString(R.string.attached_count, entry.attachedImageEntryIds.size))
        entry.sourceEntryIds.firstOrNull()?.let { add(context.getString(R.string.reference_number, it)) }
        entry.templateId?.let { id -> add(templates.firstOrNull { it.id == id }?.let { context.getString(R.string.template_name, it.name) } ?: context.getString(R.string.template_deleted)) }
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantActions(entry: Entry, all: List<Entry>, practice: Boolean, vm: StudyViewModel, onRetry: (Entry) -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (entry.state) {
            EntryState.COMPLETE -> {
                OutlinedButton(onClick = { vm.setTarget(entry.id, EntryAction.ASK) }) { Text(context.getString(R.string.follow_up)) }
                OutlinedButton(onClick = { vm.setTarget(entry.id, EntryAction.EXPAND) }) { Text(context.getString(R.string.expand)) }
                if (practice) OutlinedButton(onClick = { vm.setTarget(entry.id, EntryAction.MISTAKE) }) { Text(context.getString(R.string.mistake)) }
            }
            EntryState.FAILED, EntryState.CANCELLED, EntryState.INTERRUPTED -> {
                val user = all.firstOrNull { it.id == entry.parentEntryId }
                if (user != null) OutlinedButton(onClick = { onRetry(user) }) { Text(context.getString(R.string.retry)) }
            }
            else -> Unit
        }
    }
}

@Composable
private fun StatusBar(runningHere: Boolean, runningAnywhere: Boolean, message: String?, notice: String?, vm: StudyViewModel) {
    val context = LocalContext.current
    val text = when {
        runningHere -> context.getString(R.string.generating)
        runningAnywhere -> context.getString(R.string.generating_elsewhere)
        else -> notice ?: message
    } ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (runningAnywhere) TextButton(onClick = vm::cancel) { Text(context.getString(R.string.cancel)) }
            else if (notice != null) TextButton(onClick = vm::dismissNotice) { Text(context.getString(R.string.dismiss)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    vm: StudyViewModel,
    practice: Boolean,
    referenceNotes: List<Entry>,
    templates: List<Template>,
    defaultTemplateId: Long?,
    busy: Boolean,
) {
    val context = LocalContext.current
    val draft by vm.draft.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val parentId by vm.parentId.collectAsStateWithLifecycle()
    val photoName by vm.photoName.collectAsStateWithLifecycle()
    val attached by vm.attached.collectAsStateWithLifecycle()
    val referenceId by vm.referenceId.collectAsStateWithLifecycle()
    val templateOverride by vm.templateOverride.collectAsStateWithLifecycle()
    val templateChoice by vm.templateChoice.collectAsStateWithLifecycle()
    var showAttach by rememberSaveable { mutableStateOf(false) }
    var pickReference by rememberSaveable { mutableStateOf(false) }
    var pickTemplate by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.onCaptureResult(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importPhoto) }

    HorizontalDivider()
    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (parentId > 0) {
            val mode = when (EntryAction.valueOf(action)) {
                EntryAction.EXPAND -> context.getString(R.string.expand)
                EntryAction.MISTAKE -> context.getString(R.string.mistake_hint)
                EntryAction.ASK -> context.getString(R.string.follow_up)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.reply_target, mode, parentId), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { vm.setTarget(null) }) { Text(context.getString(R.string.cancel_reply)) }
            }
            val chain = vm.chainPhotos()
            if (chain.isNotEmpty()) {
                TextButton(onClick = { showAttach = !showAttach }) {
                    Text(if (attached.isEmpty()) context.getString(R.string.attach_original) else context.getString(R.string.selected_photos, attached.size))
                }
                if (showAttach) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chain.forEach { e ->
                        FilterChip(
                            selected = e.id in attached,
                            onClick = { vm.toggleAttached(e.id) },
                            label = { Text("#${e.id} ${e.text.take(8).ifBlank { context.getString(R.string.photo) }}") },
                        )
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val refName = referenceNotes.firstOrNull { it.id == referenceId }?.let { context.getString(R.string.reference_selected, it.id) } ?: context.getString(R.string.reference_none)
            TextButton(onClick = { pickReference = true }) { Text(refName) }
            val effective = if (templateOverride) templateChoice.takeIf { it > 0 } else defaultTemplateId
            val tName = effective?.let { id -> templates.firstOrNull { it.id == id }?.name } ?: context.getString(R.string.none)
            TextButton(onClick = { pickTemplate = true }) { Text(context.getString(if (!templateOverride && effective != null) R.string.template_default_name else R.string.template_name, tName)) }
        }
        // Read the state here so this scope recomposes when a photo arrives.
        photoName?.let { vm.pendingPhotoFile() }?.let { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhotoThumb(file, Modifier.weight(1f, fill = false))
                TextButton(onClick = vm::removePhoto) { Text(context.getString(R.string.remove)) }
            }
        }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = draft, onValueChange = vm::setDraft,
            label = { Text(context.getString(if (practice && action == EntryAction.MISTAKE.name) R.string.my_solution else R.string.question_hint)) },
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag("composer-input"),
            shape = MaterialTheme.shapes.medium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                cameraError = null
                try { camera.launch(vm.newCaptureUri()) }
                catch (e: ActivityNotFoundException) {
                    vm.onCaptureResult(false)
                    cameraError = context.getString(R.string.camera_unavailable)
                }
            }) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_camera), null, Modifier.size(20.dp))
                Text(context.getString(if (photoName == null) R.string.camera else R.string.retake), Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_gallery), null, Modifier.size(20.dp))
                Text(context.getString(R.string.gallery), Modifier.padding(start = 8.dp))
            }
            Button(enabled = !busy && (draft.isNotBlank() || photoName != null || action == EntryAction.EXPAND.name),
                onClick = { vm.send() }, modifier = Modifier.testTag("send")) {
                Text(context.getString(R.string.send))
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_send), null, Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }

    if (pickReference) ChoiceDialog(
        title = context.getString(R.string.choose_reference),
        options = referenceNotes.map { it.id to "#${it.id} ${it.text.lineSequence().firstOrNull().orEmpty().take(24)}" },
        selected = referenceId.takeIf { it > 0 },
        noneLabel = context.getString(R.string.no_reference),
        confirmLabel = context.getString(R.string.confirm),
        onConfirm = vm::setReference,
        onDismiss = { pickReference = false },
    )
    if (pickTemplate) ChoiceDialog(
        title = context.getString(R.string.choose_template),
        options = templates.map { it.id to it.name },
        selected = if (templateOverride) templateChoice.takeIf { it > 0 } else defaultTemplateId,
        noneLabel = context.getString(R.string.no_template),
        confirmLabel = context.getString(R.string.confirm),
        onConfirm = vm::setTemplate,
        onDismiss = { pickTemplate = false },
    )
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<Long, String>>,
    selected: Long?,
    noneLabel: String,
    confirmLabel: String,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var choice by rememberSaveable { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = choice == null, onClick = { choice = null }, label = { Text(noneLabel) })
                options.forEach { (id, label) ->
                    FilterChip(selected = choice == id, onClick = { choice = id }, label = { Text(label) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}
