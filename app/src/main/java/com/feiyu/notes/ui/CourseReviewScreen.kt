package com.feiyu.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.ReviewRecord
import com.feiyu.notes.data.ReviewStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseReviewScreen(
    store: NotebookStore,
    notebookId: Long,
    onBack: () -> Unit,
    onNavigateToSource: (lessonId: Long, entryId: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebook by rememberStoreValue(store, notebookId) { getNotebook(notebookId) }
    val records by rememberStoreValue(store, notebookId) { listReviewRecords(notebookId) }

    var statusFilter by rememberSaveable { mutableStateOf<ReviewStatus?>(null) }
    var addingRecord by rememberSaveable { mutableStateOf(false) }
    var editingRecord by remember { mutableStateOf<ReviewRecord?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val filteredRecords = remember(records, statusFilter) {
        val list = records.orEmpty()
        if (statusFilter == null) list else list.filter { it.status == statusFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(context.getString(R.string.course_review), style = MaterialTheme.typography.titleMedium)
                        notebook?.name?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack)
                },
                actions = {
                    TextButton(onClick = { addingRecord = true }) {
                        Text(context.getString(R.string.new_review_record))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status filters
            FlowRow(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    label = { Text(context.getString(R.string.filter_all)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.PENDING,
                    onClick = { statusFilter = ReviewStatus.PENDING },
                    label = { Text(context.getString(R.string.status_pending)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.CONFUSED,
                    onClick = { statusFilter = ReviewStatus.CONFUSED },
                    label = { Text(context.getString(R.string.status_confused)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.UNDERSTOOD,
                    onClick = { statusFilter = ReviewStatus.UNDERSTOOD },
                    label = { Text(context.getString(R.string.status_understood)) },
                )
            }

            if (records?.isEmpty() == true) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        context.getString(R.string.review_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .widthIn(max = 840.dp)
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredRecords, key = { it.id }) { record ->
                        ReviewRecordCard(
                            record = record,
                            onStatusChange = { newStatus ->
                                scope.launch { store.setReviewStatus(notebookId, record.id, newStatus) }
                            },
                            onEdit = { editingRecord = record },
                            onDelete = { deletingId = record.id },
                            onJumpToSource = {
                                val sourceId = record.sourceEntryId ?: return@ReviewRecordCard
                                scope.launch {
                                    val entry = store.getEntry(sourceId)
                                    if (entry != null) {
                                        onNavigateToSource(entry.lessonId, entry.id)
                                    } else {
                                        notice = context.getString(R.string.source_not_found)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (addingRecord) {
        ReviewRecordEditDialog(
            title = context.getString(R.string.new_review_record),
            initialTopic = "",
            initialNotes = "",
            sourceEntryId = null,
            onConfirm = { topic, notes ->
                scope.launch {
                    store.insertReviewRecord(
                        ReviewRecord(
                            id = 0,
                            notebookId = notebookId,
                            topic = topic,
                            notes = notes,
                            sourceEntryId = null,
                            sourceDeleted = false,
                            status = ReviewStatus.PENDING,
                        )
                    )
                }
            },
            onDismiss = { addingRecord = false },
        )
    }

    editingRecord?.let { record ->
        ReviewRecordEditDialog(
            title = context.getString(R.string.edit_review_record),
            initialTopic = record.topic,
            initialNotes = record.notes,
            sourceEntryId = record.sourceEntryId,
            onConfirm = { topic, notes ->
                scope.launch {
                    store.updateReviewRecord(notebookId, record.id, topic, notes)
                }
            },
            onDismiss = { editingRecord = null },
        )
    }

    deletingId?.let { id ->
        ConfirmDialog(
            title = context.getString(R.string.delete_review_record),
            text = context.getString(R.string.delete_review_confirm),
            onConfirm = { scope.launch { store.deleteReviewRecord(notebookId, id) } },
            onDismiss = { deletingId = null },
        )
    }

    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(context.getString(R.string.notice)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(context.getString(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun ReviewRecordCard(
    record: ReviewRecord,
    onStatusChange: (ReviewStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onJumpToSource: () -> Unit,
) {
    val context = LocalContext.current
    var statusMenu by remember { mutableStateOf(false) }

    Card(
        Modifier
            .fillMaxWidth()
            .testTag("review-record-${record.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: Topic and Status Chip
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.topic,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    val statusText = when (record.status) {
                        ReviewStatus.PENDING -> context.getString(R.string.status_pending)
                        ReviewStatus.UNDERSTOOD -> context.getString(R.string.status_understood)
                        ReviewStatus.CONFUSED -> context.getString(R.string.status_confused)
                    }
                    val statusColor = when (record.status) {
                        ReviewStatus.PENDING -> MaterialTheme.colorScheme.primary
                        ReviewStatus.UNDERSTOOD -> MaterialTheme.colorScheme.tertiary
                        ReviewStatus.CONFUSED -> MaterialTheme.colorScheme.error
                    }

                    SuggestionChip(
                        onClick = { statusMenu = true },
                        label = { Text(statusText) },
                        colors = SuggestionChipDefaults.suggestionChipColors(labelColor = statusColor),
                    )

                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_pending)) },
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.PENDING) },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_confused)) },
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.CONFUSED) },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_understood)) },
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.UNDERSTOOD) },
                        )
                    }
                }
            }

            // Notes body
            if (record.notes.isNotBlank()) {
                Text(
                    text = record.notes,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Source indicator and actions
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    record.sourceDeleted -> {
                        Text(
                            context.getString(R.string.review_source_deleted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    record.sourceEntryId != null -> {
                        TextButton(onClick = onJumpToSource) {
                            Text(context.getString(R.string.review_source, record.sourceEntryId) + " · " + context.getString(R.string.back_to_source))
                        }
                    }
                    else -> {
                        Text(
                            context.getString(R.string.review_manual),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) {
                        Text(context.getString(R.string.rename))
                    }
                    TextButton(onClick = onDelete) {
                        Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewRecordEditDialog(
    title: String,
    initialTopic: String,
    initialNotes: String,
    sourceEntryId: Long?,
    onConfirm: (topic: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var topic by rememberSaveable { mutableStateOf(initialTopic) }
    var notes by rememberSaveable { mutableStateOf(initialNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sourceEntryId != null) {
                    Text(
                        context.getString(R.string.review_source, sourceEntryId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text(context.getString(R.string.review_topic)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("review-topic-input"),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(context.getString(R.string.review_notes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("review-notes-input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = topic.isNotBlank(),
                onClick = {
                    onConfirm(topic.trim(), notes.trim())
                    onDismiss()
                },
            ) {
                Text(context.getString(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        },
    )
}
