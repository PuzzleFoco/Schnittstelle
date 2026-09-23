package com.puzzlefoco.schnittstelle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puzzlefoco.schnittstelle.media.MediaProbe
import com.puzzlefoco.schnittstelle.model.Project
import com.puzzlefoco.schnittstelle.model.TrackKind
import com.puzzlefoco.schnittstelle.ui.theme.SchnittstelleColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(vm: EditorViewModel) {
    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

    Scaffold(
        containerColor = SchnittstelleColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Schnittstelle") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SchnittstelleColors.Surface,
                    titleContentColor = SchnittstelleColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Videoschnitt auf dem Gerät",
                        color = SchnittstelleColors.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Ohne Cloud, ohne Konto. " +
                            "Belegter Speicher: ${formatBytes(vm.mediaUsageBytes)}",
                        color = SchnittstelleColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { vm.newProject() }) { Text("Neu") }
            }

            if (vm.projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Noch kein Projekt.\nTippe auf „Neu\", um zu starten.",
                        color = SchnittstelleColors.TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.projects, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            modified = dateFormat.format(Date(project.modifiedAt)),
                            onOpen = { vm.open(project) },
                            onDelete = { vm.deleteProject(project.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    modified: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = SchnittstelleColors.Surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SchnittstelleColors.Accent),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    color = SchnittstelleColors.Text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${MediaProbe.formatTime(project.durationMs)} · " +
                        "${project.track(TrackKind.VIDEO).items.size} Clips · " +
                        "${project.track(TrackKind.TEXT).items.size} Texte · " +
                        project.aspect.label,
                    color = SchnittstelleColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "geändert $modified",
                    color = SchnittstelleColors.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onDelete) {
                Text("Löschen", color = SchnittstelleColors.Danger)
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> "${bytes / 1024} kB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.GERMANY, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
