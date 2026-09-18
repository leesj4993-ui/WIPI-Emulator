// 게임 라이브러리 — 임포트한 게임을 filesDir/games/<UUID>/에 영구 저장하고 관리.
// 표지·게임명은 패키지(zip) 안의 big.icon/__adf__에서 뽑아 캐시한다 (iOS GameLibrary와 대칭).

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

data class GameEntry(
    val id: String,
    val name: String,
    val cover: Bitmap?,
    val gameFile: File,
    val filename: String, // 포맷 감지용 원본 파일명(.zip/.jar 확장자 유지)
    val dataDir: File, // 게임별 세이브 경로 (games/<id>/data) — 삭제 시 함께 제거
)

class GameLibrary(context: Context) {
    private val root = File(context.filesDir, "games").apply { mkdirs() }
    private val contentResolver = context.contentResolver


    /** 저장된 게임들을 스캔 (이름순) */
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
        } catch (e: Exception) {
            null
        }
    }

    /** SAF Uri에서 게임을 임포트 (복사 + 표지/이름 추출·캐시). 실패 시 null. */
    fun importGame(uri: Uri): GameEntry? {
        val filename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.zip"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, filename).writeBytes(bytes)

        WipiNative.nativeGameIcon(bytes)?.let { File(dir, "cover.png").writeBytes(it) }

        // 게임명: __adf__(EUC-KR) → 없으면 파일명(확장자 제거)
        val name = WipiNative.nativeGameName(bytes)
            ?.toString(Charset.forName("EUC-KR"))
            ?: filename.substringBeforeLast('.')

        File(dir, "meta.json").writeText(
            JSONObject().put("name", name).put("filename", filename).toString()
        )

        return load(dir)
    }

    fun exportSave(entry: GameEntry): File {
    val out = File(root.parentFile, "${entry.name}_save.zip")
    java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
        entry.dataDir.walkTopDown().filter { it.isFile }.forEach { file ->
            zip.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(entry.dataDir).path))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return out
    }
    fun delete(entry: GameEntry) {
        File(root, entry.id).deleteRecursively()
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
