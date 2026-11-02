package com.mazharansari.axion.agent

import java.io.File

/** The hidden sandbox: every file the agent reads or writes lives under one private directory. */
class Workspace(val root: File) {
    val uploads = File(root, "uploads")
    val outputs = File(root, "outputs")
    init { root.mkdirs(); uploads.mkdirs(); outputs.mkdirs() }

    fun resolve(path: String): File {
        val p = path.trim().removePrefix("~/").removePrefix("/workspace/").removePrefix("workspace/")
        val f = if (p.startsWith("/")) File(p) else File(root, p)
        val canon = f.canonicalFile
        require(canon.path == root.canonicalPath || canon.path.startsWith(root.canonicalPath + File.separator)) {
            "Path is outside the workspace: $path"
        }
        return canon
    }

    fun rel(f: File): String = f.canonicalPath.removePrefix(root.canonicalPath).removePrefix("/").ifEmpty { "." }
}
