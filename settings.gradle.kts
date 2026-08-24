rootProject.name = "HanaToki"

// HanaToki 是通用副本引擎:零依賴,不 includeBuild 任何其他插件——這條本身就是架構邊界的
// 一部分(引擎不得 import 任何消費端插件的類別)。其他插件要借 HanaToki 的型別編譯時,
// 在自己的 settings.gradle.kts 裡自行 includeBuild("../HanaToki")(見 README「Quick start」)。
