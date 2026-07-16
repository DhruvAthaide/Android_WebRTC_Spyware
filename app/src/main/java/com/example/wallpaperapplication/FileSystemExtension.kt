package com.example.wallpaperapplication

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * FileSystemExtension handles all remote file system operations over Socket.IO.
 *
 * Features:
 *  - fs:list   → list files in a directory
 *  - fs:download → send file to web client in base64 chunks
 *  - fs:delete → delete a file or directory
 *
 * Special path alias: "REC" → resolves to the Recordings directory used by LocalRecorder.
 * A virtual "⭐ RECORDINGS" folder is injected at the root level for easy navigation.
 */
class FileSystemExtension(private val context: Context, private val socket: Socket) {

    private var webClientId: String? = null
    private var uploadStream: java.io.FileOutputStream? = null
    private var uploadFile: File? = null

    fun init() {
        // Capture web client ID when a client connects
        socket.on(Constants.EVENT_WEB_CLIENT_READY) { args ->
            try {
                if (args != null && args.isNotEmpty()) {
                    webClientId = args[0] as? String
                    Log.d(TAG, "Web client connected: $webClientId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading web client ID", e)
            }
        }

        // ── LIST FILES ───────────────────────────────────────────
        socket.on(Constants.FS_LIST) { args ->
            val path = if (args != null) parsePath(args) else null
            listFiles(path)
        }

        // ── DOWNLOAD FILE ────────────────────────────────────────
        socket.on(Constants.FS_DOWNLOAD) { args ->
            val path = if (args != null) parsePath(args) else null
            if (path != null) downloadFile(path)
        }

        // ── DELETE FILE ──────────────────────────────────────────
        socket.on(Constants.FS_DELETE) { args ->
            val path = if (args != null) parsePath(args) else null
            if (path != null) deleteFile(path)
        }

        // ── UPLOAD FILE ──────────────────────────────────────────
        socket.on(Constants.FS_UPLOAD_START) { args ->
            if (args != null && args.isNotEmpty() && args[0] is JSONObject) {
                handleUploadStart(args[0] as JSONObject)
            }
        }

        socket.on(Constants.FS_UPLOAD_CHUNK) { args ->
            if (args != null && args.isNotEmpty() && args[0] is JSONObject) {
                handleUploadChunk(args[0] as JSONObject)
            }
        }

        socket.on(Constants.FS_UPLOAD_COMPLETE) { args ->
            handleUploadComplete()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────

    private fun parsePath(args: Array<Any>): String? {
        if (args.isEmpty()) return null
        return when (val first = args[0]) {
            is String -> first
            is JSONObject -> if (first.has("path")) first.getString("path") else null
            else -> null
        }
    }

    private fun resolveRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Validates that the resolved path is within allowed boundaries
     * to prevent path traversal attacks (e.g., ../../etc/passwd).
     */
    private fun isPathAllowed(path: String): Boolean {
        return try {
            val canonical = File(path).canonicalPath
            val rootCanonical = File(Constants.ROOT_PATH).canonicalPath
            val recCanonical = resolveRecordingsDir().canonicalPath
            canonical.startsWith(rootCanonical) || canonical.startsWith(recCanonical)
        } catch (e: Exception) {
            Log.e(TAG, "Path validation error for: $path", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // fs:list
    // ─────────────────────────────────────────────────────────────

    private fun listFiles(path: String?) {
        if (webClientId == null) return

        // Resolve special "REC" alias → Recordings directory
        val actualPath = if (path == "REC") resolveRecordingsDir().absolutePath else path

        var dir = if (actualPath.isNullOrEmpty()) File(Constants.ROOT_PATH) else File(actualPath)

        // Validate path is within allowed boundaries
        if (!isPathAllowed(dir.absolutePath)) {
            Log.w(TAG, "Path traversal blocked: ${dir.absolutePath}")
            dir = File(Constants.ROOT_PATH)
        }

        // Fallback to root if path is invalid
        if (!dir.exists() || !dir.isDirectory) {
            dir = File(Constants.ROOT_PATH)
        }

        val files = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException listing files in: ${dir.absolutePath}", e)
            null
        }
        val fileList = JSONArray()

        // Inject virtual "⭐ RECORDINGS" shortcut at root level
        val isAtRoot = dir.absolutePath == Constants.ROOT_PATH ||
                dir.absolutePath == "/storage/emulated/0"
        if (isAtRoot) {
            try {
                fileList.put(
                    JSONObject()
                        .put("name", "⭐ RECORDINGS")
                        .put("path", "REC")
                        .put("isDir", true)
                        .put("size", 0)
                )
            } catch (ignored: JSONException) {}
        }

        files?.forEach { f ->
            try {
                fileList.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("path", f.absolutePath)
                        .put("isDir", f.isDirectory)
                        .put("size", f.length())
                )
            } catch (ignored: JSONException) {}
        }

        try {
            val msg = JSONObject()
                .put("to", webClientId)
                .put("from", socket.id())
                .put(
                    "file_list",
                    JSONObject()
                        .put("currentPath", dir.absolutePath)
                        .put("files", fileList)
                )
            socket.emit(Constants.FS_FILES, msg)
        } catch (ignored: JSONException) {}
    }

    // ─────────────────────────────────────────────────────────────
    // fs:download — chunked base64 transfer
    // ─────────────────────────────────────────────────────────────

    private fun downloadFile(path: String) {
        if (webClientId == null || !socket.connected()) return
        if (!isPathAllowed(path)) {
            Log.w(TAG, "Download blocked — path traversal: $path")
            return
        }
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) return

        Thread {
            var fis: FileInputStream? = null
            try {
                val fileId = java.util.UUID.randomUUID().toString()
                val size = file.length()
                val chunkSize = Constants.FS_CHUNK_SIZE
                val totalChunks = Math.ceil(size.toDouble() / chunkSize).toInt()

                socket.emit(
                    Constants.FS_DOWNLOAD_START,
                    JSONObject()
                        .put("to", webClientId)
                        .put("fileId", fileId)
                        .put("name", file.name)
                        .put("size", size)
                        .put("totalChunks", totalChunks)
                )

                fis = FileInputStream(file)
                val buffer = ByteArray(chunkSize)
                var read: Int
                var idx = 0

                while (fis.read(buffer).also { read = it } != -1) {
                    // Abort if socket disconnected mid-transfer
                    if (!socket.connected()) {
                        Log.w(TAG, "Socket disconnected during download, aborting")
                        break
                    }
                    socket.emit(
                        Constants.FS_DOWNLOAD_CHUNK,
                        JSONObject()
                            .put("to", webClientId)
                            .put("fileId", fileId)
                            .put("chunkIndex", idx++)
                            .put("content", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                    )
                    Thread.sleep(30) // ~33 chunks/sec throttle to prevent socket flooding
                }

                if (socket.connected()) {
                    socket.emit(
                        Constants.FS_DOWNLOAD_COMPLETE,
                        JSONObject().put("to", webClientId).put("fileId", fileId)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "File transfer error", e)
            } finally {
                try { fis?.close() } catch (ignored: Exception) {}
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────
    // fs:delete
    // ─────────────────────────────────────────────────────────────

    private fun deleteFile(path: String) {
        try {
            if (webClientId == null) return
            if (!isPathAllowed(path)) {
                Log.w(TAG, "Delete blocked — path traversal: $path")
                return
            }
            val file = File(path)
            val success = file.deleteRecursively()

            socket.emit(
                Constants.FS_DELETE_RESULT,
                JSONObject()
                    .put("to", webClientId)
                    .put("success", success)
                    .put("path", path)
            )

            // Refresh the parent folder after deletion
            listFiles(file.parent ?: Constants.ROOT_PATH)
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed", e)
        }
    }

    private fun handleUploadStart(payload: JSONObject) {
        try {
            if (uploadStream != null) {
                Log.w(TAG, "Upload already in progress, aborting previous")
                try {
                    uploadStream?.close()
                } catch (_: Exception) {}
                uploadStream = null
                uploadFile = null
            }

            val filename = payload.getString("filename")
            if (filename.isEmpty() || filename.contains("..") || filename.contains("/")) {
                Log.w(TAG, "Invalid upload filename: $filename")
                return
            }
            val parentPath = payload.optString("parentPath", "/storage/emulated/0/")
            val resolvedParent = if (parentPath == "REC") resolveRecordingsDir().absolutePath else parentPath
            
            if (!isPathAllowed(resolvedParent)) {
                Log.w(TAG, "Upload blocked — invalid parent path: $resolvedParent")
                return
            }

            uploadFile = File(resolvedParent, filename)
            uploadStream = java.io.FileOutputStream(uploadFile)
            Log.d(TAG, "Starting file upload stream to: ${uploadFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting file upload", e)
        }
    }

    private fun handleUploadChunk(payload: JSONObject) {
        try {
            val base64Chunk = payload.getString("chunk")
            val data = Base64.decode(base64Chunk, Base64.NO_WRAP)
            uploadStream?.write(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file upload chunk", e)
        }
    }

    private fun handleUploadComplete() {
        try {
            uploadStream?.flush()
            uploadStream?.close()
            Log.d(TAG, "Completed file upload stream to: ${uploadFile?.absolutePath}")
            
            val parent = uploadFile?.parent ?: "/storage/emulated/0/"
            listFiles(parent)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file upload stream", e)
        } finally {
            uploadStream = null
            uploadFile = null
        }
    }

    companion object {
        private const val TAG = "FileSystemExtension"
    }
}
