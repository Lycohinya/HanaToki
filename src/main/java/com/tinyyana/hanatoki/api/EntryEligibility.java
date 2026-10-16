package com.tinyyana.hanatoki.api;

import java.util.UUID;

/**
 * 內容層提供給 HanaToki 的「這位玩家現在能不能進這座副本」查詢(1.0.0-rc.17)。
 *
 * <p>由內容／integration 插件以 Bukkit {@code ServicesManager} 註冊，可以有<b>多個</b> provider。
 * HanaToki 每次進場 admission 都重新取一次全部 registration，逐一詢問：任何一個 provider 回傳
 * 非空字串就擋下，結果是 {@code DungeonEntryStatus.NOT_ELIGIBLE}，該字串原樣成為
 * {@link DungeonEntryOutcome#failureReason()}。單人、雙人、三人與任意人數進場都對名單上
 * <b>每一位</b>玩家逐人檢查；{@link DungeonAccess#entryBlockReason(UUID, String)} 預檢也會回報同一個原因。
 *
 * <h2>實作規則</h2>
 * <ul>
 *   <li><b>只讀、快速、執行緒安全</b>：會在 HanaToki 進場臨界區（持有引擎鎖）內、任意執行緒上被呼叫。
 *       只查記憶體中的快取；不做資料庫／網路 I/O，不等 future，不碰世界或實體狀態，
 *       也不要回頭呼叫 {@link DungeonAccess} 的進場方法。</li>
 *   <li>回傳 {@code null} 或空字串 = 不擋（對這座副本沒有意見也回 null）。</li>
 *   <li>擋下時回傳<b>玩家可讀</b>的繁體中文純文字（不含 MiniMessage 標籤），說明缺什麼、去哪裡補，
 *       句尾不加句號。</li>
 *   <li>丟出例外視同<b>擋下</b>：HanaToki 記 warning，原因改用引擎自己的文字
 *       （{@code messages.yml} 的 {@code session.eligibility-error}）。</li>
 * </ul>
 *
 * <h2>跨插件型別</h2>
 * 刻意寫成 Java interface、只用 JDK 型別（{@link UUID}／{@link String}），沒有 Kotlin 預設參數或
 * Kotlin 執行期型別，provider 不需要 kotlin-stdlib 也能實作。
 */
public interface EntryEligibility {

    /**
     * @param player    要進場的玩家
     * @param dungeonId 副本 id（{@code dungeons.yml} 的 key）
     * @return {@code null} 或空字串 = 可以進；否則是玩家可讀的擋下原因
     */
    String block(UUID player, String dungeonId);
}
