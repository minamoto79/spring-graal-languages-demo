package com.example.springLanguages.js

import org.graalvm.polyglot.io.FileSystem
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import kotlin.io.path.Path

class InMemoryFileSystem(
    private val files: Map<String, ByteArray>, // absolute unix-like paths: "/app/index.js"
    private val roots: Set<String> = setOf("/")
) : FileSystem {

    private fun norm(path: String): String {
        val p = path.replace('\\', '/')
        return if (p.startsWith("/")) p else "/$p"
    }

    private fun exists(p: String) = files.containsKey(norm(p)) || isDir(p)

    private fun isDir(path: String): Boolean {
        val p = norm(path).trimEnd('/')
        if (p.isEmpty()) return true
        val prefix = "$p/"
        return files.keys.any { it.startsWith(prefix) }
    }

    override fun parsePath(uri: URI): Path {
        return Path(uri.path)
    }

    override fun parsePath(path: String): Path = Path(norm(path))

    override fun toAbsolutePath(path: Path): Path = if (path.isAbsolute) path else Path("/").resolve(path)

    override fun toRealPath(path: Path, vararg options: LinkOption): Path {
        val abs = toAbsolutePath(path).normalize()
        if (!exists(abs.toString())) throw NoSuchFileException(abs.toString())
        return abs
    }

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) {
        val p = toRealPath(path).toString()
        if (!exists(p)) throw NoSuchFileException(p)
        if (modes.contains(AccessMode.WRITE)) throw AccessDeniedException(p)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        throw AccessDeniedException(dir.toString()) // read-only
    }

    override fun delete(path: Path) {
        throw AccessDeniedException(path.toString()) // read-only
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        val p = toRealPath(path).toString()
        if (isDir(p)) throw FileSystemException(p, null, "Is a directory")
        if (options.any { it == StandardOpenOption.WRITE || it == StandardOpenOption.CREATE || it == StandardOpenOption.TRUNCATE_EXISTING }) {
            throw AccessDeniedException(p)
        }
        val data = files[norm(p)] ?: throw NoSuchFileException(p)
        return object : SeekableByteChannel {
            private var pos = 0
            private var open = true
            override fun isOpen() = open
            override fun close() { open = false }
            override fun position(): Long = pos.toLong()
            override fun position(newPosition: Long): SeekableByteChannel {
                pos = newPosition.toInt().coerceIn(0, data.size); return this
            }
            override fun size(): Long = data.size.toLong()
            override fun truncate(size: Long): SeekableByteChannel = throw AccessDeniedException(p)
            override fun read(dst: ByteBuffer): Int {
                if (!open) throw ClosedChannelException()
                if (pos >= data.size) return -1
                val n = minOf(dst.remaining(), data.size - pos)
                dst.put(data, pos, n)
                pos += n
                return n
            }
            override fun write(src: ByteBuffer): Int = throw AccessDeniedException(p)
        }
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val d = toRealPath(dir).toString().trimEnd('/')
        if (!isDir(d)) throw NotDirectoryException(d)

        val prefix = if (d == "/") "/" else "$d/"
        val children = files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .map { it.substringBefore('/') }
            .distinct()
            .map { Path(prefix + it) }
            .filter { filter.accept(it) }

        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = children.toMutableList().iterator()
            override fun close() {}
        }
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> {
        val p = toRealPath(path).toString()
        val now = FileTime.fromMillis(System.currentTimeMillis())
        val isDir = isDir(p)
        val size = if (isDir) 0L else (files[norm(p)]?.size?.toLong() ?: 0L)
        return mapOf(
            "isDirectory" to isDir,
            "isRegularFile" to !isDir,
            "size" to size,
            "lastModifiedTime" to now,
            "creationTime" to now,
            "lastAccessTime" to now
        )
    }

    override fun getTempDirectory(): Path = Path("/tmp")

    override fun getPathSeparator(): String = "/"


    override fun setCurrentWorkingDirectory(path: Path) {
        // keep fixed "/" for simplicity
    }
}