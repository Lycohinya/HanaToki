package com.tinyyana.hanatoki.api

import java.util.UUID

/**
 * ARCH §4:integration 註冊給 HanaToki 呼叫(HanaToki 在 stage 進出時呼叫)。缺席時無聲降級——
 * 不查表就直接 no-op,不記警告(BGM 缺席不是需要維運注意的事件,跟 [RewardSink] 缺席不同級別)。
 */
fun interface MusicCue {
    fun cue(playerId: UUID, cueId: String)
}
