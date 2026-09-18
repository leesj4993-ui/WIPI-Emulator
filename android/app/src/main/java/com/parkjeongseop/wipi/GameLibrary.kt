// 게임 라이브러리 — 임포트한 게임을 filesDir/games/<UUID>/에 영구 저장하고 관리.
package com.parkjeongseop.wipi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class GameEntry(
    val id: String,
    val name: String,
    val cover: Bitmap?,
    val gameFile: File,
    val filename: String,
    val dataDir: File,
)

class GameLibrary(context: Context) {
    private val root = File(context.filesDir, "games").apply { mkdirs() }
    private val contentResolver = context.contentResolver

    fun list(): List<GameEntry> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(it) }
            .sortedBy { it.name }

    private fun load(dir: File): GameEntry? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            val filename = meta.getString("filename")
            val gameFile = File(dir, filename)
            if (!gameFile.exists()) return null
            val cover = File(dir, "cover.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            GameEntry(
                id = dir.name,
                name = meta.getString("name"),
                cover = cover,
                gameFile = gameFile,
                filename = filename,
                dataDir = File(dir, "data"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun importGame(uri: Uri): GameEntry? {
        val filename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.zip"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, filename).writeBytes(bytes)
        WipiNative.nativeGameIcon(bytes)?.let { File(dir, "cover.png").writeBytes(it) }
        val name = WipiNative.nativeGameName(bytes)
            ?.toString(Charset.forName("EUC-KR"))
            ?: filename.substringBeforeLast('.')
        File(dir, "meta.json").writeText(
            JSONObject().put("name", name).put("filename", filename).toString()
        )
        return load(dir)
    }

    fun exportSave(entry: GameEntry, uri: Uri): Boolean = try {
        val output = contentResolver.openOutputStream(uri) ?: return false
        ZipOutputStream(output.buffered()).use { zip ->
            if (entry.dataDir.exists()) {
                entry.dataDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(entry.dataDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun importSave(entry: GameEntry, uri: Uri): Boolean {
        val gameDir = File(root, entry.id)
        val tempDir = File(gameDir, "data_import_tmp")
        return try {
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            val tempRoot = tempDir.canonicalFile
            val input = contentResolver.openInputStream(uri) ?: return false
            ZipInputStream(input.buffered()).use { zip ->
                var item = zip.nextEntry
                while (item != null) {
                    val out = File(tempDir, item.name).canonicalFile
                    if (!out.path.startsWith(tempRoot.path + File.separator) && out != tempRoot) {
                        throw IllegalArgumentException("Invalid ZIP path")
                    }
                    if (item.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    item = zip.nextEntry
                }
            }
            entry.dataDir.deleteRecursively()
            if (!tempDir.renameTo(entry.dataDir)) {
                tempDir.copyRecursively(entry.dataDir, overwrite = true)
                tempDir.deleteRecursively()
            }
            true
        } catch (_: Exception) {
            tempDir.deleteRecursively()
            false
        }
    }

    fun delete(entry: GameEntry) {
        File(root, entry.id).deleteRecursively()
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
