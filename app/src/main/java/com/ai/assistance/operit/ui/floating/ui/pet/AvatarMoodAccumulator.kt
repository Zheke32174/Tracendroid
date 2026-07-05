package com.ai.assistance.operit.ui.floating.ui.pet

import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import kotlin.math.exp

/**
 * 跨轮次的情绪累积器（会话感知的心情状态机）。
 *
 * 目标：让 avatar 的心情能够“延续”而不是每条消息都从头判断。
 * 单条含糊/中性的消息不再把 avatar 直接打回 IDLE，而是继承最近累积出的
 * 主导心情（例如连续几条开心的消息之后，一句平淡的话仍保持 happy）；
 * 但一条明确的强信号（mood 标签或关键词命中）依然能立即覆盖。
 *
 * 实现方式：记录最近若干条 (情绪, 时间戳, 权重) 观测，按时间做指数衰减，
 * 汇总出当前主导心情。这是纯内存的单例，不做持久化——心情本身是短时的
 * 会话上下文，重启后从中性开始是合理的（现有 prefs 基础设施也没有天然的
 * per-avatar 心情槽位可复用）。所有方法都在主线程的消息处理路径上串行调用，
 * 无需额外同步。
 *
 * 仅使用既有的 7 个 [AvatarEmotion] 值；不引入任何新的枚举常量。
 */
object AvatarMoodAccumulator {

    /** 单条情绪观测。 */
    private data class MoodSample(
        val emotion: AvatarEmotion,
        val timestampMs: Long,
        val weight: Float
    )

    /**
     * 视为“真实心情”的情绪集合。IDLE/THINKING/LISTENING 属于瞬态/系统状态，
     * 不参与累积，也不会成为被继承的主导心情。
     */
    private val moodEmotions: Set<AvatarEmotion> =
        setOf(
            AvatarEmotion.HAPPY,
            AvatarEmotion.SAD,
            AvatarEmotion.CONFUSED,
            AvatarEmotion.SURPRISED
        )

    /** 半衰期（毫秒）：约一分钟后一次观测的贡献衰减到一半。 */
    private const val HALF_LIFE_MS: Double = 60_000.0

    /** 明确信号（mood 标签 / 关键词命中）的记录权重。 */
    private const val EXPLICIT_WEIGHT: Float = 1.0f

    /** 弱信号（仅标点回退）的记录权重。 */
    private const val WEAK_WEIGHT: Float = 0.5f

    /**
     * 让含糊消息继承主导心情所需的最低衰减后强度。
     * 低于该阈值说明近期没有足够的心情动量，不做偏置。
     */
    private const val INHERIT_THRESHOLD: Float = 0.35f

    /** 最多保留的观测条数，避免无界增长。 */
    private const val MAX_SAMPLES: Int = 32

    private val samples = ArrayDeque<MoodSample>()

    /**
     * 用一条推理结果更新累积器，并返回应当实际播放的情绪。
     *
     * - 明确的心情信号（`inference.isExplicit == true` 且情绪属于 [moodEmotions]）：
     *   记录该观测，并原样返回——强信号覆盖累积状态。
     * - 弱信号 / IDLE：先不记录，查询当前主导心情；若其衰减后强度达到阈值，
     *   则返回主导心情（继承），否则返回原始推理情绪。
     *
     * @param inference [AvatarEmotionManager.analyzeEmotionDetailed] 的结果。
     * @param nowMs 当前时间戳，默认取系统时间（测试可注入）。
     */
    fun accept(
        inference: AvatarEmotionManager.EmotionInference,
        nowMs: Long = System.currentTimeMillis()
    ): AvatarEmotion {
        val emotion = inference.emotion
        val isMood = emotion in moodEmotions

        if (inference.isExplicit && isMood) {
            record(emotion, EXPLICIT_WEIGHT, nowMs)
            return emotion
        }

        // 弱信号：先看看能否继承最近的主导心情。
        val (dominant, strength) = dominantMood(nowMs)
        if (dominant != null && strength >= INHERIT_THRESHOLD) {
            // 以较低权重强化被继承的心情，让连续的中性消息维持动量但仍会缓慢衰减。
            record(dominant, WEAK_WEIGHT, nowMs)
            return dominant
        }

        // 没有可继承的心情：若这是个弱心情信号也顺手记录一笔，否则原样返回。
        if (isMood) {
            record(emotion, WEAK_WEIGHT, nowMs)
        }
        return emotion
    }

    /**
     * 返回当前主导心情及其衰减后的累积强度；无有效心情时 emotion 为 null。
     */
    fun dominantMood(nowMs: Long = System.currentTimeMillis()): Pair<AvatarEmotion?, Float> {
        if (samples.isEmpty()) return null to 0f
        val totals = HashMap<AvatarEmotion, Float>()
        for (sample in samples) {
            val age = (nowMs - sample.timestampMs).coerceAtLeast(0L)
            val decay = exp(-age.toDouble() / HALF_LIFE_MS * LN2).toFloat()
            val contribution = sample.weight * decay
            if (contribution <= 0f) continue
            totals[sample.emotion] = (totals[sample.emotion] ?: 0f) + contribution
        }
        var bestEmotion: AvatarEmotion? = null
        var bestStrength = 0f
        for ((emotion, strength) in totals) {
            if (strength > bestStrength) {
                bestStrength = strength
                bestEmotion = emotion
            }
        }
        return bestEmotion to bestStrength
    }

    /** 清空累积状态（例如切换会话/角色时）。 */
    fun reset() {
        samples.clear()
    }

    private fun record(emotion: AvatarEmotion, weight: Float, nowMs: Long) {
        samples.addLast(MoodSample(emotion, nowMs, weight))
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }
    }

    /** ln(2)，用于把半衰期换算成指数衰减常数。 */
    private const val LN2: Double = 0.6931471805599453
}
