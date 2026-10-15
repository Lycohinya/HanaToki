rootProject.name = "HanaToki"

// HanaToki 是通用副本引擎:引擎不得 import 任何消費端(玩法內容)插件的類別。
// 唯一的編譯期兄弟是 LycoLib(共用基礎庫,不是消費端),只為了把副本 ActionBar 交給它的 HudBridge 排隊;
// 它是 softdepend,執行期沒有 LycoLib 照常載入(見 hud/ActionBars.kt)。
// 其他插件要借 HanaToki 的型別編譯時,在自己的 settings.gradle.kts 裡自行 includeBuild("../HanaToki")(見 README「Quick start」)。
includeBuild("../LycoLib")
