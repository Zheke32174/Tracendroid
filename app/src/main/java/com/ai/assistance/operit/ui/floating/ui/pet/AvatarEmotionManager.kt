package com.ai.assistance.operit.ui.floating.ui.pet

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.common.state.AvatarMoodTypes

/**
 * Avatar表情管理器
 * 从PetOverlayService迁移的表情推理逻辑
 */
object AvatarEmotionManager {

    /**
     * 从文本内容推理情感
     * 通过关键词/表情/标点匹配来判断应该使用哪种表情。
     *
     * 覆盖中英双语关键词、常见 emoji、颜文字以及标点情绪信号，
     * 尽量让普通句子（"great!" / "sorry" / "haha" / "?!"）也能映射到合适的表情，
     * 而不是一律回退到 IDLE。
     */
    fun inferEmotionFromText(text: String): AvatarEmotion {
        return inferEmotionDetailed(text).emotion
    }

    /**
     * 推理结果，附带信号强度。
     *
     * @param emotion 推理出的表情。
     * @param isExplicit 是否来自明确信号（mood 标签或关键词/emoji 命中）。
     *                   仅靠标点回退（"!" / "?"）得到的结果视为“弱”信号，
     *                   `isExplicit = false`，此时调用方可让最近的情绪延续下去，
     *                   而不是被一次含糊的消息重置。
     */
    data class EmotionInference(
        val emotion: AvatarEmotion,
        val isExplicit: Boolean
    )

    /**
     * 关键词/标点推理，同时返回信号是否“明确”。
     * 关键词或 emoji 命中记为明确；仅命中标点回退记为弱信号；IDLE 亦为弱信号。
     */
    fun inferEmotionDetailed(text: String): EmotionInference {
        val t = text.lowercase()

        // Happy: praise, thanks, laughter, positive interjections.
        val happyKeywords = listOf(
            // zh
            "开心", "高兴", "不错", "棒", "太好了", "赞", "喜欢", "谢谢", "哈哈", "嘻嘻", "好耶",
            // en
            "great", "awesome", "nice", "love it", "love you", "thank", "thanks", "haha",
            "lol", "yay", "amazing", "wonderful", "perfect", "well done", "good job", "happy",
            // emoji / kaomoji
            "😀", "🙂", "😊", "😄", "😃", "😁", "🥰", "😍", "❤", "👍", "🎉", "^_^", ":)", ":d"
        )
        // Sad: sorrow, apology, disappointment, crying.
        val sadKeywords = listOf(
            // zh
            "难过", "伤心", "沮丧", "忧伤", "哭", "对不起", "抱歉", "失望", "遗憾", "可惜",
            // en
            "sad", "sorry", "apolog", "unfortunately", "disappoint", "regret", "miss you",
            "cry", "crying", "depress", "lonely",
            // emoji / kaomoji
            "😭", "😢", "😔", "😞", "💔", "😥", "t_t", ":(", ";("
        )
        // Angry: anger, blame, strong dissatisfaction. Maps to SAD (existing behavior).
        val angryKeywords = listOf(
            // zh
            "生气", "愤怒", "气死", "讨厌", "糟糕", "怒", "烦",
            // en
            "angry", "furious", "annoyed", "hate", "terrible", "awful", "stupid", "damn",
            // emoji
            "😡", "😠", "🤬", ">:("
        )
        // Surprised: shock, exclamation, amazement.
        val surprisedKeywords = listOf(
            // zh
            "哇", "天哪", "居然", "竟然", "不会吧", "真的吗", "惊",
            // en
            "wow", "what?!", "no way", "really?", "omg", "oh my", "unbelievable", "surprise",
            // emoji / punctuation
            "😲", "😮", "😱", "🤯", "?!", "!?"
        )
        // Shy/confused: bashful, teasing, embarrassment, or plain confusion. Maps to CONFUSED.
        val confusedKeywords = listOf(
            // zh (shy)
            "害羞", "羞", "脸红", "不好意思", "///",
            // zh (confused)
            "困惑", "疑惑", "不懂", "什么意思", "为什么", "怎么办",
            // en (shy)
            "shy", "blush", "embarrass",
            // en (confused)
            "confused", "confusing", "don't understand", "not sure", "how come", "hmm", "huh",
            // emoji / kaomoji
            "😳", "😅", "🤔", "😕", "///"
        )

        fun containsAny(keys: List<String>): Boolean =
            keys.any { t.contains(it) || text.contains(it) }

        return when {
            // Keyword/emoji hits are explicit signals.
            containsAny(happyKeywords) -> EmotionInference(AvatarEmotion.HAPPY, isExplicit = true)
            // Angry and sad both fall back to SAD, preserving prior behavior.
            containsAny(angryKeywords) -> EmotionInference(AvatarEmotion.SAD, isExplicit = true)
            containsAny(sadKeywords) -> EmotionInference(AvatarEmotion.SAD, isExplicit = true)
            containsAny(surprisedKeywords) -> EmotionInference(AvatarEmotion.SURPRISED, isExplicit = true)
            containsAny(confusedKeywords) -> EmotionInference(AvatarEmotion.CONFUSED, isExplicit = true)
            // Sentiment-from-punctuation fallback (only when no keyword matched):
            // an exclamation usually reads as upbeat, a lone question as puzzled.
            // These are weak signals: isExplicit = false so recent mood may carry instead.
            text.contains("!") || text.contains("！") ->
                EmotionInference(AvatarEmotion.HAPPY, isExplicit = false)
            text.contains("?") || text.contains("？") ->
                EmotionInference(AvatarEmotion.CONFUSED, isExplicit = false)
            else -> EmotionInference(AvatarEmotion.IDLE, isExplicit = false)
        }
    }
    
    /**
     * 解析后的 mood 标签。
     *
     * @param key 归一化后的 mood 关键字（如 "happy"）。
     * @param weight 可选强度，取值 0f..1f。若标签未提供 weight 属性则为 null。
     */
    data class MoodTag(
        val key: String,
        val weight: Float? = null
    )

    /**
     * 从文本中提取带可选属性的 mood 标签。
     * 支持两种写法：
     *   <mood>happy</mood>
     *   <mood weight="0.8">happy</mood>
     *
     * 返回最后一个匹配到的标签（与旧行为一致）。weight 会被解析并暴露出来，
     * 但目前 [AvatarController] 接口不接受强度参数，因此调用方尚未使用它。
     * TODO: 当 AvatarController 支持强度/混合权重时，将 weight 透传到播放层。
     */
    fun extractMoodTag(text: String): MoodTag? {
        return try {
            // 属性部分 (\\s[^>]*)? 兼容 <mood weight="0.8"> 等写法；标签体捕获到下一个 '<'。
            val regex = Regex(
                "<mood(\\s[^>]*)?>([^<]+)</mood>",
                RegexOption.IGNORE_CASE
            )
            val all = regex.findAll(text).toList()
            if (all.isEmpty()) return null
            val match = all.last()
            val key = AvatarMoodTypes.normalizeKey(match.groupValues[2])
                .takeIf { it.isNotBlank() } ?: return null
            val weight = parseWeightAttribute(match.groupValues[1])
            MoodTag(key = key, weight = weight)
        } catch (_: Exception) { null }
    }

    /**
     * 从文本中提取mood标签的关键字（向后兼容的便捷方法）。
     * AI可能会在回复中包含<mood>标签来明确指定情感。
     */
    fun extractMoodTagValue(text: String): String? {
        return extractMoodTag(text)?.key
    }

    /**
     * 解析 mood 标签属性区中的 weight="x" 值，限制在 0f..1f。
     * 无属性或解析失败时返回 null。
     */
    private fun parseWeightAttribute(attributes: String): Float? {
        if (attributes.isBlank()) return null
        val weightRegex = Regex(
            "weight\\s*=\\s*\"?'?([0-9]*\\.?[0-9]+)",
            RegexOption.IGNORE_CASE
        )
        val raw = weightRegex.find(attributes)?.groupValues?.get(1) ?: return null
        return raw.toFloatOrNull()?.coerceIn(0f, 1f)
    }

    /**
     * 将Mood转换为AvatarEmotion。
     * 内置 mood 使用其 fallbackEmotion；自定义/未知 mood 退回关键词推理，
     * 以避免直接落到 IDLE。
     */
    private fun moodToEmotion(mood: String): AvatarEmotion? {
        AvatarMoodTypes.builtInFallbackEmotion(mood)?.let { return it }
        // 未知/自定义 mood：用关键字本身跑一次推理，尽量给出贴切表情。
        val inferred = inferEmotionFromText(mood)
        return inferred.takeIf { it != AvatarEmotion.IDLE }
    }
    
    /**
     * 综合分析文本，返回最合适的表情
     * 优先使用mood标签，如果没有则使用关键词推理
     */
    fun analyzeEmotion(text: String): AvatarEmotion {
        AppLogger.d("AvatarEmotionManager", "分析情感 - 原始文本: $text")

        // 首先尝试从mood标签获取
        val moodTag = extractMoodTag(text)
        if (moodTag != null) {
            val emotion = moodToEmotion(moodTag.key)
            if (emotion != null) {
                AppLogger.d(
                    "AvatarEmotionManager",
                    "从mood标签解析: ${moodTag.key} (weight=${moodTag.weight}) -> $emotion"
                )
                return emotion
            }
            // mood 标签存在但无法解析为表情：不直接返回，落到下面的关键词推理。
        }

        // 如果没有可用的mood标签，则使用关键词/标点推理
        val emotion = inferEmotionFromText(text)
        AppLogger.d("AvatarEmotionManager", "使用关键词推理: $emotion")
        return emotion
    }

    /**
     * 与 [analyzeEmotion] 相同的推理，但额外返回信号是否“明确”。
     *
     * mood 标签成功解析或关键词命中 -> `isExplicit = true`（强信号，应覆盖累积情绪）；
     * 仅靠标点回退或落到 IDLE -> `isExplicit = false`（弱信号，可让累积情绪延续）。
     *
     * 该方法为附加接口，供 [AvatarMoodAccumulator] 做跨轮次的情绪偏置；
     * 现有 [analyzeEmotion] 行为保持不变。
     */
    fun analyzeEmotionDetailed(text: String): EmotionInference {
        val moodTag = extractMoodTag(text)
        if (moodTag != null) {
            val emotion = moodToEmotion(moodTag.key)
            if (emotion != null) {
                // 显式 mood 标签始终视为强信号。
                return EmotionInference(emotion, isExplicit = true)
            }
        }
        return inferEmotionDetailed(text)
    }
    
    /**
     * 清除文本中的XML标签
     * 用于显示给用户时移除mood等标记标签
     */
    fun stripXmlLikeTags(text: String): String {
        var s = text
        // 匹配成对的标签 <tag>...</tag>
        val paired = Regex(
            pattern = "<([A-Za-z][A-Za-z0-9:_-]*)(\\s[^>]*)?>[\\s\\S]*?</\\1>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        repeat(5) { _ ->
            val updated = s.replace(paired, "")
            if (updated == s) return@repeat
            s = updated
        }
        // 匹配自闭合标签 <tag />
        s = s.replace(
            Regex("<[A-Za-z][A-Za-z0-9:_-]*(\\s[^>]*)?/\\s*>", RegexOption.IGNORE_CASE),
            ""
        )
        // 匹配任何剩余的标签
        s = s.replace(
            Regex("</?[^>]+>", RegexOption.IGNORE_CASE),
            ""
        )
        return s.trim()
    }
} 
