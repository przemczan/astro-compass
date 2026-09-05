package com.astro.ssag

class IntelHexParser {
    data class HexRecord(val address: Int, val type: Int, val data: ByteArray)

    companion object {
        fun parse(hexContent: String): List<HexRecord> {
            val records = mutableListOf<HexRecord>()
            hexContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(":") && trimmed.length >= 11) {
                    val byteCount = trimmed.substring(1, 3).toInt(16)
                    val address = trimmed.substring(3, 7).toInt(16)
                    val recordType = trimmed.substring(7, 9).toInt(16)
                    
                    if (recordType == 0) { // Data Record
                        val data = ByteArray(byteCount)
                        for (i in 0 until byteCount) {
                            val start = 9 + (i * 2)
                            data[i] = trimmed.substring(start, start + 2).toInt(16).toByte()
                        }
                        records.add(HexRecord(address, recordType, data))
                    }
                }
            }
            return records
        }
    }
}