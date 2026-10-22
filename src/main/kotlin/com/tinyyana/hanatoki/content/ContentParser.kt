package com.tinyyana.hanatoki.content

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** 內容資料有誤。[errors] 每行一個問題,格式「檔名 路徑: 訊息」(路徑含 ID 與欄位);有任何一筆就拒絕整份內容。 */
class ContentDataException(val errors: List<String>) : RuntimeException(
    "副本內容資料有 ${errors.size} 個錯誤:\n" + errors.joinToString("\n") { "  - $it" },
)

/** 資料檔名(固定;cues/vfx/dialog 不在 HanaToki 載入,只用 ID 引用)。 */
object ContentFileNames {
    const val STAGES = "stages.yml"
    const val SCENE = "scene.yml"
    const val OBJECTS = "objects.yml"
    const val ENEMIES = "enemies.yml"
    const val ENCOUNTERS = "encounters.yml"
    const val TOOL = "tool.yml"
    const val HINTS = "hints.yml"

    val REQUIRED = listOf(STAGES, SCENE)
    val OPTIONAL = listOf(OBJECTS, ENEMIES, ENCOUNTERS, TOOL, HINTS)
    val ALL = REQUIRED + OPTIONAL
}

/** 讀 YAML 文字成純 Map(不經 Bukkit 的 YamlConfiguration:它會把含 `.` 的 key 拆成路徑,載入器就擋不到了)。 */
object ContentYaml {
    @Suppress("UNCHECKED_CAST")
    fun parse(fileName: String, text: String): Map<String, Any?> {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val loaded = try {
            Yaml(SafeConstructor(options)).load<Any?>(text)
        } catch (error: Exception) {
            throw ContentDataException(listOf("$fileName: YAML 無法解析(${error.message?.lineSequence()?.firstOrNull()})"))
        }
        if (loaded == null) return emptyMap()
        return loaded as? Map<String, Any?> ?: throw ContentDataException(listOf("$fileName: 最上層必須是 key: value 區塊"))
    }
}

object ContentIds {
    private val ID = Regex("[a-z0-9_-]+")
    fun valid(id: String): Boolean = ID.matches(id)
    const val RULE = "只能使用小寫英數、底線、連字號(a-z 0-9 _ -),不可含 '.' 或空白"
}

/**
 * 七個資料檔 → [ContentSpec]。只做結構與欄位驗證(型別、範圍、ID 字元、拼錯的欄位名);跨檔參照與場景 marker
 * 的核對在 [ContentValidator]。所有錯誤一次收齊再丟 [ContentDataException],不半載入。
 */
class ContentParser(private val files: Map<String, Map<String, Any?>>) {
    private val errors = ArrayList<String>()

    fun parse(): ContentSpec {
        for (required in ContentFileNames.REQUIRED) {
            if (required !in files) errors += "$required: 缺少必要資料檔"
        }
        val stagesFile = Node(ContentFileNames.STAGES, "", files[ContentFileNames.STAGES] ?: emptyMap())
        val sceneFile = Node(ContentFileNames.SCENE, "", files[ContentFileNames.SCENE] ?: emptyMap())
        stagesFile.only(setOf("dungeon", "namespace", "stages"))
        sceneFile.only(setOf("entry", "pieces"))

        val dungeon = stagesFile.id("dungeon", required = true)
        val namespace = stagesFile.id("namespace", required = true)
        val stages = parseStages(stagesFile)
        val scene = parseScene(sceneFile)
        val objects = parseObjects(optional(ContentFileNames.OBJECTS, "objects"))
        val enemies = parseEnemies(optional(ContentFileNames.ENEMIES, "enemies"))
        val encounters = parseEncounters(optional(ContentFileNames.ENCOUNTERS, "encounters"))
        val tools = parseTools(optional(ContentFileNames.TOOL, "tools"))
        val hints = parseHints(optional(ContentFileNames.HINTS, "hints"))
        for (name in files.keys) if (name !in ContentFileNames.ALL) errors += "$name: 未知的資料檔(只認 ${ContentFileNames.ALL.joinToString()})"

        if (errors.isNotEmpty()) throw ContentDataException(errors.toList())
        return ContentSpec(dungeon!!, namespace!!, stages, scene!!, objects, enemies, encounters, tools, hints)
    }

    /** 可選檔案:回傳 (檔名, 最上層 key 底下的 Map)。檔案不存在 = 空。 */
    private fun optional(file: String, rootKey: String): Node? {
        val raw = files[file] ?: return null
        val root = Node(file, "", raw)
        root.only(setOf(rootKey))
        return root.child(rootKey)
    }

    // ---- 小工具:Node = 檔名 + 路徑 + Map,所有欄位讀取都帶路徑回報錯誤 ----

    private inner class Node(val file: String, val path: String, val map: Map<String, Any?>) {
        fun at(key: String): String = if (path.isEmpty()) key else "$path.$key"
        fun err(key: String?, message: String) {
            errors += "$file ${if (key == null) path else at(key)}: $message"
        }

        fun only(allowed: Set<String>) {
            for (key in map.keys) if (key !in allowed) err(key, "未知的欄位(可用:${allowed.sorted().joinToString()})")
        }

        fun str(key: String, required: Boolean = false): String? {
            val value = map[key]
            if (value == null) {
                if (required) err(key, "缺少必要欄位")
                return null
            }
            if (value is Map<*, *> || value is List<*>) {
                err(key, "必須是文字")
                return null
            }
            val text = value.toString()
            if (text.isBlank()) {
                if (required) err(key, "不可為空")
                return null
            }
            return text
        }

        fun id(key: String, required: Boolean = false): String? {
            val text = str(key, required) ?: return null
            if (!ContentIds.valid(text)) {
                err(key, "ID「$text」不合法:${ContentIds.RULE}")
                return null
            }
            return text
        }

        fun num(key: String, min: Double? = null, max: Double? = null, exclusiveMin: Boolean = false, required: Boolean = false): Double? {
            val value = map[key]
            if (value == null) {
                if (required) err(key, "缺少必要欄位")
                return null
            }
            val number = (value as? Number)?.toDouble()
            if (number == null || number.isNaN() || number.isInfinite()) {
                err(key, "必須是數字(目前 $value)")
                return null
            }
            if (min != null && (number < min || (exclusiveMin && number == min))) {
                err(key, "必須 ${if (exclusiveMin) ">" else ">="} $min(目前 $number)")
                return null
            }
            if (max != null && number > max) {
                err(key, "必須 <= $max(目前 $number)")
                return null
            }
            return number
        }

        fun int(key: String, min: Int? = null, max: Int? = null, required: Boolean = false): Int? {
            val value = map[key]
            if (value == null) {
                if (required) err(key, "缺少必要欄位")
                return null
            }
            val number = value as? Number
            if (number == null || number.toDouble() != Math.rint(number.toDouble())) {
                err(key, "必須是整數(目前 $value)")
                return null
            }
            val v = number.toInt()
            if (min != null && v < min) { err(key, "必須 >= $min(目前 $v)"); return null }
            if (max != null && v > max) { err(key, "必須 <= $max(目前 $v)"); return null }
            return v
        }

        fun bool(key: String): Boolean? {
            val value = map[key] ?: return null
            if (value !is Boolean) {
                err(key, "必須是 true/false(目前 $value)")
                return null
            }
            return value
        }

        @Suppress("UNCHECKED_CAST")
        fun child(key: String, required: Boolean = false): Node? {
            val value = map[key]
            if (value == null) {
                if (required) err(key, "缺少必要區塊")
                return null
            }
            if (value !is Map<*, *>) {
                err(key, "必須是區塊(key: value)")
                return null
            }
            return Node(file, at(key), value as Map<String, Any?>)
        }

        /** 子區塊的每一項;key 必須是合法 ID。 */
        @Suppress("UNCHECKED_CAST")
        fun entries(): List<Pair<String, Node>> {
            val out = ArrayList<Pair<String, Node>>()
            for ((rawKey, value) in map) {
                val key = rawKey.toString()
                if (!ContentIds.valid(key)) {
                    errors += "$file ${at(key)}: ID「$key」不合法:${ContentIds.RULE}"
                    continue
                }
                if (value !is Map<*, *>) {
                    errors += "$file ${at(key)}: 必須是區塊(key: value)"
                    continue
                }
                out += key to Node(file, at(key), value as Map<String, Any?>)
            }
            return out
        }

        fun list(key: String): List<Any?> {
            val value = map[key] ?: return emptyList()
            if (value !is List<*>) {
                err(key, "必須是清單")
                return emptyList()
            }
            return value
        }

        /** ID 清單(cue、marker 組…)。 */
        fun idList(key: String): List<String> {
            val out = ArrayList<String>()
            // 單一 ID 可以直接寫成純量(cues: flash),等同只有一個元素的清單
            val scalar = map[key]
            if (scalar != null && scalar !is List<*> && scalar !is Map<*, *>) {
                val text = scalar.toString()
                if (!ContentIds.valid(text)) errors += "$file ${at(key)}: ID「$text」不合法:${ContentIds.RULE}" else out += text
                return out
            }
            list(key).forEachIndexed { index, item ->
                val text = item?.toString()
                when {
                    item == null || item is Map<*, *> || item is List<*> || text.isNullOrBlank() -> errors += "$file ${at(key)}[$index]: 必須是 ID 文字"
                    !ContentIds.valid(text) -> errors += "$file ${at(key)}[$index]: ID「$text」不合法:${ContentIds.RULE}"
                    else -> out += text
                }
            }
            return out
        }

        fun strList(key: String): List<String> {
            val out = ArrayList<String>()
            list(key).forEachIndexed { index, item ->
                if (item == null || item is Map<*, *> || item is List<*> || item.toString().isBlank()) errors += "$file ${at(key)}[$index]: 必須是文字"
                else out += item.toString()
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        fun mapList(key: String): List<Node> {
            val out = ArrayList<Node>()
            list(key).forEachIndexed { index, item ->
                if (item !is Map<*, *>) errors += "$file ${at(key)}[$index]: 必須是區塊(key: value)"
                else out += Node(file, "${at(key)}[$index]", item as Map<String, Any?>)
            }
            return out
        }

        /** `{2: 1.5, 3: 2}` 這種「人數 → 值」的表;key 必須是 >= 1 的整數。 */
        fun playerMap(key: String): Map<Int, Any?> {
            val value = map[key] ?: return emptyMap()
            if (value !is Map<*, *>) {
                err(key, "必須是「人數: 值」的區塊")
                return emptyMap()
            }
            val out = LinkedHashMap<Int, Any?>()
            for ((k, v) in value) {
                val n = (k as? Number)?.toInt() ?: k?.toString()?.toIntOrNull()
                if (n == null || n < 1) errors += "$file ${at(key)}: 人數 key「$k」必須是 >= 1 的整數"
                else out[n] = v
            }
            return out
        }
    }

    // ---- stages.yml ----

    private fun parseStages(root: Node): List<StageSpec> {
        val block = root.child("stages", required = true) ?: return emptyList()
        val out = ArrayList<StageSpec>()
        for ((id, node) in block.entries()) {
            node.only(setOf("on-start", "complete", "on-complete", "next", "objective", "objective-done", "objective-every-seconds"))
            val onStart = parseStageEffects(node.child("on-start"))
            val onComplete = parseStageEffects(node.child("on-complete"))
            val complete = node.mapList("complete").mapNotNull { parseCondition(it) }
            val next = node.id("next")
            val objective = parseObjective(node)
            val every = node.num("objective-every-seconds", min = 0.5, max = 600.0) ?: 0.0
            val done = node.str("objective-done")
            if (objective.isEmpty() && (done != null || node.map["objective-every-seconds"] != null)) {
                node.err("objective", "有 objective-done 或 objective-every-seconds 就必須寫 objective")
            }
            out += StageSpec(id, onStart, complete, onComplete, next, objective, done, every)
        }
        if (out.isEmpty() && block.map.isEmpty()) block.err(null, "至少要有一個 stage")
        return out
    }

    /** `objective: key`(單一步驟)或 `objective: [{ text: key, until: {條件} }, …]`(依序,until 達成才換下一步)。 */
    private fun parseObjective(node: Node): List<ObjectiveStep> {
        val raw = node.map["objective"] ?: return emptyList()
        if (raw is List<*>) {
            if (raw.isEmpty()) { node.err("objective", "步驟清單不可為空"); return emptyList() }
            return node.mapList("objective").mapNotNull { step ->
                step.only(setOf("text", "until"))
                val text = step.str("text", required = true)
                val until = step.child("until")?.let { parseCondition(it) }
                if (text == null) null else ObjectiveStep(text, until)
            }
        }
        val text = node.str("objective") ?: return emptyList()
        return listOf(ObjectiveStep(text, null))
    }

    private fun parseStageEffects(node: Node?): StageEffects {
        if (node == null) return StageEffects()
        node.only(setOf("cues", "music", "dialog", "title", "subtitle"))
        return StageEffects(
            cues = node.idList("cues"),
            music = node.str("music"),
            dialog = node.id("dialog"),
            titleKey = node.str("title"),
            subtitleKey = node.str("subtitle"),
        )
    }

    private fun parseCondition(node: Node): Condition? =
        when (val type = node.str("type", required = true)) {
            null -> null
            "encounter-cleared" -> {
                node.only(setOf("type", "encounter"))
                node.id("encounter", required = true)?.let(Condition::EncounterCleared)
            }
            "object-state" -> {
                node.only(setOf("type", "object", "state"))
                val obj = node.id("object", required = true)
                val state = node.id("state", required = true)
                if (obj != null && state != null) Condition.ObjectState(obj, state) else null
            }
            "reach" -> {
                node.only(setOf("type", "marker", "radius"))
                val marker = node.id("marker", required = true)
                val radius = node.num("radius", min = 0.0, exclusiveMin = true, required = true)
                if (marker != null && radius != null) Condition.Reach(marker, radius) else null
            }
            else -> {
                node.err("type", "未知的完成條件「$type」(可用:encounter-cleared、object-state、reach)")
                null
            }
        }

    // ---- scene.yml ----

    private fun parseScene(root: Node): SceneSpec? {
        val entry = root.id("entry", required = true)
        val pieces = LinkedHashMap<String, PieceSpec>()
        val block = root.child("pieces", required = true)
        block?.entries()?.forEach { (id, node) ->
            node.only(setOf("asset", "origin", "rotation"))
            val asset = node.str("asset", required = true)
            if (asset != null && !ASSET.matches(asset)) node.err("asset", "資產 ID「$asset」必須是 namespace:path(小寫英數 _ - . /)")
            val origin = node.list("origin")
            var ox = 0; var oy = 0; var oz = 0
            if (node.map["origin"] == null) node.err("origin", "缺少必要欄位(相對 slot anchor 的 [x, y, z])")
            else if (origin.size != 3 || origin.any { it !is Number || it.toDouble() != Math.rint(it.toDouble()) }) node.err("origin", "必須是三個整數 [x, y, z]")
            else { ox = (origin[0] as Number).toInt(); oy = (origin[1] as Number).toInt(); oz = (origin[2] as Number).toInt() }
            val rotation = node.int("rotation") ?: 0
            if (rotation !in setOf(0, 90, 180, 270)) node.err("rotation", "必須是 0/90/180/270(目前 $rotation)")
            if (asset != null) pieces[id] = PieceSpec(id, asset, ox, oy, oz, rotation)
        }
        if (block != null && block.map.isEmpty()) block.err(null, "至少要有一塊 piece")
        return if (entry != null) SceneSpec(entry, pieces) else null
    }

    // ---- objects.yml ----

    private fun parseObjects(block: Node?): Map<String, ObjectSpec> {
        val out = LinkedHashMap<String, ObjectSpec>()
        block?.entries()?.forEach { (id, node) ->
            val type = node.str("type", required = true)
            val spec: ObjectSpec? = when (type) {
                null -> null
                "lantern" -> parseLantern(id, node)
                "gate" -> parseGate(id, node)
                else -> { node.err("type", "未知的物件類型「$type」(可用:lantern、gate)"); null }
            }
            if (spec != null) out[id] = spec
        }
        return out
    }

    private fun parseStates(node: Node, valid: List<String>): Map<String, StateEffect> {
        val block = node.child("states") ?: return emptyMap()
        val out = LinkedHashMap<String, StateEffect>()
        for (key in block.map.keys) if (key !in valid) block.err(key, "未知的狀態(這個類型的狀態:${valid.joinToString()})")
        for (state in valid) {
            val sub = block.child(state) ?: continue
            sub.only(setOf("cues", "blocks"))
            out[state] = parseStateEffect(sub)
        }
        return out
    }

    private fun parseStateEffect(node: Node): StateEffect {
        val blocks = node.mapList("blocks").mapNotNull { b ->
            b.only(setOf("offset", "block"))
            val offset = b.list("offset")
            val block = b.str("block", required = true)
            if (offset.size != 3 || offset.any { it !is Number || it.toDouble() != Math.rint(it.toDouble()) }) {
                b.err("offset", "必須是三個整數 [x, y, z](相對 marker)")
                null
            } else if (block == null) null
            else BlockEdit((offset[0] as Number).toInt(), (offset[1] as Number).toInt(), (offset[2] as Number).toInt(), block)
        }
        return StateEffect(node.idList("cues"), blocks)
    }

    private fun parseLantern(id: String, node: Node): LanternSpec? {
        node.only(
            setOf(
                "type", "marker", "stage", "states", "burn-seconds", "stay-radius", "speed-by-players", "hold-cue", "repeat-cue",
                "milestones", "pause-cues", "resume-cues", "on-lit", "progress-bar", "ignite-radius", "hint",
            ),
        )
        val marker = node.id("marker", required = true)
        val burn = node.num("burn-seconds", min = 0.0, exclusiveMin = true, required = true)
        val radius = node.num("stay-radius", min = 0.0, exclusiveMin = true, required = true)
        val speed = LinkedHashMap<Int, Double>()
        for ((n, v) in node.playerMap("speed-by-players")) {
            val d = (v as? Number)?.toDouble()
            if (d == null || d <= 0.0) node.err("speed-by-players", "人數 $n 的倍率必須是 > 0 的數字(目前 $v)") else speed[n] = d
        }
        val repeat = node.child("repeat-cue")?.let { r ->
            r.only(setOf("cue", "every-seconds"))
            val cue = r.id("cue", required = true)
            val every = r.num("every-seconds", min = 0.0, exclusiveMin = true, required = true)
            if (cue != null && every != null) RepeatCue(cue, every) else null
        }
        val milestones = node.mapList("milestones").mapNotNull { m ->
            m.only(setOf("at", "cues", "blocks"))
            val at = m.num("at", min = 0.0, max = 1.0, exclusiveMin = true, required = true)
            if (at == null) null else Milestone(at, parseStateEffect(m))
        }.sortedBy { it.at }
        val lit = node.child("on-lit", required = true)
        var onLit: LanternLit? = null
        if (lit != null) {
            lit.only(setOf("cues", "light-radius", "light-level", "light-spacing", "clears-enemies", "opens"))
            onLit = LanternLit(
                cues = lit.idList("cues"),
                lightRadius = lit.num("light-radius", min = 0.0) ?: 0.0,
                lightLevel = lit.int("light-level", min = 1, max = 15) ?: 12,
                lightSpacing = lit.int("light-spacing", min = 1, max = 16) ?: 4,
                clearsEnemies = lit.bool("clears-enemies") ?: false,
                opens = lit.id("opens"),
            )
            val cells = Math.pow(2 * onLit.lightRadius / onLit.lightSpacing + 1, 3.0) * 0.52
            if (cells > MAX_LIGHT_CELLS) {
                lit.err("light-radius", "光源方塊約 ${cells.toInt()} 格,超過上限 $MAX_LIGHT_CELLS(調小 light-radius 或調大 light-spacing)")
            }
        }
        val bar = node.child("progress-bar")?.let { b ->
            b.only(setOf("text", "color"))
            val text = b.str("text", required = true)
            val color = (b.str("color") ?: "YELLOW").uppercase()
            if (color !in BAR_COLORS) b.err("color", "未知的顏色「$color」(可用:${BAR_COLORS.joinToString()})")
            if (text != null) ProgressBar(text, color) else null
        }
        val states = parseStates(node, ObjectStates.LANTERN)
        val igniteRadius = node.num("ignite-radius", min = 0.0, max = 64.0) ?: 0.0
        val hint = node.child("hint")?.let { h ->
            h.only(setOf("cue", "ready-cue", "radius", "ready-radius", "requires-tool"))
            val cue = h.id("cue", required = true)
            val ready = h.id("ready-cue")
            val hintRadius = h.num("radius", min = 0.0, max = 128.0, exclusiveMin = true, required = true)
            val readyRadius = h.num("ready-radius", min = 0.0, max = 128.0, exclusiveMin = true)
            if (ready != null && readyRadius == null) h.err("ready-radius", "有 ready-cue 就必須寫 ready-radius")
            if (ready == null && readyRadius != null) h.err("ready-cue", "有 ready-radius 就必須寫 ready-cue")
            if (readyRadius != null && hintRadius != null && readyRadius > hintRadius) h.err("ready-radius", "不可大於 radius($hintRadius)")
            val tool = h.id("requires-tool")
            if (cue != null && hintRadius != null) ObjectHint(cue, ready, hintRadius, readyRadius ?: hintRadius, tool) else null
        }
        if (marker == null || burn == null || radius == null || onLit == null) return null
        return LanternSpec(
            id, marker, node.id("stage"), states, burn, radius, speed, node.id("hold-cue"), repeat, milestones,
            node.idList("pause-cues"), node.idList("resume-cues"), onLit, bar, igniteRadius, hint,
        )
    }

    private fun parseGate(id: String, node: Node): GateSpec? {
        node.only(setOf("type", "marker", "stage", "states", "column-ticks", "slide-from"))
        val marker = node.id("marker", required = true)
        val slide = (node.str("slide-from") ?: "left").lowercase()
        if (slide != "left" && slide != "right") node.err("slide-from", "必須是 left 或 right(目前 $slide)")
        val states = parseStates(node, ObjectStates.GATE)
        val columnTicks = node.int("column-ticks", min = 1, max = 200) ?: 4
        if (marker == null) return null
        return GateSpec(id, marker, node.id("stage"), states, columnTicks, slide)
    }

    // ---- enemies.yml ----

    private fun parseEnemies(block: Node?): Map<String, EnemySpec> {
        val out = LinkedHashMap<String, EnemySpec>()
        block?.entries()?.forEach { (id, node) ->
            node.only(
                setOf(
                    "base-entity", "model", "animations", "rules", "health", "speed", "damage", "scale", "by-players", "spawn-cues",
                    "needs-flash", "seeks-object", "harasses-players",
                ),
            )
            val base = node.str("base-entity", required = true)?.uppercase()
            val anim = node.child("animations")
            anim?.only(setOf("idle", "stunned", "dissolve"))
            val animations = EnemyAnimations(
                anim?.str("idle") ?: "idle", anim?.str("stunned") ?: "stunned", anim?.str("dissolve") ?: "dissolve",
            )
            val rules = LinkedHashSet<String>()
            node.idList("rules").forEach { rule ->
                if (rule !in EnemyRules.IMPLEMENTED && rule !in EnemyRules.RESERVED) {
                    node.err("rules", "未知的規則標籤「$rule」(已實作:${EnemyRules.IMPLEMENTED.joinToString()};保留:${EnemyRules.RESERVED.joinToString()})")
                } else rules += rule
            }
            val health = node.num("health", min = 0.0, exclusiveMin = true, required = true)
            val speed = node.num("speed", min = 0.0, exclusiveMin = true)
            val damage = node.num("damage", min = 0.0)
            val scale = node.num("scale", min = 0.0, exclusiveMin = true)
            val byPlayers = LinkedHashMap<Int, EnemyMultipliers>()
            val raw = node.child("by-players")
            if (raw != null) {
                for ((n, v) in node.playerMap("by-players")) {
                    val sub = v as? Map<*, *>
                    if (sub == null) { node.err("by-players", "人數 $n 必須是區塊(health/speed/damage 倍率)"); continue }
                    for (k in sub.keys) if (k !in setOf("health", "speed", "damage")) node.err("by-players", "人數 $n 有未知欄位「$k」")
                    fun mult(k: String): Double {
                        val d = (sub[k] as? Number)?.toDouble() ?: 1.0
                        if (d <= 0.0) node.err("by-players", "人數 $n 的 $k 倍率必須 > 0")
                        return d
                    }
                    byPlayers[n] = EnemyMultipliers(mult("health"), mult("speed"), mult("damage"))
                }
            }
            val flash = if (EnemyRules.NEEDS_FLASH in rules) {
                val f = node.child("needs-flash")
                if (f == null) { node.err("needs-flash", "規則 needs-flash 需要這個區塊(whiff-cue、stun-cue、dissolve-cue…)"); null }
                else {
                    f.only(
                        setOf(
                            "whiff-cue", "stun-cue", "dissolve-cue", "refill-cue", "dissolve-ticks", "whiff-cooldown-ticks", "glow-on-stun",
                            "alive-cue", "stunned-cue",
                        ),
                    )
                    NeedsFlashSpec(
                        f.id("whiff-cue"), f.id("stun-cue"), f.id("dissolve-cue"), f.id("refill-cue"),
                        f.int("dissolve-ticks", min = 1, max = 400) ?: 12,
                        f.int("whiff-cooldown-ticks", min = 0, max = 400) ?: 6,
                        f.bool("glow-on-stun") ?: true,
                        f.id("alive-cue"), f.id("stunned-cue"),
                    )
                }
            } else {
                if (node.map["needs-flash"] != null) node.err("needs-flash", "這個敵人沒有 needs-flash 規則標籤,不應有這個區塊")
                null
            }
            val seeks = if (EnemyRules.SEEKS_OBJECT in rules) parseSeeksObject(node) else {
                if (node.map["seeks-object"] != null) node.err("seeks-object", "這個敵人沒有 seeks-object 規則標籤,不應有這個區塊")
                null
            }
            val harass = if (EnemyRules.HARASSES_PLAYERS in rules) parseHarass(node) else {
                if (node.map["harasses-players"] != null) node.err("harasses-players", "這個敵人沒有 harasses-players 規則標籤,不應有這個區塊")
                null
            }
            if (base != null && health != null) {
                out[id] = EnemySpec(
                    id, base, node.str("model"), animations, rules, health, speed, damage, scale, byPlayers,
                    node.idList("spawn-cues"), flash, seeks, harass,
                )
            }
        }
        return out
    }

    private fun parseSeeksObject(enemy: Node): SeeksObjectSpec? {
        val n = enemy.child("seeks-object")
        if (n == null) { enemy.err("seeks-object", "規則 seeks-object 需要這個區塊(object、contest-radius、drain-per-second…)"); return null }
        n.only(
            setOf(
                "object", "states", "repath-ticks", "speed", "contest-radius", "drain-per-second", "max-drainers", "ignores-players",
                "contest-cues", "contest-cue-every-seconds",
            ),
        )
        val obj = n.id("object", required = true)
        val states = LinkedHashSet<String>()
        if (n.map["states"] == null) states += ObjectStates.LANTERN_BURNING else n.idList("states").forEach { states += it }
        if (states.isEmpty()) n.err("states", "不可為空")
        val repath = n.int("repath-ticks", min = MIN_SCAN_TICKS, max = MAX_SCAN_TICKS) ?: 10
        val speed = n.num("speed", min = 0.1, max = 3.0) ?: 1.0
        val radius = n.num("contest-radius", min = 0.5, max = 16.0, required = true)
        val drain = n.num("drain-per-second", min = 0.0, max = 60.0, exclusiveMin = true, required = true)
        val maxDrainers = n.int("max-drainers", min = 1, max = 32) ?: 3
        val ignores = n.bool("ignores-players") ?: false
        val every = n.num("contest-cue-every-seconds", min = 0.2, max = 30.0) ?: 1.0
        if (obj == null || radius == null || drain == null) return null
        return SeeksObjectSpec(obj, states, repath, speed, radius, drain, maxDrainers, ignores, n.idList("contest-cues"), every)
    }

    private fun parseHarass(enemy: Node): HarassSpec? {
        val n = enemy.child("harasses-players")
        if (n == null) { enemy.err("harasses-players", "規則 harasses-players 需要這個區塊(contact-radius、effects…)"); return null }
        n.only(setOf("contact-radius", "cooldown-ticks", "scan-ticks", "effects", "cues", "suppress-melee"))
        val radius = n.num("contact-radius", min = 0.5, max = 8.0, required = true)
        val cooldown = n.int("cooldown-ticks", min = 0, max = 1200) ?: 40
        val scan = n.int("scan-ticks", min = MIN_SCAN_TICKS, max = MAX_SCAN_TICKS) ?: 10
        val effects = ArrayList<HarassEffect>()
        n.mapList("effects").forEach { e ->
            when (val type = e.str("type", required = true)) {
                null -> {}
                "push" -> {
                    e.only(setOf("type", "strength", "lift"))
                    effects += HarassEffect.Push(e.num("strength", min = 0.0, max = 2.0, required = true) ?: 0.0, e.num("lift", min = 0.0, max = 1.0) ?: 0.0)
                }
                "tool-charges" -> {
                    e.only(setOf("type", "tool", "delta"))
                    val tool = e.id("tool", required = true)
                    val delta = e.int("delta", min = -20, max = 20, required = true)
                    if (tool != null && delta != null) effects += HarassEffect.ToolCharges(tool, delta)
                }
                "potion" -> {
                    e.only(setOf("type", "effect", "duration-ticks", "amplifier"))
                    val effect = e.str("effect", required = true)
                    val duration = e.int("duration-ticks", min = 1, max = 1200, required = true)
                    if (effect != null && !effect.contains(':')) e.err("effect", "必須是 namespaced key(例如 minecraft:slowness,目前 $effect)")
                    else if (effect != null && duration != null) effects += HarassEffect.Potion(effect, duration, e.int("amplifier", min = 0, max = 9) ?: 0)
                }
                "damage" -> {
                    e.only(setOf("type", "amount", "min-health"))
                    val amount = e.num("amount", min = 0.0, max = 20.0, exclusiveMin = true, required = true)
                    if (amount != null) effects += HarassEffect.Damage(amount, e.num("min-health", min = 0.0, max = 20.0) ?: 4.0)
                }
                else -> e.err("type", "未知的效果「$type」(可用:push、tool-charges、potion、damage)")
            }
        }
        if (n.map["effects"] == null || effects.isEmpty()) n.err("effects", "至少要有一個效果")
        if (radius == null || effects.isEmpty()) return null
        return HarassSpec(radius, cooldown, scan, effects, n.idList("cues"), n.bool("suppress-melee") ?: false)
    }

    // ---- encounters.yml ----

    private fun parseEncounters(block: Node?): Map<String, EncounterSpec> {
        val out = LinkedHashMap<String, EncounterSpec>()
        block?.entries()?.forEach { (id, node) ->
            node.only(setOf("stage", "enemy", "marker-group", "trigger", "delay-ticks", "counts", "interval-ticks", "start-cues", "marker-offset"))
            val stage = node.id("stage", required = true)
            val enemy = node.id("enemy", required = true)
            val group = node.id("marker-group", required = true)
            val trigger = node.child("trigger", required = true)?.let { parseTrigger(it) }
            val counts = LinkedHashMap<Int, Int>()
            for ((n, v) in node.playerMap("counts")) {
                val c = (v as? Number)?.toInt()
                if (c == null || c < 1) node.err("counts", "人數 $n 的隻數必須是 >= 1 的整數(目前 $v)") else counts[n] = c
            }
            if (node.map["counts"] == null) node.err("counts", "缺少必要欄位(人數: 隻數,至少要有 1)")
            else if (1 !in counts && counts.isNotEmpty()) node.err("counts", "必須包含 1 人的隻數")
            val interval = node.int("interval-ticks", min = 0, max = 1200) ?: 10
            if (stage != null && enemy != null && group != null && trigger != null && counts.isNotEmpty()) {
                out[id] = EncounterSpec(
                    id, stage, enemy, group, trigger, node.int("delay-ticks", min = 0, max = 6000) ?: 0, counts, interval,
                    node.idList("start-cues"), node.int("marker-offset", min = 0, max = 64) ?: 0,
                )
            }
        }
        return out
    }

    private fun parseTrigger(node: Node): Trigger? =
        when (val type = node.str("type", required = true)) {
            null -> null
            "stage-start" -> { node.only(setOf("type")); Trigger.StageStart }
            "object-state" -> {
                node.only(setOf("type", "object", "state"))
                val obj = node.id("object", required = true)
                val state = node.id("state", required = true)
                if (obj != null && state != null) Trigger.ObjectState(obj, state) else null
            }
            "encounter-cleared" -> {
                node.only(setOf("type", "encounter"))
                node.id("encounter", required = true)?.let(Trigger::EncounterCleared)
            }
            else -> { node.err("type", "未知的觸發「$type」(可用:stage-start、object-state、encounter-cleared)"); null }
        }

    // ---- tool.yml ----

    private fun parseTools(block: Node?): Map<String, ToolSpec> {
        val out = LinkedHashMap<String, ToolSpec>()
        block?.entries()?.forEach { (id, node) ->
            node.only(
                setOf(
                    "type", "material", "item-model", "name", "lore", "slot", "charges", "start-charges", "cone-angle", "range",
                    "stun-seconds", "cooldown-ticks", "refill-on-dissolve", "cues", "hud-text", "passive-regen-seconds", "zone-regen",
                ),
            )
            val type = node.str("type") ?: "flash"
            if (type != "flash") node.err("type", "未知的道具類型「$type」(目前只有 flash)")
            val material = node.str("material", required = true)
            val name = node.str("name", required = true)
            val charges = node.int("charges", min = 1, max = 64, required = true)
            val start = node.int("start-charges", min = 0, max = 64)
            if (charges != null && start != null && start > charges) node.err("start-charges", "不可大於 charges($charges)")
            val cone = node.num("cone-angle", min = 1.0, max = 180.0, required = true)
            val range = node.num("range", min = 0.0, exclusiveMin = true, required = true)
            val stun = node.num("stun-seconds", min = 0.0, exclusiveMin = true, required = true)
            val cues = node.child("cues")
            cues?.only(setOf("flash", "empty", "drained", "gain"))
            val passive = node.num("passive-regen-seconds", min = 1.0, max = 600.0)
            val zone = node.child("zone-regen")?.let { z ->
                z.only(setOf("seconds", "radius", "states", "markers"))
                val seconds = z.num("seconds", min = 0.5, max = 600.0, required = true)
                val zoneRadius = z.num("radius", min = 0.0, max = 64.0, exclusiveMin = true, required = true)
                val states = LinkedHashSet<String>()
                if (z.map["states"] == null) states += setOf(ObjectStates.LANTERN_BURNING, ObjectStates.LANTERN_LIT)
                else z.idList("states").forEach { st ->
                    if (st != ObjectStates.LANTERN_BURNING && st != ObjectStates.LANTERN_LIT) z.err("states", "「$st」不能當光源區狀態(可用:burning、lit)") else states += st
                }
                val markers = z.idList("markers")
                if (seconds != null && zoneRadius != null) ZoneRegen(seconds, zoneRadius, states, markers) else null
            }
            if (material != null && !MATERIAL.matches(material)) node.err("material", "材質名稱「$material」格式不合法(例如 LANTERN)")
            if (material != null && name != null && charges != null && cone != null && range != null && stun != null) {
                out[id] = ToolSpec(
                    id, material.uppercase(), node.str("item-model"), name, node.strList("lore"),
                    node.int("slot", min = 0, max = 8) ?: 0, charges, start ?: charges, cone, range, stun,
                    node.int("cooldown-ticks", min = 0, max = 1200) ?: 10,
                    node.int("refill-on-dissolve", min = 0, max = 64) ?: 0,
                    cues?.idList("flash") ?: emptyList(), cues?.idList("empty") ?: emptyList(), node.str("hud-text"),
                    passive, zone, cues?.idList("drained") ?: emptyList(), cues?.idList("gain") ?: emptyList(),
                )
            }
        }
        return out
    }

    // ---- hints.yml ----

    private fun parseHints(block: Node?): Map<String, HintSpec> {
        val out = LinkedHashMap<String, HintSpec>()
        block?.entries()?.forEach { (id, node) ->
            node.only(setOf("trigger", "stage", "cues", "once", "repeat-seconds"))
            val t = node.child("trigger", required = true)
            val trigger: HintTrigger? = if (t == null) null else when (val type = t.str("type", required = true)) {
                null -> null
                "first-whiff" -> { t.only(setOf("type")); HintTrigger.FirstWhiff }
                "no-progress" -> {
                    t.only(setOf("type", "seconds"))
                    t.num("seconds", min = 0.0, exclusiveMin = true, required = true)?.let(HintTrigger::NoProgress)
                }
                "near-marker" -> {
                    t.only(setOf("type", "marker", "radius"))
                    val marker = t.id("marker", required = true)
                    val radius = t.num("radius", min = 0.0, exclusiveMin = true, required = true)
                    if (marker != null && radius != null) HintTrigger.NearMarker(marker, radius) else null
                }
                "tool-empty" -> { t.only(setOf("type")); HintTrigger.ToolEmpty }
                "unequipped-near-enemy" -> {
                    t.only(setOf("type", "radius"))
                    t.num("radius", min = 0.0, max = 64.0, exclusiveMin = true, required = true)?.let(HintTrigger::UnequippedNearEnemy)
                }
                "lantern-contested" -> {
                    t.only(setOf("type", "seconds"))
                    t.num("seconds", min = 0.0, max = 600.0, exclusiveMin = true, required = true)?.let(HintTrigger::LanternContested)
                }
                else -> { t.err("type", "未知的提示觸發「$type」(可用:first-whiff、no-progress、near-marker、tool-empty、unequipped-near-enemy、lantern-contested)"); null }
            }
            val cues = node.idList("cues")
            if (cues.isEmpty() && node.map["cues"] == null) node.err("cues", "缺少必要欄位(要播的 cue ID 清單)")
            if (trigger != null) {
                out[id] = HintSpec(
                    id, trigger, node.id("stage"), cues, node.bool("once") ?: true,
                    node.num("repeat-seconds", min = 0.0, exclusiveMin = true) ?: 30.0,
                )
            }
        }
        return out
    }

    private companion object {
        val ASSET = Regex("[a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*")
        val MATERIAL = Regex("[A-Za-z0-9_:]+")
        const val MAX_LIGHT_CELLS = 2500
        const val MIN_SCAN_TICKS = 4
        const val MAX_SCAN_TICKS = 100
        val BAR_COLORS = setOf("BLUE", "GREEN", "PINK", "PURPLE", "RED", "WHITE", "YELLOW")
    }
}
