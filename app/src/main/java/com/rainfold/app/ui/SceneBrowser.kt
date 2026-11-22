package com.rainfold.app.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rainfold.app.R
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.domain.SoundScene
import com.rainfold.app.player.sceneTitle
import com.rainfold.app.player.descriptionResource

class SceneActions(
    val select: (String, Boolean) -> Unit,
    val save: (String, ScenePurpose) -> Unit,
    val rename: (String, String) -> Unit,
    val copy: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val favorite: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SceneBrowser(
    state: PlaybackScreenState,
    actions: SceneActions,
    onDismiss: () -> Unit,
    onPlaybackToggle: () -> Unit,
) {
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = RainfoldTokens.size24),
                verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size16)) {
                item {
                    Text(stringResource(R.string.scenes), style = MaterialTheme.typography.headlineSmall)
                    FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text(stringResource(R.string.favorites)) })
                    if (favoritesOnly && state.library.favorites.isEmpty()) Text(stringResource(R.string.no_favorites))
                    if (state.library.custom.size >= SceneLibrary.MAX_CUSTOM_SCENES) Text(stringResource(R.string.scene_limit))
                }
                items(state.library.scenes.filter { !favoritesOnly || it.id in state.library.favorites }, key = { it.id }) { scene ->
                    SceneCard(scene, state, actions, onSelected = { play ->
                        actions.select(scene.id, play)
                        onDismiss()
                    })
                }
            }
            PlaybackButton(state, onPlaybackToggle, Modifier.padding(RainfoldTokens.size16))
        }
    }
}

@Composable
private fun SceneCard(scene: SoundScene, state: PlaybackScreenState, actions: SceneActions, onSelected: (Boolean) -> Unit) {
    val title = LocalContext.current.sceneTitle(scene)
    val favorite = scene.id in state.library.favorites
    val favoriteLabel = stringResource(if (favorite) R.string.unfavorite_scene else R.string.favorite_scene, title)
    val playLabel = stringResource(R.string.play_scene, title)
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val canSave = state.library.custom.size < SceneLibrary.MAX_CUSTOM_SCENES
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(RainfoldTokens.size16), verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size8)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(purposeLabel(scene.purpose), style = MaterialTheme.typography.labelLarge)
            if (state.library.selectedId == scene.id) Text(stringResource(R.string.noise_selected))
            scene.descriptionResource()?.let { Text(stringResource(it)) }
            FilterChip(selected = favorite, onClick = { actions.favorite(scene.id) }, enabled = state.connected,
                label = { Text(stringResource(R.string.favorites)) },
                modifier = Modifier.semantics { contentDescription = favoriteLabel })
            Button(onClick = { onSelected(true) }, enabled = state.connected,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = playLabel }) {
                Text(stringResource(R.string.play))
            }
            OutlinedButton(onClick = { onSelected(false) }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.choose_scene))
            }
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { menu = true }, enabled = state.connected) { Text(stringResource(R.string.scene_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.copy_scene)) }, enabled = canSave,
                        onClick = { menu = false; duplicate = true })
                    if (!scene.builtIn) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; delete = true })
                    }
                }
            }
        }
    }
    if (rename || duplicate) {
        SceneNameDialog(
            title = stringResource(if (rename) R.string.rename else R.string.copy_scene),
            initialName = if (rename) title else stringResource(R.string.copy_scene_name, title).take(SceneLibrary.MAX_NAME_LENGTH),
            initialPurpose = scene.purpose, showPurpose = false,
            onDismiss = { rename = false; duplicate = false },
            onSave = { name, _ ->
                if (rename) actions.rename(scene.id, name) else actions.copy(scene.id, name)
                rename = false; duplicate = false
            },
        )
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false },
        title = { Text(stringResource(R.string.delete_scene_title)) },
        text = { Text(stringResource(R.string.delete_scene_message, title)) },
        confirmButton = { TextButton(onClick = { actions.delete(scene.id); delete = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SceneNameDialog(
    title: String,
    initialName: String,
    initialPurpose: ScenePurpose,
    showPurpose: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String, ScenePurpose) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var purpose by rememberSaveable { mutableStateOf(initialPurpose) }
    val valid = name.trim().isNotEmpty() && name.trim().length <= SceneLibrary.MAX_NAME_LENGTH && name.none(Char::isISOControl)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= SceneLibrary.MAX_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.scene_name)) }, singleLine = true)
                if (showPurpose) ScenePurpose.entries.forEach { choice ->
                    FilterChip(selected = purpose == choice, onClick = { purpose = choice }, label = { Text(purposeLabel(choice)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), purpose) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun purposeLabel(purpose: ScenePurpose): String = stringResource(
    if (purpose == ScenePurpose.SLEEP) R.string.purpose_sleep else R.string.purpose_focus
)