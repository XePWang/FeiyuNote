package com.feiyu.notes.data

import java.io.File
import java.util.UUID

/** Photos live in files/images/<notebookId>/<fileName>; nothing outside that tree is accepted. */
class PhotoFiles(filesDir: File) {
    private val root = File(filesDir, "images")

    fun notebookDir(notebookId: Long): File = File(root, notebookId.toString())

    /** Returns a fresh, not-yet-existing file for the camera to write into. */
    fun allocatePhoto(notebookId: Long): File {
        val dir = notebookDir(notebookId).apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    fun resolvePhoto(notebookId: Long, fileName: String): File {
        require(isSafeName(fileName)) { "Invalid photo file name" }
        return File(notebookDir(notebookId), fileName)
    }

    /** A photo may become a sent attachment only once fully written. */
    fun isUsable(notebookId: Long, fileName: String): Boolean =
        isSafeName(fileName) && resolvePhoto(notebookId, fileName).let { it.isFile && it.length() > 0 }

    fun deletePhotos(notebookId: Long, fileNames: Collection<String>) {
        fileNames.filter(::isSafeName).forEach { File(notebookDir(notebookId), it).delete() }
    }

    fun deleteNotebookDir(notebookId: Long) {
        notebookDir(notebookId).deleteRecursively()
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == File.separatorChar }
}
