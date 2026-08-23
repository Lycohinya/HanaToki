rootProject.name = "HanaToki"

// HanaToki 是通用副本引擎(docs/hanatoki/HANATOKI_ARCHITECTURE.md §3):零 Lyco 依賴,
// 不 includeBuild 任何 Lyco* 插件——這條本身就是架構邊界的一部分(§3「HanaToki 不得 import
// LycohinyaCore/LycoItems/Lycovelia 的任何類別」),Phase 1 甚至不碰 LycoLib(GUI 才需要,
// 且是 softdepend,見 ARCH §11)。其他插件要借 HanaToki 的型別編譯時,比照
// LycoCommerce/plugin/settings.gradle.kts 的寫法自行 includeBuild("../HanaToki")。
