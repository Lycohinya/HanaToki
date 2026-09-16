package com.tinyyana.hanatoki.map

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * 只讀的最小 NBT 解碼器,給 [StructureTemplate] 讀原版 Structure Block 存出來的 `.nbt`。
 *
 * 不走 Bukkit `StructureManager`:那條路拿不到 structure block / jigsaw 的方塊實體欄位
 * (marker 與 connector 就寫在那裡),而且需要伺服器才能跑,資產就沒辦法在單元測試裡驗證。
 * 解出來的值用 JDK 型別:compound = `Map<String, Any>`、list = `List<Any>`、數字照原型別。
 */
object Nbt {
    fun read(bytes: ByteArray): Map<String, Any> = read(ByteArrayInputStream(bytes))

    /** gzip 或未壓縮都接受(原版存檔是 gzip)。 */
    fun read(input: InputStream): Map<String, Any> {
        val pushback = PushbackInputStream(input, 2)
        val head = ByteArray(2)
        val n = pushback.readNBytes(head, 0, 2)
        pushback.unread(head, 0, n)
        val raw = if (n == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) GZIPInputStream(pushback) else pushback
        val data = DataInputStream(raw)
        val type = data.readByte().toInt()
        require(type == TAG_COMPOUND) { "NBT 根節點不是 compound(type=$type)" }
        data.readUTF()
        @Suppress("UNCHECKED_CAST")
        return readPayload(data, TAG_COMPOUND, 0) as Map<String, Any>
    }

    private fun readPayload(data: DataInputStream, type: Int, depth: Int): Any {
        require(depth <= MAX_DEPTH) { "NBT 巢狀超過 $MAX_DEPTH 層" }
        return when (type) {
            1 -> data.readByte()
            2 -> data.readShort()
            3 -> data.readInt()
            4 -> data.readLong()
            5 -> data.readFloat()
            6 -> data.readDouble()
            7 -> ByteArray(length(data)).also { data.readFully(it) }
            8 -> data.readUTF()
            9 -> {
                val elementType = data.readByte().toInt()
                val size = length(data)
                List(size) { readPayload(data, elementType, depth + 1) }
            }
            TAG_COMPOUND -> {
                val out = LinkedHashMap<String, Any>()
                while (true) {
                    val child = data.readByte().toInt()
                    if (child == 0) break
                    val name = data.readUTF()
                    out[name] = readPayload(data, child, depth + 1)
                }
                out
            }
            11 -> IntArray(length(data)) { data.readInt() }
            12 -> LongArray(length(data)) { data.readLong() }
            else -> throw IllegalArgumentException("未知的 NBT tag type=$type")
        }
    }

    private fun length(data: DataInputStream): Int {
        val size = data.readInt()
        require(size in 0..MAX_ELEMENTS) { "NBT 陣列長度不合理:$size" }
        return size
    }

    private const val TAG_COMPOUND = 10
    private const val MAX_DEPTH = 64
    private const val MAX_ELEMENTS = 16_000_000
}
