package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.config.DungeonDefinition
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind

/**
 * 跨檔參照與場景核對(純函式,不碰 Bukkit)。回傳錯誤清單,空 = 通過。錯誤格式同 [ContentDataException]:
 * 「檔名 路徑: 訊息」。呼叫端把所有清單串起來,有任何一筆就拒絕整份內容,不半載入。
 */
object ContentValidator {

    /** 檔案內部的參照:stage、object、encounter、enemy 是否存在,狀態名是否合法,stage 鏈是否成立。 */
    fun validateReferences(spec: ContentSpec): List<String> {
        val errors = ArrayList<String>()
        val stageIds = spec.stages.map { it.id }.toSet()
        if (spec.stages.map { it.id }.size != stageIds.size) errors += "${ContentFileNames.STAGES} stages: stage ID 重複"

        fun stateOk(objectId: String, state: String): String? {
            val obj = spec.objects[objectId] ?: return "引用不存在的物件「$objectId」(objects.yml)"
            val valid = ObjectStates.valid(obj)
            return if (state in valid) null else "物件「$objectId」沒有狀態「$state」(${obj::class.simpleName} 的狀態:${valid.joinToString()})"
        }

        // stages.yml
        for (stage in spec.stages) {
            val where = "${ContentFileNames.STAGES} stages.${stage.id}"
            if (stage.next != null && stage.next !in stageIds) errors += "$where.next: 引用不存在的 stage「${stage.next}」"
            if (stage.next == stage.id) errors += "$where.next: 不可指向自己"
            stage.complete.forEachIndexed { i, c ->
                when (c) {
                    is Condition.EncounterCleared ->
                        if (c.encounter !in spec.encounters) errors += "$where.complete[$i].encounter: 引用不存在的波次「${c.encounter}」(encounters.yml)"
                    is Condition.ObjectState -> stateOk(c.objectId, c.state)?.let { errors += "$where.complete[$i]: $it" }
                    is Condition.Reach -> {}
                    is Condition.BossDefeated ->
                        if (c.boss !in spec.bosses) errors += "$where.complete[$i].boss: 引用不存在的 boss「${c.boss}」(bosses.yml)"
                    Condition.Powered, is Condition.BossKneel, Condition.PoweredUsed, is Condition.EnemyDispersed, Condition.Arrived, Condition.LanternBurning ->
                        errors += "$where.complete[$i]: ${c::class.simpleName} 會來去或是累積旗標,不能當 stage 的完成條件(只能用在 objective)"
                }
            }
            // on-complete.opens:要打開的 gate 物件
            stage.onComplete.opens.forEachIndexed { i, gate ->
                if (spec.objects[gate] !is GateSpec) errors += "$where.on-complete.opens[$i]: 「$gate」不是 gate 物件(objects.yml)"
            }
            // arrive／on-arrive(rc.26)
            if (stage.onArrive.opens.isNotEmpty()) errors += "$where.on-arrive.opens: on-arrive 不能寫 opens"
            if (stage.onArrive.dialogs.isNotEmpty() && stage.arrive == null) errors += "$where.on-arrive.dialogs: 沒有 arrive 的 stage 不會觸發 on-arrive"
            if (stage.complete.isEmpty() && stage.next != null) errors += "$where.complete: 有下一段(next)的 stage 必須寫完成條件"
            if (stage.resolve != null && stage.next != null) errors += "$where.resolve: 有下一段(next)的 stage 不能結算"
            // objective 的 until:跟 complete 同一套條件,引用要存在;reach 只有 stage.complete 裡探測得到的 marker 才會達成
            fun objectiveCondition(at: String, c: Condition?) {
                when (c) {
                    null -> {}
                    is Condition.EncounterCleared ->
                        if (c.encounter !in spec.encounters) errors += "$at.encounter: 引用不存在的波次「${c.encounter}」(encounters.yml)"
                    is Condition.ObjectState -> stateOk(c.objectId, c.state)?.let { errors += "$at: $it" }
                    is Condition.Reach ->
                        if (stage.complete.none { it is Condition.Reach && it.marker == c.marker }) {
                            errors += "$at.marker: reach 只有寫在這個 stage 的 complete 裡的 marker「${c.marker}」才會被偵測"
                        }
                    is Condition.BossDefeated ->
                        if (c.boss !in spec.bosses) errors += "$at.boss: 引用不存在的 boss「${c.boss}」(bosses.yml)"
                    Condition.Powered, Condition.PoweredUsed ->
                        if (spec.objects.values.none { it is PowerUpSpec }) errors += "$at: ${c::class.simpleName} 需要至少一個 power-up 物件(objects.yml),否則永遠不會成立"
                    is Condition.EnemyDispersed ->
                        if (c.enemy !in spec.enemies) errors += "$at.enemy: 引用不存在的敵人「${c.enemy}」(enemies.yml)"
                    Condition.Arrived ->
                        if (stage.arrive == null) errors += "$at: arrived 需要這個 stage 寫 arrive(沒有抵達區就一開始成立，不需要這個條件)"
                    Condition.LanternBurning ->
                        if (spec.objects.values.none { it is LanternSpec && it.stage == stage.id }) errors += "$at: lantern-burning 需要這個 stage 至少有一盞 lantern 物件(objects.yml stage: ${stage.id})"
                    is Condition.BossKneel -> {
                        val boss = spec.bosses[c.boss]
                        if (boss == null) errors += "$at.boss: 引用不存在的 boss「${c.boss}」(bosses.yml)"
                        else if (boss.stage != stage.id) errors += "$at.boss: boss「${c.boss}」在 stage「${boss.stage}」,這個 stage 看不到她跪下"
                    }
                }
            }
            stage.objective.forEachIndexed { i, step ->
                objectiveCondition("$where.objective[$i].until", step.until)
                objectiveCondition("$where.objective[$i].while", step.during)
                if (step.until == null && step.during == null && i != stage.objective.lastIndex) {
                    errors += "$where.objective[$i].until: 沒寫 until 的步驟永遠停在這一步,只能放最後一個(有 while 的步驟不受限)"
                }
            }
        }
        // stage 鏈:從第一段沿 next 走,不可繞圈,且每個 stage 都走得到
        val seen = LinkedHashSet<String>()
        var cursor: String? = spec.stages.firstOrNull()?.id
        while (cursor != null) {
            if (!seen.add(cursor)) { errors += "${ContentFileNames.STAGES} stages.$cursor: stage 鏈繞圈了"; break }
            cursor = spec.stage(cursor)?.next
        }
        for (id in stageIds - seen) errors += "${ContentFileNames.STAGES} stages.$id: 從起始 stage「${spec.startStage}」沿 next 走不到"

        // objects.yml
        for (obj in spec.objects.values) {
            val where = "${ContentFileNames.OBJECTS} objects.${obj.id}"
            if (obj.stage != null && obj.stage !in stageIds) errors += "$where.stage: 引用不存在的 stage「${obj.stage}」"
            if (obj is LanternSpec) {
                val opens = obj.onLit.opens
                if (opens != null && spec.objects[opens] !is GateSpec) errors += "$where.on-lit.opens: 「$opens」不是 gate 物件"
                val needTool = obj.hint?.requiresTool
                if (needTool != null && needTool !in spec.tools) errors += "$where.hint.requires-tool: 引用不存在的道具「$needTool」(tool.yml)"
            }
        }

        // enemies.yml:seeks-object 要指向 lantern、狀態合法;harasses-players 的 tool-charges 要指向存在的道具
        for (enemy in spec.enemies.values) {
            val where = "${ContentFileNames.ENEMIES} enemies.${enemy.id}"
            enemy.seeksObject?.let { seek ->
                val obj = spec.objects[seek.objectId]
                if (obj == null) errors += "$where.seeks-object.object: 引用不存在的物件「${seek.objectId}」(objects.yml)"
                else if (obj !is LanternSpec) errors += "$where.seeks-object.object: 「${seek.objectId}」不是 lantern 物件(只有燈籠能被啃)"
                else {
                    for (st in seek.states) if (st !in ObjectStates.LANTERN) errors += "$where.seeks-object.states: 燈籠沒有狀態「$st」(可用:${ObjectStates.LANTERN.joinToString()})"
                }
            }
            enemy.harassesPlayers?.effects?.forEachIndexed { i, effect ->
                if (effect is HarassEffect.ToolCharges && effect.tool !in spec.tools) {
                    errors += "$where.harasses-players.effects[$i].tool: 引用不存在的道具「${effect.tool}」(tool.yml)"
                }
            }
            enemy.chasesPlayers?.contact?.effects?.forEachIndexed { i, effect ->
                if (effect is HarassEffect.ToolCharges && effect.tool !in spec.tools) {
                    errors += "$where.chases-players.contact.effects[$i].tool: 引用不存在的道具「${effect.tool}」(tool.yml)"
                }
            }
            if (enemy.fleesWhenPowered != null && spec.objects.values.none { it is PowerUpSpec }) {
                errors += "$where.flees-when-powered: 沒有任何 power-up 物件(objects.yml)可以讓成員 powered,這個規則永遠不會動"
            }
        }

        errors += validateBosses(spec, stageIds)

        // encounters.yml
        for (enc in spec.encounters.values) {
            val where = "${ContentFileNames.ENCOUNTERS} encounters.${enc.id}"
            if (enc.stage !in stageIds) errors += "$where.stage: 引用不存在的 stage「${enc.stage}」"
            if (enc.enemy !in spec.enemies) errors += "$where.enemy: 引用不存在的敵人「${enc.enemy}」(enemies.yml)"
            when (val t = enc.trigger) {
                is Trigger.ObjectState -> stateOk(t.objectId, t.state)?.let { errors += "$where.trigger: $it" }
                is Trigger.EncounterCleared -> {
                    if (t.encounter !in spec.encounters) errors += "$where.trigger.encounter: 引用不存在的波次「${t.encounter}」"
                    if (t.encounter == enc.id) errors += "$where.trigger.encounter: 不可觸發自己"
                }
                Trigger.StageStart -> {}
                Trigger.StageArrive ->
                    if (spec.stage(enc.stage)?.arrive == null) errors += "$where.trigger: stage-arrive 需要所屬 stage「${enc.stage}」寫 arrive(沒有抵達區請用 stage-start)"
            }
        }
        // 波次觸發鏈不可繞圈
        for (enc in spec.encounters.values) {
            val chain = HashSet<String>()
            var cur: EncounterSpec? = enc
            while (cur != null) {
                if (!chain.add(cur.id)) { errors += "${ContentFileNames.ENCOUNTERS} encounters.${enc.id}.trigger: 波次觸發鏈繞圈"; break }
                cur = (cur.trigger as? Trigger.EncounterCleared)?.let { spec.encounters[it.encounter] }
            }
        }

        // hints.yml
        for (hint in spec.hints.values) {
            val where = "${ContentFileNames.HINTS} hints.${hint.id}"
            if (hint.stage != null && hint.stage !in stageIds) errors += "$where.stage: 引用不存在的 stage「${hint.stage}」"
            if (hint.requiresArrival && (hint.stage == null || spec.stage(hint.stage)?.arrive == null)) {
                errors += "$where.requires-arrival: 需要寫 stage，而且那個 stage 要有 arrive"
            }
            (hint.trigger as? HintTrigger.EnemyDispersed)?.let { t ->
                if (t.enemy !in spec.enemies) errors += "$where.trigger.enemy: 引用不存在的敵人「${t.enemy}」(enemies.yml)"
            }
            if (hint.trigger is HintTrigger.LanternContested && spec.enemies.values.none { it.seeksObject != null }) {
                errors += "$where.trigger: lantern-contested 需要至少一種敵人有 seeks-object 規則"
            }
            if ((hint.trigger is HintTrigger.ToolEmpty || hint.trigger is HintTrigger.UnequippedNearEnemy) && spec.tools.isEmpty()) {
                errors += "$where.trigger: 這種提示要有局內道具(tool.yml 沒有任何道具)"
            }
        }
        // tool.yml:光源區回補只認 burning／lit(unlit 的燈籠沒有光)
        for (tool in spec.tools.values) {
            val zone = tool.zoneRegen ?: continue
            if (zone.states.isEmpty() && zone.markers.isEmpty()) {
                errors += "${ContentFileNames.TOOL} tools.${tool.id}.zone-regen: states 與 markers 不可都是空的"
            }
            if (zone.states.isNotEmpty() && spec.objects.values.none { it is LanternSpec }) {
                errors += "${ContentFileNames.TOOL} tools.${tool.id}.zone-regen.states: 沒有任何 lantern 物件可以當光源區(改用 markers 或加燈籠)"
            }
        }
        return errors.distinct()
    }

    /** bosses.yml 的跨檔參照:stage、場邊燈(lantern 物件)、南瓜燈(power-up 物件)、召喚的敵人。 */
    private fun validateBosses(spec: ContentSpec, stageIds: Set<String>): List<String> {
        val errors = ArrayList<String>()
        for ((stageId, list) in spec.bosses.values.groupBy { it.stage }) {
            if (list.size > 1) errors += "${ContentFileNames.BOSSES} bosses: stage「$stageId」有 ${list.size} 個 boss(${list.joinToString { it.id }}),一個 stage 只能有一個"
        }
        val lampOwner = HashMap<String, String>()
        for (boss in spec.bosses.values) {
            val where = "${ContentFileNames.BOSSES} bosses.${boss.id}"
            if (boss.stage !in stageIds) errors += "$where.stage: 引用不存在的 stage「${boss.stage}」"
            if (boss.intro?.awaitArriveDialogs == true && spec.stage(boss.stage)?.onArrive?.dialogs.isNullOrEmpty()) {
                errors += "$where.intro.await-arrive-dialogs: stage「${boss.stage}」的 on-arrive 沒有 dialogs 可等"
            }
            if (boss.moves.values.none { it.fromPhase == 1 }) errors += "$where.moves: 至少要有一招從 phase 1 就開放"
            boss.gems.forEachIndexed { i, gem ->
                val obj = spec.objects[gem.lamp]
                val at = "$where.gems[$i].lamp"
                if (obj == null) { errors += "$at: 引用不存在的物件「${gem.lamp}」(objects.yml)"; return@forEachIndexed }
                if (obj !is LanternSpec) { errors += "$at: 「${gem.lamp}」不是 lantern 物件"; return@forEachIndexed }
                if (obj.stage != null && obj.stage != boss.stage) errors += "$at: 場邊燈「${gem.lamp}」的 stage 是 ${obj.stage},必須省略或等於 boss 的 stage「${boss.stage}」"
                if (obj.progressBar != null) errors += "$at: 場邊燈不能有 progress-bar(它和 boss 血量條共用同一條 boss bar)"
                if (obj.onLit.lightRadius > 0.0) errors += "$at: 場邊燈的 on-lit.light-radius 必須是 0(被熄掉時放出的光源方塊不會收回)"
                if (obj.onLit.opens != null) errors += "$at: 場邊燈不能有 on-lit.opens(它會被重置,門只能開一次)"
                if (obj.onLit.clearsEnemies) errors += "$at: 場邊燈不能有 on-lit.clears-enemies"
                lampOwner.put(gem.lamp, boss.id)?.let { if (it != boss.id) errors += "$at: 場邊燈「${gem.lamp}」已被 boss「$it」使用" }
            }
            boss.pumpkins?.objects?.forEachIndexed { i, id ->
                val obj = spec.objects[id]
                val at = "$where.pumpkins.objects[$i]"
                if (obj == null) errors += "$at: 引用不存在的物件「$id」(objects.yml)"
                else if (obj !is PowerUpSpec) errors += "$at: 「$id」不是 power-up 物件"
                else if (obj.stage != null && obj.stage != boss.stage) errors += "$at: power-up「$id」的 stage 是 ${obj.stage},必須省略或等於 boss 的 stage「${boss.stage}」"
            }
            for (move in boss.moves.values) {
                val s = move.shape
                if (s is MoveShape.Summon && s.enemy !in spec.enemies) {
                    errors += "$where.moves.${move.id}.enemy: 引用不存在的敵人「${s.enemy}」(enemies.yml)"
                }
                if (s is MoveShape.Snuff && boss.gems.isEmpty()) errors += "$where.moves.${move.id}: snuff 需要場邊燈(gems)"
            }
        }
        return errors
    }

    /**
     * 場景 marker 核對。[markers] 是整張圖(所有 piece 轉換後)的 marker,座標任意(只看種類、ID、屬性)。
     * 這裡的錯誤在註冊時就擋下,不等到玩家進場才發現「少一個出生點」。
     */
    fun validateScene(spec: ContentSpec, markers: List<MapMarker>): List<String> {
        val errors = ArrayList<String>()
        val byId = LinkedHashMap<String, MutableList<MapMarker>>()
        for (m in markers) {
            val id = m.id ?: continue
            if (!ContentIds.valid(id)) errors += "scene.yml marker「${m.kind.id} id=$id」(${m.asset.key}): ID 不合法:${ContentIds.RULE}"
            byId.getOrPut(id) { ArrayList() } += m
        }
        for ((id, list) in byId) if (list.size > 1) {
            errors += "scene.yml marker「$id」重複 ${list.size} 次(${list.joinToString { "${it.kind.id}@${it.asset.key}" }}):marker ID 在整張圖必須唯一"
        }
        fun marker(id: String): MapMarker? = byId[id]?.firstOrNull()

        val entry = marker(spec.scene.entry)
        if (entry == null) errors += "scene.yml entry: 找不到 player_spawn id=${spec.scene.entry}"
        else if (entry.kind != MarkerKind.PLAYER_SPAWN) errors += "scene.yml entry: marker「${spec.scene.entry}」是 ${entry.kind.id},必須是 player_spawn"

        for (obj in spec.objects.values) {
            val where = "${ContentFileNames.OBJECTS} objects.${obj.id}.marker"
            val m = marker(obj.markerId)
            if (m == null) { errors += "$where: 場景裡沒有 id=${obj.markerId} 的 marker"; continue }
            when (obj) {
                is LanternSpec -> if (m.kind != MarkerKind.TRIGGER) errors += "$where: lantern 的 marker 必須是 trigger(「${obj.markerId}」是 ${m.kind.id})"
                is PowerUpSpec -> if (m.kind != MarkerKind.TRIGGER) errors += "$where: power-up 的 marker 必須是 trigger(「${obj.markerId}」是 ${m.kind.id})"
                is GateSpec -> {
                    if (m.kind != MarkerKind.DOOR) { errors += "$where: gate 的 marker 必須是 door(「${obj.markerId}」是 ${m.kind.id})"; continue }
                    if (m.facing == null || m.facing !in setOf(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
                        errors += "$where: door marker「${obj.markerId}」必須有水平的 facing=north/south/east/west"
                    }
                    val w = m.properties["w"]?.toIntOrNull()
                    val h = m.properties["h"]?.toIntOrNull()
                    if (w == null || w < 1 || h == null || h < 1) errors += "$where: door marker「${obj.markerId}」必須有 w=<寬> h=<高>(>= 1 的整數)"
                }
            }
        }
        for (stage in spec.stages) stage.complete.forEachIndexed { i, c ->
            if (c is Condition.Reach) {
                val m = marker(c.marker)
                if (m == null) errors += "${ContentFileNames.STAGES} stages.${stage.id}.complete[$i].marker: 場景裡沒有 id=${c.marker} 的 marker"
                else if (m.kind != MarkerKind.TRIGGER) errors += "${ContentFileNames.STAGES} stages.${stage.id}.complete[$i].marker: reach 的 marker 必須是 trigger(「${c.marker}」是 ${m.kind.id})"
            }
        }
        for (stage in spec.stages) stage.arrive?.let { a ->
            if (marker(a.marker) == null) errors += "${ContentFileNames.STAGES} stages.${stage.id}.arrive.marker: 場景裡沒有 id=${a.marker} 的 marker"
        }
        for (boss in spec.bosses.values) {
            val where = "${ContentFileNames.BOSSES} bosses.${boss.id}"
            fun need(path: String, id: String) { if (marker(id) == null) errors += "$where.$path: 場景裡沒有 id=$id 的 marker" }
            boss.points.forEachIndexed { i, id -> need("points[$i]", id) }
            boss.downed.revivePoints.forEachIndexed { i, id -> need("downed.revive-points[$i]", id) }
            need("wipe.checkpoint", boss.wipe.checkpoint)
            boss.intro?.let { intro ->
                need("intro.marker", intro.marker)
                intro.glideTo?.let { need("intro.then-glide-to", it) }
            }
            for (move in boss.moves.values) {
                move.origin.marker?.let { need("moves.${move.id}.origin-marker", it) }
                (move.shape as? MoveShape.Summon)?.markers?.forEachIndexed { i, id -> need("moves.${move.id}.markers[$i]", id) }
            }
        }
        for (stage in spec.stages) stage.resolve?.epilogue?.forEachIndexed { i, step ->
            val id = step.marker ?: return@forEachIndexed
            if (marker(id) == null) errors += "${ContentFileNames.STAGES} stages.${stage.id}.resolve.epilogue[$i].marker: 場景裡沒有 id=$id 的 marker"
        }
        for (enc in spec.encounters.values) {
            if (enc.spawnMarker != null) {
                if (marker(enc.spawnMarker) == null) errors += "${ContentFileNames.ENCOUNTERS} encounters.${enc.id}.spawn-marker: 場景裡沒有 id=${enc.spawnMarker} 的 marker"
                continue
            }
            val spawns = markers.filter { it.kind == MarkerKind.MOB_SPAWN && it.properties["group"] == enc.markerGroup }
            if (spawns.isEmpty()) errors += "${ContentFileNames.ENCOUNTERS} encounters.${enc.id}.marker-group: 場景裡沒有 mob_spawn group=${enc.markerGroup}"
        }
        for (hint in spec.hints.values) {
            val t = hint.trigger
            if (t is HintTrigger.NearMarker && marker(t.marker) == null) {
                errors += "${ContentFileNames.HINTS} hints.${hint.id}.trigger.marker: 場景裡沒有 id=${t.marker} 的 marker"
            }
        }
        for (tool in spec.tools.values) tool.zoneRegen?.markers?.forEachIndexed { i, id ->
            if (marker(id) == null) errors += "${ContentFileNames.TOOL} tools.${tool.id}.zone-regen.markers[$i]: 場景裡沒有 id=$id 的 marker"
        }
        return errors
    }

    /**
     * 跟 dungeons.yml 的對帳:同一個 ID、stage 圖一致、進場落點就是 player_spawn、有道具就必須開局內背包。
     * [entry] 是 entry marker 相對 slot anchor 的座標(場景以 anchor 為原點排版時的 marker 座標)。
     */
    fun validateDungeon(spec: ContentSpec, def: DungeonDefinition, entry: MapMarker?): List<String> {
        val errors = ArrayList<String>()
        val where = "dungeons.yml dungeons.${def.id}"
        if (def.id != spec.dungeonId) errors += "stages.yml dungeon: 「${spec.dungeonId}」和 dungeons.yml 的「${def.id}」不同"
        val graph = def.stageGraph
        if (graph == null) {
            errors += "$where.stages: 缺少 stages.start/list(引擎沒有 stage 圖就不會執行任何 stage)"
        } else {
            val declared = spec.stages.map { it.id }.toSet()
            if (graph.startStage != spec.startStage) errors += "$where.stages.start: 「${graph.startStage}」必須等於 stages.yml 的第一個 stage「${spec.startStage}」"
            for (id in declared - graph.stages.keys) errors += "$where.stages.list: 缺少 stages.yml 宣告的 stage「$id」"
            for (id in graph.stages.keys - declared) errors += "$where.stages.list: 有 stages.yml 沒宣告的 stage「$id」"
        }
        if (spec.tools.isNotEmpty() && def.instanceInventory == null) {
            errors += "$where.instance-inventory: tool.yml 有局內道具,dungeons.yml 必須啟用 instance-inventory(例如 enabled: true、loadout: [])才蓋得出 instance 章"
        }
        if (entry != null) {
            val fx = Math.floor(def.spawnOffsetX).toInt()
            val fy = Math.floor(def.spawnOffsetY).toInt()
            val fz = Math.floor(def.spawnOffsetZ).toInt()
            if (fx != entry.x || fy != entry.y || fz != entry.z) {
                errors += "$where.spawn-offset-x/y/z: (${def.spawnOffsetX}, ${def.spawnOffsetY}, ${def.spawnOffsetZ}) 沒有落在 player_spawn id=${spec.scene.entry} 相對 anchor 的位置 (${entry.x}, ${entry.y}, ${entry.z});引擎進場傳送用這個偏移,對不上玩家會掉進虛空"
            }
        }
        return errors
    }

    /** 文案 key 是否存在。[has] 查內容層 texts 與引擎 messages.yml。 */
    fun validateTexts(spec: ContentSpec, has: (String) -> Boolean): List<String> {
        val errors = ArrayList<String>()
        fun check(file: String, path: String, key: String?) {
            if (key != null && !has(key)) errors += "$file $path: 找不到文案 key「$key」(texts)"
        }
        for (s in spec.stages) {
            check(ContentFileNames.STAGES, "stages.${s.id}.on-start.title", s.onStart.titleKey)
            check(ContentFileNames.STAGES, "stages.${s.id}.on-start.subtitle", s.onStart.subtitleKey)
            check(ContentFileNames.STAGES, "stages.${s.id}.on-arrive.title", s.onArrive.titleKey)
            check(ContentFileNames.STAGES, "stages.${s.id}.on-arrive.subtitle", s.onArrive.subtitleKey)
            check(ContentFileNames.STAGES, "stages.${s.id}.on-complete.title", s.onComplete.titleKey)
            check(ContentFileNames.STAGES, "stages.${s.id}.on-complete.subtitle", s.onComplete.subtitleKey)
            s.objective.forEachIndexed { i, step -> check(ContentFileNames.STAGES, "stages.${s.id}.objective[$i].text", step.textKey) }
            check(ContentFileNames.STAGES, "stages.${s.id}.objective-done", s.objectiveDoneKey)
        }
        for (c in spec.chat.values) check(ContentFileNames.CHAT, "chat.${c.cueId}.text", c.textKey)
        for (t in spec.tools.values) {
            check(ContentFileNames.TOOL, "tools.${t.id}.name", t.nameKey)
            t.loreKeys.forEachIndexed { i, k -> check(ContentFileNames.TOOL, "tools.${t.id}.lore[$i]", k) }
            check(ContentFileNames.TOOL, "tools.${t.id}.hud-text", t.hudTextKey)
        }
        for (o in spec.objects.values) if (o is LanternSpec) {
            check(ContentFileNames.OBJECTS, "objects.${o.id}.progress-bar.text", o.progressBar?.textKey)
        }
        for (b in spec.bosses.values) {
            val f = ContentFileNames.BOSSES
            check(f, "bosses.${b.id}.bar.text", b.bar?.textKey)
            check(f, "bosses.${b.id}.downed.title", b.downed.titleKey)
            check(f, "bosses.${b.id}.downed.subtitle", b.downed.subtitleKey)
            check(f, "bosses.${b.id}.downed.hud-text", b.downed.hudTextKey)
            check(f, "bosses.${b.id}.downed.ally-hud-text", b.downed.allyHudTextKey)
            check(f, "bosses.${b.id}.wipe.title", b.wipe.titleKey)
            check(f, "bosses.${b.id}.wipe.subtitle", b.wipe.subtitleKey)
            b.phases.forEach { p ->
                check(f, "bosses.${b.id}.phases[${p.number - 1}].title", p.onEnter.titleKey)
                check(f, "bosses.${b.id}.phases[${p.number - 1}].subtitle", p.onEnter.subtitleKey)
            }
        }
        return errors
    }

    /** 不擋註冊、但值得讓維運看到的提醒。 */
    fun warnings(spec: ContentSpec): List<String> {
        val out = ArrayList<String>()
        for (e in spec.enemies.values) for (rule in e.rules) {
            if (rule in EnemyRules.RESERVED) out += "${ContentFileNames.ENEMIES} enemies.${e.id}.rules: 「$rule」是保留標籤,本版沒有行為"
        }
        for (e in spec.enemies.values) if (e.rules.isEmpty()) out += "${ContentFileNames.ENEMIES} enemies.${e.id}.rules: 沒有任何規則標籤,這個敵人就是一般會被打死的怪"
        for (t in spec.tools.values) if (t.passiveRegenSeconds == null) {
            out += "${ContentFileNames.TOOL} tools.${t.id}: 沒有 passive-regen-seconds,燈火歸零時只剩打散敵人能補(場上沒有可打的敵人就會卡死)"
        }
        return out
    }
}
