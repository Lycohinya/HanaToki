package com.tinyyana.hanatoki.expedition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「額度用完不能擋見證出口」(2026-09-19 canonical 任務指示,務必有測試)。
 *
 * 這條規則沒辦法用真 Bukkit 場景跑(引擎測試不起 Player/ServicesManager,見
 * `InstanceJournalTest` 的 KDoc 同一套限制),所以驗證方式是**結構性**的:掃
 * `import com.tinyyana.hanatoki.api.RewardQuota*` 這一行存不存在——那是唯一能讓這幾支檔案
 * 真的「查得到」額度型別的管道(Kotlin 跨套件用型別一定要 import,沒 import 就沒有能力查)。
 * 只比對 import 行,不比對整份原始碼的文字,是為了不誤觸 KDoc 裡用中文散文解釋這條不變式時
 * 提到的「quota」字樣本身。
 *
 * 哪天有人在這幾支裡面接了一段 quota 判斷,這個測試會炸,逼那個人正視「這是不是真的要做」
 * 而不是悄悄破壞這條不變式。
 */
class ExpeditionQuotaIndependenceTest {

    private val srcRoot = File("src/main/kotlin/com/tinyyana/hanatoki/expedition")

    private fun importsQuotaType(file: File): Boolean =
        file.readLines().any { it.trimStart().startsWith("import") && it.contains("RewardQuota") }

    @Test
    fun `見證出口的原始碼不 import 任何 RewardQuota 型別`() {
        val files = srcRoot.listFiles { f -> f.extension == "kt" }
        assertTrue(files != null && files.isNotEmpty(), "找不到 expedition 套件原始碼,測試本身可能路徑錯了:${srcRoot.absolutePath}")
        val offenders = files.filter { importsQuotaType(it) }
        assertTrue(
            offenders.isEmpty(),
            "這些檔案 import 了 RewardQuota 型別,違反「見證出口跟獎勵額度是兩條獨立路徑」的不變式:" +
                offenders.joinToString(",") { it.name },
        )
    }

    @Test
    fun `InstanceInventoryService 不 import 任何 RewardQuota 型別`() {
        // InstanceInventoryService 本身混了局內背包交易與見證派送(dispatchExpeditionEvidence)
        // 兩件事,住同一個檔案——它完全不 import RewardQuota,兩件事都沒有查額度的能力。
        val service = File("src/main/kotlin/com/tinyyana/hanatoki/inventory/InstanceInventoryService.kt")
        assertTrue(service.exists(), "找不到 InstanceInventoryService.kt,測試路徑錯了")
        assertTrue(
            !importsQuotaType(service),
            "InstanceInventoryService.kt import 了 RewardQuota 型別——見證派送不該有任何額度依賴",
        )
    }
}
