package com.qalabox.emu.util

import java.io.File
import java.io.RandomAccessFile

/**
 * محلل PE — يحدد معمارية ملف exe (32/64 بت) قبل الإقلاع
 * لاختيار Box86 أو Box64 تلقائياً. ميزة غابت تماماً عن ExaGear
 * حيث كان المستخدم يحتاج نسخ محاكاة مختلفة لكل معمارية.
 */
object PeParser {

    const val ARCH_X86 = "x86"
    const val ARCH_X64 = "x64"
    const val ARCH_UNKNOWN = "unknown"

    fun detectArch(exeFile: File): String {
        return try {
            RandomAccessFile(exeFile, "r").use { raf ->
                val dos = ByteArray(2)
                raf.readFully(dos)
                if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return ARCH_UNKNOWN

                raf.seek(0x3C)
                val peOffset = readIntLe(raf)
                if (peOffset <= 0 || peOffset > raf.length()) return ARCH_UNKNOWN

                raf.seek(peOffset.toLong())
                val peSig = ByteArray(4)
                raf.readFully(peSig)
                if (peSig[0] != 'P'.code.toByte() || peSig[1] != 'E'.code.toByte()) return ARCH_UNKNOWN

                val machine = readShortLe(raf).toInt() and 0xFFFF
                when (machine) {
                    0x014C -> ARCH_X86      // IMAGE_FILE_MACHINE_I386
                    0x8664 -> ARCH_X64      // IMAGE_FILE_MACHINE_AMD64
                    else -> ARCH_UNKNOWN
                }
            }
        } catch (e: Exception) {
            ARCH_UNKNOWN
        }
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(raf: RandomAccessFile): Short {
        val b = ByteArray(2)
        raf.readFully(b)
        return ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)).toShort()
    }
}
