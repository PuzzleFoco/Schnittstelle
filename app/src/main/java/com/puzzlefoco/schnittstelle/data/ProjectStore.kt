package com.puzzlefoco.schnittstelle.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.puzzlefoco.schnittstelle.model.Project
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persistenz: Projekte als JSON, importierte Medien als Dateien im App-Speicher.
 * Eigene Ablage (kein MediaStore) → keine Pfad-Invalidierung, wenn der Nutzer
 * seine Galerie aufräumt.
 */
class ProjectStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    val projectsDir: File = File(context.filesDir, "projects").apply { mkdirs() }
    val mediaDir: File = File(context.filesDir, "media").apply { mkdirs() }

    fun listProjects(): List<Project> = projectsDir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { load(it) }.getOrNull() }
        ?.sortedByDescending { it.modifiedAt }
        ?: emptyList()

    fun load(projectId: String): Project? {
        val file = File(projectsDir, "$projectId.json")
        if (!file.exists()) return null
        return load(file)
    }

    private fun load(file: File): Project =
        json.decodeFromString(Project.serializer(), file.readText())

    fun save(project: Project) {
        val file = File(projectsDir, "${project.id}.json")
        file.writeText(json.encodeToString(Project.serializer(), project.copy(modifiedAt = System.currentTimeMillis())))
    }

    fun delete(projectId: String) {
        File(projectsDir, "$projectId.json").delete()
    }

    fun mediaFile(relativePath: String): File = File(mediaDir, relativePath)

    /**
     * Kopiert eine vom Nutzer gewählte Datei (content:// oder file://) in den App-Speicher.
     * @return relativer Pfad innerhalb [mediaDir]
     */
    fun importMedia(uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: Uri.fromFile(File(uri.path ?: "")).path?.substringAfterLast('.', "mp4")
            ?: "mp4"
        val target = File(mediaDir, "${UUID.randomUUID().toString().take(12)}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: throw IllegalStateException("Quelle nicht lesbar: $uri")
        return target.name
    }

    /** Anzeigename einer Auswahl-Uri (für Fehlermeldungen). */
    fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    /** Speicherbelegung durch importierte Medien in Bytes. */
    fun mediaUsageBytes(): Long = mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun isKnownMedia(relativePath: String): Boolean = mediaFile(relativePath).exists()
}
