package com.feiyu.notes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.R
import com.feiyu.notes.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Stable ordering: persisted indices refer to this list, never to generated Android resource IDs.
internal val WhalePortraits = listOf(
    R.drawable.whale_01_01,
    R.drawable.whale_01_02,
    R.drawable.whale_01_03,
    R.drawable.whale_01_04,
    R.drawable.whale_01_05,
    R.drawable.whale_01_06,
    R.drawable.whale_01_07,
    R.drawable.whale_01_08,
    R.drawable.whale_01_09,
    R.drawable.whale_02_01,
    R.drawable.whale_02_02,
    R.drawable.whale_02_03,
    R.drawable.whale_02_04,
    R.drawable.whale_02_05,
    R.drawable.whale_02_06,
    R.drawable.whale_02_07,
    R.drawable.whale_02_08,
    R.drawable.whale_02_09,
    R.drawable.whale_02_10,
    R.drawable.whale_02_11,
    R.drawable.whale_02_12
)

@Composable
fun WhaleAvatar(lessonId: Long, modifier: Modifier = Modifier.size(40.dp)) {
    val context = LocalContext.current
    val app = context.app
    val revision by app.avatars.revision.collectAsStateWithLifecycle()
    val portrait = remember(lessonId, app.prefs) { WhalePortraits[app.prefs.avatarIndex(lessonId, WhalePortraits.size)] }
    val custom by produceState<ImageBitmap?>(null, app.avatars, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(app.avatars.file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val description = context.getString(R.string.deepseek_avatar)
    val shape = modifier.clip(CircleShape)
    if (custom != null) Image(custom!!, description, shape, contentScale = ContentScale.Crop)
    else Image(painterResource(portrait), description, shape, contentScale = ContentScale.Crop)
}

@Composable
fun ActionIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun WelcomeCard(compact: Boolean = false) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.whale_02_01), null, Modifier.size(if (compact) 64.dp else 88.dp).clip(CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(context.getString(R.string.welcome_title), style = MaterialTheme.typography.titleMedium)
                Text(context.getString(if (compact) R.string.welcome_short else R.string.welcome_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
