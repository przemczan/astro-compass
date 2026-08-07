package com.astrocompass.astro.io

/**
 * Little-endian reader for the catalog blobs. `java.nio.ByteBuffer` is not available in
 * `commonMain`, so this reads bytes by hand — same constraint as lightnet-mobile's `ByteReader`.
 */
class BinaryReader(private val bytes: ByteArray) {
    private var position = 0

    val hasRemaining: Boolean get() = position < bytes.size

    fun readUInt8(): Int = bytes[position++].toInt() and 0xFF

    fun readUInt16(): Int {
        val b0 = readUInt8()
        val b1 = readUInt8()
        return b0 or (b1 shl 8)
    }

    fun readInt32(): Int {
        val b0 = readUInt8()
        val b1 = readUInt8()
        val b2 = readUInt8()
        val b3 = readUInt8()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readFloat32(): Float = Float.fromBits(readInt32())

    /** Length-prefixed (1 byte, so ≤ 255 bytes) UTF-8 string. Empty string means "absent". */
    fun readString(): String {
        val length = readUInt8()
        val slice = bytes.copyOfRange(position, position + length)
        position += length
        return slice.decodeToString()
    }
}
