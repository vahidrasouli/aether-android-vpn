package io.aethervpn.android.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Streaming pure-Kotlin implementation of bsdiff 4.x (`BSDIFF40`) binary patch application.
 *
 * Applies a bzip2-compressed bsdiff patch against [oldFile] (e.g. `base.apk`) to produce
 * [newFile] using constant memory (~200 KB) without loading either APK into RAM.
 */
object BsPatch {
    private val MAGIC = byteArrayOf(
        'B'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(),
        'F'.code.toByte(), 'F'.code.toByte(), '4'.code.toByte(), '0'.code.toByte()
    )
    private const val HEADER_SIZE = 32L
    private const val IO_BUFFER_SIZE = 64 * 1024

    fun patch(oldFile: File, patchFile: File, newFile: File) {
        if (!oldFile.isFile) {
            throw IOException("Base file does not exist: ${oldFile.absolutePath}")
        }
        if (!patchFile.isFile || patchFile.length() < HEADER_SIZE) {
            throw IOException("Patch file is missing or too small: ${patchFile.absolutePath}")
        }

        val header = ByteArray(HEADER_SIZE.toInt())
        FileInputStream(patchFile).use { fis ->
            readFully(fis, header, 0, header.size)
        }

        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                throw IOException("Invalid bsdiff magic header")
            }
        }

        val ctrlBlockLen = offtin(header, 8)
        val diffBlockLen = offtin(header, 16)
        val newSize = offtin(header, 24)
        val patchLen = patchFile.length()

        if (ctrlBlockLen < 0 || diffBlockLen < 0 || newSize < 0) {
            throw IOException("Negative sizes in bsdiff header")
        }
        if (HEADER_SIZE + ctrlBlockLen + diffBlockLen > patchLen) {
            throw IOException("Truncated bsdiff patch file")
        }

        val diffOffset = HEADER_SIZE + ctrlBlockLen
        val extraOffset = diffOffset + diffBlockLen
        val extraBlockLen = patchLen - extraOffset

        newFile.parentFile?.mkdirs()
        val oldSize = oldFile.length()

        openBzip2Section(patchFile, HEADER_SIZE, ctrlBlockLen).use { ctrlStream ->
            openBzip2Section(patchFile, diffOffset, diffBlockLen).use { diffStream ->
                openBzip2Section(patchFile, extraOffset, extraBlockLen).use { extraStream ->
                    RandomAccessFile(oldFile, "r").use { oldRaf ->
                        BufferedOutputStream(FileOutputStream(newFile), IO_BUFFER_SIZE).use { out ->
                            applyStreams(
                                oldRaf = oldRaf,
                                oldSize = oldSize,
                                ctrlStream = ctrlStream,
                                diffStream = diffStream,
                                extraStream = extraStream,
                                newSize = newSize,
                                out = out
                            )
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(IO_BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun applyStreams(
        oldRaf: RandomAccessFile,
        oldSize: Long,
        ctrlStream: InputStream,
        diffStream: InputStream,
        extraStream: InputStream,
        newSize: Long,
        out: BufferedOutputStream
    ) {
        var oldPos = 0L
        var newPos = 0L
        val ctrlBuf = ByteArray(24)
        val diffBuf = ByteArray(IO_BUFFER_SIZE)
        val oldBuf = ByteArray(IO_BUFFER_SIZE)

        while (newPos < newSize) {
            readFully(ctrlStream, ctrlBuf, 0, 24)
            val addLen = offtin(ctrlBuf, 0)
            val extraLen = offtin(ctrlBuf, 8)
            val seekLen = offtin(ctrlBuf, 16)

            if (addLen < 0 || extraLen < 0 || newPos + addLen > newSize) {
                throw IOException("Corrupt bsdiff control block (addLen=$addLen, extraLen=$extraLen, newPos=$newPos, newSize=$newSize)")
            }

            var addRemaining = addLen
            while (addRemaining > 0L) {
                val chunk = minOf(addRemaining, diffBuf.size.toLong()).toInt()
                readFully(diffStream, diffBuf, 0, chunk)
                if (oldPos >= 0L && oldPos + chunk <= oldSize) {
                    oldRaf.seek(oldPos)
                    oldRaf.readFully(oldBuf, 0, chunk)
                    for (i in 0 until chunk) {
                        diffBuf[i] = (diffBuf[i].toInt() + oldBuf[i].toInt()).toByte()
                    }
                } else {
                    for (i in 0 until chunk) {
                        val pos = oldPos + i
                        if (pos in 0L until oldSize) {
                            oldRaf.seek(pos)
                            diffBuf[i] = (diffBuf[i].toInt() + oldRaf.read()).toByte()
                        }
                    }
                }
                out.write(diffBuf, 0, chunk)
                oldPos += chunk
                newPos += chunk
                addRemaining -= chunk
            }

            if (newPos + extraLen > newSize) {
                throw IOException("Corrupt bsdiff extra block (extraLen=$extraLen, newPos=$newPos, newSize=$newSize)")
            }

            var extraRemaining = extraLen
            while (extraRemaining > 0L) {
                val chunk = minOf(extraRemaining, diffBuf.size.toLong()).toInt()
                readFully(extraStream, diffBuf, 0, chunk)
                out.write(diffBuf, 0, chunk)
                newPos += chunk
                extraRemaining -= chunk
            }

            oldPos += seekLen
        }
    }

    private fun openBzip2Section(file: File, offset: Long, length: Long): InputStream {
        val fis = FileInputStream(file)
        try {
            var remainingToSkip = offset
            while (remainingToSkip > 0L) {
                val skipped = fis.skip(remainingToSkip)
                if (skipped <= 0L) {
                    if (fis.read() == -1) {
                        throw IOException("Unexpected EOF seeking to offset $offset")
                    }
                    remainingToSkip--
                } else {
                    remainingToSkip -= skipped
                }
            }
            val bounded = BoundedInputStream(fis, length)
            return BufferedInputStream(BZip2CompressorInputStream(bounded), IO_BUFFER_SIZE)
        } catch (e: Throwable) {
            runCatching { fis.close() }
            throw e
        }
    }

    internal fun offtin(buf: ByteArray, offset: Int): Long {
        var y = (buf[offset + 7].toInt() and 0x7F).toLong()
        for (i in 6 downTo 0) {
            y = (y shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        if ((buf[offset + 7].toInt() and 0x80) != 0) {
            y = -y
        }
        return y
    }

    private fun readFully(stream: InputStream, dst: ByteArray, offset: Int, len: Int) {
        var total = 0
        while (total < len) {
            val read = stream.read(dst, offset + total, len - total)
            if (read < 0) {
                throw IOException("Unexpected EOF in bsdiff stream (read $total of $len bytes)")
            }
            total += read
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = delegate.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            delegate.close()
        }
    }
}
