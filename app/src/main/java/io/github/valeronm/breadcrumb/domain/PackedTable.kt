package io.github.valeronm.breadcrumb.domain

import java.nio.ByteBuffer

/**
 * The header of a table the tools packed, past its [magic] and, where [atlasRows] is given, past the
 * atlas row count it was packed against; big-endian, as [ByteBuffer] reads by default. Throws
 * [IllegalArgumentException] naming [what] on bytes that don't open with [magic], end within the
 * [headerBytes] after it, or were packed against an atlas of another row count.
 */
internal fun packedHeader(
    bytes: ByteArray,
    magic: ByteArray,
    what: String,
    headerBytes: Int,
    atlasRows: Int? = null,
): ByteBuffer {
    require(bytes.size >= magic.size + headerBytes && magic.indices.all { bytes[it] == magic[it] }) { "not a $what" }
    val header = ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)
    if (atlasRows != null) {
        val forRows = header.int
        require(forRows == atlasRows) { "$what was packed against $forRows atlas rows, the atlas has $atlasRows" }
    }
    return header
}
