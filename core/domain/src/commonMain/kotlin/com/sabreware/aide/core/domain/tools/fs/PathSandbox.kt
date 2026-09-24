package com.sabreware.aide.core.domain.tools.fs

// Direct backend also verifies resolved canonical paths stay inside the root —
// defeats symlink escape on OEM ROMs (SAF tree URIs are sandbox-by-construction).
object PathSandbox {

    fun segments(relPath: String?): List<String> {
        if (relPath.isNullOrBlank()) return emptyList()
        if (relPath.startsWith("/")) {
            throw FsException(FsErrorCode.PATH_ESCAPE, "absolute paths are not allowed")
        }
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (p in parts) {
            if (p == "." || p == "..") {
                throw FsException(FsErrorCode.PATH_ESCAPE, "'.' and '..' are not allowed in relPath")
            }
            if (p.contains('\\')) {
                throw FsException(FsErrorCode.PATH_ESCAPE, "backslash not allowed in relPath")
            }
        }
        return parts
    }

    fun normalize(relPath: String?): String = segments(relPath).joinToString("/")

    fun parent(relPath: String): String {
        val segs = segments(relPath)
        return if (segs.size <= 1) "" else segs.dropLast(1).joinToString("/")
    }

    fun lastSegment(relPath: String): String {
        return segments(relPath).lastOrNull()
            ?: throw FsException(FsErrorCode.BAD_ARGS, "relPath has no final segment")
    }

    // Supports *, ?, character classes [abc], literal . — deliberately tiny. Case-insensitive.
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> sb.append('\\').append(c)
                '[' -> {
                    val close = glob.indexOf(']', i + 1)
                    if (close == -1) sb.append("\\[") else {
                        sb.append(glob.substring(i, close + 1))
                        i = close
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
