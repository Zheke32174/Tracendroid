package com.ai.assistance.operit.ui.floating.ui.fullscreen.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.avatar.common.control.AvatarSettingKeys
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.common.view.AvatarView
import com.ai.assistance.operit.core.avatar.impl.factory.AvatarControllerFactoryImpl
import com.ai.assistance.operit.core.avatar.impl.factory.AvatarModelFactoryImpl
import com.ai.assistance.operit.core.avatar.impl.factory.AvatarRendererFactoryImpl
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.WakeWordPreferences
import com.ai.assistance.operit.data.repository.AvatarRepository
import com.ai.assistance.operit.data.repository.AvatarSettings
import com.ai.assistance.operit.data.repository.getEmotionAnimationMapping
import com.ai.assistance.operit.data.repository.getMoodAnimationMapping
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.BottomControlBar
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.EditPanel
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.MessageDisplay
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.WaveVisualizerSection
import com.ai.assistance.operit.ui.floating.ui.fullscreen.viewmodel.rememberFloatingFullscreenModeViewModel
import java.util.Random
import kotlin.random.Random as KRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

/**
 * 全屏模式主屏幕
 */
@Composable
fun FloatingFullscreenMode(floatContext: FloatContext) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val service = floatContext.chatService
    val autoEnterVoiceChat = remember(service) { service?.consumeAutoEnterVoiceChat() == true }
    var autoEnteringVoice by remember(autoEnterVoiceChat) { mutableStateOf(autoEnterVoiceChat) }
    val viewModel = rememberFloatingFullscreenModeViewModel(context, floatContext, coroutineScope, initialWaveActive = autoEnterVoiceChat)
    
    // 偏好设置
    val preferencesManager = UserPreferencesManager.getInstance(context)
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }
    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCardAvatarUri by remember(activeCharacterCard?.id) {
        activeCharacterCard?.id?.let { preferencesManager.getAiAvatarForCharacterCardFlow(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupAvatarUri by remember(activeCharacterGroup?.id) {
        activeCharacterGroup?.id?.let { preferencesManager.getAiAvatarForCharacterGroupFlow(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupFallbackMemberCardId = remember(activeCharacterGroup?.members) {
        val sortedMembers = activeCharacterGroup?.members?.sortedBy { it.orderIndex }.orEmpty()
        sortedMembers.firstOrNull()?.characterCardId
    }
    val activeGroupFallbackMemberAvatarUri by remember(activeGroupFallbackMemberCardId) {
        activeGroupFallbackMemberCardId?.let { preferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeCharacterAvatarUri =
        when (activePrompt) {
            is ActivePrompt.CharacterGroup -> activeGroupAvatarUri ?: activeGroupFallbackMemberAvatarUri
            is ActivePrompt.CharacterCard -> activeCardAvatarUri
        }
    val globalAiAvatarUri by preferencesManager.customAiAvatarUri.collectAsState(initial = null)
    val aiAvatarUri = activeCharacterAvatarUri ?: globalAiAvatarUri

    val avatarModelFactory = remember { AvatarModelFactoryImpl() }
    val avatarRepository = remember { AvatarRepository.getInstance(context, avatarModelFactory) }
    val avatarControllerFactory = remember { AvatarControllerFactoryImpl() }
    val avatarRendererFactory = remember { AvatarRendererFactoryImpl() }
    val currentAvatarModel by avatarRepository.currentAvatar.collectAsState(initial = null)
    val avatarSettings by avatarRepository.settings.collectAsState(initial = AvatarSettings())
    val avatarConfigs by avatarRepository.configs.collectAsState(initial = emptyList())
    val avatarInstanceSettings by avatarRepository.instanceSettings.collectAsState(initial = emptyMap())
    val currentAvatarConfig = remember(avatarConfigs, currentAvatarModel?.id) {
        currentAvatarModel?.let { avatar -> avatarConfigs.find { it.id == avatar.id } }
    }
    val currentAvatarEmotionMapping = remember(currentAvatarConfig) {
        currentAvatarConfig?.getEmotionAnimationMapping().orEmpty()
    }
    val currentAvatarMoodAnimationMapping = remember(currentAvatarConfig) {
        currentAvatarConfig?.getMoodAnimationMapping().orEmpty()
    }
    val currentAvatarSettings = remember(currentAvatarModel?.id, avatarInstanceSettings) {
        currentAvatarModel?.id?.let { avatarId -> avatarInstanceSettings[avatarId] }
    }
    val currentAvatarRuntimeSettings = remember(currentAvatarSettings) {
        currentAvatarSettings?.let { settings ->
            mutableMapOf<String, Any>(
                AvatarSettingKeys.SCALE to settings.scale,
                AvatarSettingKeys.TRANSLATE_X to settings.translateX,
                AvatarSettingKeys.TRANSLATE_Y to settings.translateY
            ).apply {
                settings.customSettings.forEach { (key, value) ->
                    this[key] = value
                }
            }
        }
    }
    val voiceAvatarController = currentAvatarModel?.let { avatarControllerFactory.createController(it) }
    val isVoiceAvatarEnabled =
        avatarSettings.isVoiceCallAvatarEnabled &&
            currentAvatarModel != null &&
            voiceAvatarController != null

    val speechServicesPrefs = SpeechServicesPreferences(context)
    val ttsCleanerRegexs by speechServicesPrefs.ttsCleanerRegexsFlow.collectAsState(initial = emptyList())
    
    val wakePrefs = remember { WakeWordPreferences(context.applicationContext) }
    val autoNewChatGroup by wakePrefs.autoNewChatGroupFlow.collectAsState(initial = WakeWordPreferences.DEFAULT_AUTO_NEW_CHAT_GROUP)
    
    val volumeLevel by viewModel.volumeLevelFlow.collectAsState()
    
    var pendingSpeechPreview by remember { mutableStateOf<String?>(null) }
    var lastUserMessageTimestampBeforeSpeech by remember { mutableStateOf<Long?>(null) }

    // Avatar gaze state. `isGazeInteracting` is true while the user is touching the
    // avatar (gaze-follows-touch); it gates the idle-wander loop off so the two never
    // fight. `isAvatarSpeaking` mirrors the TTS speaking flow and also suppresses idle
    // wander so gaze stays composed while talking. Both drive lookAt() only through the
    // active AvatarController, whose lookAt is a no-op default on non-DragonBones runtimes.
    var isGazeInteracting by remember { mutableStateOf(false) }
    var isAvatarSpeaking by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isRecording, viewModel.userMessage) {
        if (viewModel.isRecording && viewModel.userMessage.isNotBlank()) {
            pendingSpeechPreview = viewModel.userMessage
        }
    }

    LaunchedEffect(viewModel.isRecording) {
        if (viewModel.isRecording) {
            lastUserMessageTimestampBeforeSpeech = floatContext.messages.lastOrNull { it.sender == "user" }?.timestamp
        }
    }

    LaunchedEffect(viewModel.isRecording) {
        if (!viewModel.isRecording && pendingSpeechPreview != null) {
            val snapshot = pendingSpeechPreview
            delay(1500)
            if (pendingSpeechPreview == snapshot) {
                pendingSpeechPreview = null
            }
        }
    }

    LaunchedEffect(floatContext.messages.lastOrNull()?.timestamp, viewModel.isRecording) {
        if (viewModel.isRecording) return@LaunchedEffect
        if (pendingSpeechPreview == null) return@LaunchedEffect
        val lastUser = floatContext.messages.lastOrNull { it.sender == "user" } ?: return@LaunchedEffect
        val beforeTs = lastUserMessageTimestampBeforeSpeech
        if (beforeTs != null && lastUser.timestamp != beforeTs) {
            pendingSpeechPreview = null
        }
    }
    
    // 监听语音识别结果
    LaunchedEffect(Unit) {
        viewModel.recognitionResultFlow.collectLatest { result ->
            viewModel.handleRecognitionResult(result.text, result.isFinal)
        }
    }
    
    // 初始化
    LaunchedEffect(Unit) {
        viewModel.initialize(
            autoEnterVoiceChat = autoEnterVoiceChat,
            wakeLaunched = service?.isWakeLaunched() == true
        )
    }

    LaunchedEffect(viewModel.isWaveActive) {
        if (viewModel.isWaveActive) {
            autoEnteringVoice = false
        }
    }

    val latestMessage = floatContext.messages.lastOrNull()

    // 监听最新的AI消息
    LaunchedEffect(latestMessage?.timestamp) {
        viewModel.processAndSpeakAiMessage(
            latestMessage,
            ttsCleanerRegexs
        )
    }

    LaunchedEffect(latestMessage?.timestamp, latestMessage?.contentStream == null) {
        viewModel.handleVoiceAvatarMessage(latestMessage)
    }

    LaunchedEffect(floatContext.inputProcessingState.value, latestMessage?.timestamp, latestMessage?.contentStream == null) {
        viewModel.syncVoiceAvatarWithProcessingState(
            state = floatContext.inputProcessingState.value,
            latestMessage = latestMessage
        )
    }

    LaunchedEffect(voiceAvatarController, currentAvatarEmotionMapping) {
        voiceAvatarController?.updateEmotionAnimationMapping(currentAvatarEmotionMapping)
    }

    LaunchedEffect(voiceAvatarController, currentAvatarMoodAnimationMapping) {
        voiceAvatarController?.updateTriggerAnimationMapping(currentAvatarMoodAnimationMapping)
    }

    LaunchedEffect(voiceAvatarController, currentAvatarRuntimeSettings) {
        currentAvatarRuntimeSettings?.let { settings ->
            voiceAvatarController?.updateSettings(settings)
        }
    }

    LaunchedEffect(voiceAvatarController, isVoiceAvatarEnabled, viewModel.voiceAvatarMotionRequest.sequence) {
        val controller = voiceAvatarController ?: return@LaunchedEffect
        if (!isVoiceAvatarEnabled) {
            return@LaunchedEffect
        }

        val request = viewModel.voiceAvatarMotionRequest
        // Apply parsed `<mood weight>` intensity (if any) before playback so the
        // controller can make the transition more/less emphatic. No-op on runtimes
        // that don't override setEmotionIntensity.
        request.intensity?.let { controller.setEmotionIntensity(it) }
        val triggerName = request.triggerName?.trim().orEmpty()
        if (triggerName.isNotEmpty()) {
            val handled = controller.playTrigger(triggerName, loop = if (request.playOnce) 1 else 0)
            if (handled) {
                if (request.playOnce) {
                    val durationMillis =
                        controller.estimateTriggerDurationMillis(triggerName)
                            ?: controller.estimateEmotionDurationMillis(request.emotion)
                    durationMillis?.let {
                        delay(durationMillis)
                        controller.setEmotion(AvatarEmotion.IDLE)
                    }
                }
                return@LaunchedEffect
            }
        }

        if (request.playOnce) {
            controller.playEmotion(request.emotion, loop = 1)
            controller.estimateEmotionDurationMillis(request.emotion)?.let { durationMillis ->
                delay(durationMillis)
                controller.setEmotion(AvatarEmotion.IDLE)
            }
        } else {
            controller.setEmotion(request.emotion)
        }
    }

    // Lip-sync: drive the active avatar's mouth while TTS is speaking.
    // v1 is a speaking on/off oscillation (mouth opens/closes on a fixed cadence while
    // the voice service reports it is speaking, then closes when playback stops).
    // collectLatest cancels the in-flight oscillation as soon as speaking flips, so the
    // finally block always closes the mouth. Amplitude-accurate lip-sync (driving the
    // open amount from live audio RMS) is a documented follow-up; the seam is the same
    // controller.lipSync(openAmount) call. lipSync() is a no-op default on runtimes that
    // don't override it, so this is safe for all avatar types.
    LaunchedEffect(voiceAvatarController, isVoiceAvatarEnabled) {
        val controller = voiceAvatarController ?: return@LaunchedEffect
        if (!isVoiceAvatarEnabled) return@LaunchedEffect
        viewModel.speechManager.voiceService.speakingStateFlow.collectLatest { speaking ->
            if (!speaking) {
                controller.lipSync(0f)
                return@collectLatest
            }
            try {
                var open = true
                while (true) {
                    controller.lipSync(if (open) 0.9f else 0.1f)
                    open = !open
                    delay(120)
                }
            } finally {
                controller.lipSync(0f)
            }
        }
    }

    // Track TTS speaking state into a Compose flag so the idle-gaze wander below can gate
    // itself off while the avatar is talking. Mirrors the same speakingStateFlow the
    // lip-sync effect uses; resets to false when there is no active controller.
    LaunchedEffect(voiceAvatarController, isVoiceAvatarEnabled) {
        val controller = voiceAvatarController
        if (controller == null || !isVoiceAvatarEnabled) {
            isAvatarSpeaking = false
            return@LaunchedEffect
        }
        try {
            viewModel.speechManager.voiceService.speakingStateFlow.collectLatest { speaking ->
                isAvatarSpeaking = speaking
            }
        } finally {
            isAvatarSpeaking = false
        }
    }

    // Idle-gaze wander: while the avatar is idle (not being touched and not speaking),
    // periodically drift gaze to a small random normalized offset then ease back to
    // center, so the avatar feels alive instead of frozen. Uses kotlin.random.Random
    // (aliased KRandom to avoid the java.util.Random used by the noise bitmap). Every
    // call goes through AvatarController.lookAt (normalized -1..1, (0,0) recenters),
    // which is a no-op default on runtimes other than DragonBones, so this is safe for
    // every avatar type and a null controller is a no-op. The effect restarts whenever
    // interaction/speaking state flips, which snaps gaze back to center on entry.
    LaunchedEffect(voiceAvatarController, isVoiceAvatarEnabled, isGazeInteracting, isAvatarSpeaking) {
        val controller = voiceAvatarController ?: return@LaunchedEffect
        if (!isVoiceAvatarEnabled || isGazeInteracting || isAvatarSpeaking) {
            // Recenter and stay put while interacting/speaking (or when disabled).
            controller.lookAt(0f, 0f)
            return@LaunchedEffect
        }
        try {
            while (true) {
                // Dwell at center for a few seconds before the next glance.
                delay(KRandom.nextLong(2500L, 5000L))
                // Small, subtle offsets so the eyes drift rather than dart. lookAt clamps
                // to -1..1 internally; we stay well inside that for a gentle look.
                val targetX = KRandom.nextDouble(-0.35, 0.35).toFloat()
                val targetY = KRandom.nextDouble(-0.25, 0.25).toFloat()
                // Ease out from center to the target over a handful of small steps.
                val steps = 8
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    controller.lookAt(targetX * t, targetY * t)
                    delay(28L)
                }
                // Hold the glance briefly.
                delay(KRandom.nextLong(500L, 1200L))
                // Ease back to center.
                for (i in steps downTo 0) {
                    val t = i.toFloat() / steps
                    controller.lookAt(targetX * t, targetY * t)
                    delay(28L)
                }
            }
        } finally {
            // Whatever cancels us (interaction, speaking, disposal), leave gaze centered.
            controller.lookAt(0f, 0f)
        }
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cleanup()
        }
    }
    
    // 监听是否需要自动勾选"圈选识别" (来自圈选识别返回)
    LaunchedEffect(floatContext.currentMode, floatContext.pendingScreenSelection) {
        if (floatContext.currentMode == FloatingMode.FULLSCREEN && floatContext.pendingScreenSelection) {
            viewModel.hasOcrSelection = true
            floatContext.pendingScreenSelection = false
        }
    }

    // UI 布局
    val effectiveWaveActive = viewModel.isWaveActive || autoEnteringVoice
    val fullscreenBgAlpha by animateFloatAsState(
        targetValue = if (autoEnteringVoice) 0f else 0.22f,
        animationSpec = tween(durationMillis = 260),
        label = "fullscreen_bg_alpha"
    )
    val systemBlurActive = floatContext.windowState?.fullscreenSystemBlurActive?.value ?: false
    val fallbackBlurEnabled = !systemBlurActive
    val fallbackOverlayAlpha by animateFloatAsState(
        targetValue = if (fallbackBlurEnabled && !autoEnteringVoice) 0.30f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "fullscreen_fallback_alpha"
    )
    val fallbackBlurRadius by animateFloatAsState(
        targetValue = if (fallbackBlurEnabled && !autoEnteringVoice) 22f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "fullscreen_fallback_blur"
    )
    val fallbackNoiseAlpha by animateFloatAsState(
        targetValue = if (fallbackBlurEnabled && !autoEnteringVoice) 0.06f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "fullscreen_fallback_noise"
    )
    val fullscreenScrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = fullscreenBgAlpha)
    val noiseBitmap = rememberNoiseBitmap()
    val topInsetPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(fullscreenScrimColor)
    ) {
        if (fallbackBlurEnabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fallbackOverlayAlpha }
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.40f to Color.Transparent,
                                0.68f to MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                                1.0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.40f)
                            )
                        )
                    )
                    .blur(fallbackBlurRadius.dp)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fallbackOverlayAlpha }
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.35f to Color.Transparent,
                                0.70f to Color.White.copy(alpha = 0.08f),
                                1.0f to Color.White.copy(alpha = 0.16f)
                            )
                        )
                    )
            )
            Image(
                bitmap = noiseBitmap,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fallbackNoiseAlpha },
                contentScale = ContentScale.Crop
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(10f)
                .padding(start = 16.dp, top = topInsetPadding + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
            IconButton(
                onClick = {
                    val group = autoNewChatGroup.trim().ifBlank {
                        WakeWordPreferences.DEFAULT_AUTO_NEW_CHAT_GROUP
                    }
                    floatContext.chatService?.getChatCore()?.createNewChat(
                        group = group,
                        inheritGroupFromCurrent = false
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Default.AddComment,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 顶部控制区域：返回窗口 / 语音模式 / 缩成语音球 / 关闭
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(10f)
                .padding(end = 16.dp, top = topInsetPadding + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回窗口模式
            IconButton(onClick = {
                floatContext.onModeChange(FloatingMode.WINDOW)
            }) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.floating_back_to_window),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 缩小成语音球
            IconButton(onClick = { floatContext.onModeChange(FloatingMode.VOICE_BALL) }) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = stringResource(R.string.floating_shrink_to_ball),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 关闭悬浮窗
            IconButton(
                onClick = {
                    viewModel.cleanup()
                    floatContext.onClose()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.floating_close_floating_window),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        
        // 主内容区域
        val isBottomBarVisible = viewModel.showBottomControls && !viewModel.isEditMode && !effectiveWaveActive
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (isBottomBarVisible) 120.dp else 32.dp)
        ) {
            // 波浪可视化和头像：仅在语音模式下显示
            if (effectiveWaveActive) {
                val waveOffsetY = (-64).dp
                val activeWaveSize = if (isVoiceAvatarEnabled) 420.dp else 300.dp
                val activeAvatarSize = if (isVoiceAvatarEnabled) 320.dp else 120.dp
                val centerTapTargetSize = if (isVoiceAvatarEnabled) 220.dp else 140.dp
                WaveVisualizerSection(
                    isWaveActive = viewModel.isWaveActive,
                    isRecording = viewModel.isRecording,
                    showAiLoadingEffect = viewModel.isVoiceCapturePausedForAi && !viewModel.isRecording,
                    volumeLevelFlow = if (viewModel.isWaveActive && viewModel.isRecording)
                        viewModel.volumeLevelFlow else null,
                    aiAvatarUri = aiAvatarUri,
                    avatarContent =
                        if (isVoiceAvatarEnabled) {
                            {
                                // Gaze-follows-touch: while the user touches/drags over the
                                // avatar, steer gaze toward the pointer. Pointer offsets are
                                // normalized against this element's size into lookAt's -1..1
                                // space (top-left -> (-1,-1), center -> (0,0), bottom-right ->
                                // (1,1)); on release we recenter with lookAt(0,0). Guards mark
                                // isGazeInteracting so the idle-wander loop pauses. Every call
                                // goes through the active AvatarController (no-op default off
                                // DragonBones), so this is safe for all avatar types.
                                val gazeModifier = Modifier.pointerInput(voiceAvatarController) {
                                    val controller = voiceAvatarController ?: return@pointerInput
                                    fun steer(position: Offset) {
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        if (w <= 0f || h <= 0f) return
                                        val nx = ((position.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                                        val ny = ((position.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                                        controller.lookAt(nx, ny)
                                    }
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            isGazeInteracting = true
                                            steer(offset)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            steer(change.position)
                                        },
                                        onDragEnd = {
                                            isGazeInteracting = false
                                            controller.lookAt(0f, 0f)
                                        },
                                        onDragCancel = {
                                            isGazeInteracting = false
                                            controller.lookAt(0f, 0f)
                                        }
                                    )
                                }
                                AvatarView(
                                    modifier = Modifier.fillMaxSize().then(gazeModifier),
                                    model = currentAvatarModel!!,
                                    controller = voiceAvatarController!!,
                                    rendererFactory = avatarRendererFactory
                                )
                            }
                        } else {
                            null
                        },
                    clipAvatarContent = false,
                    avatarShape = CircleShape,
                    activeWaveSize = activeWaveSize,
                    activeAvatarSize = activeAvatarSize,
                    onToggleActive = {
                        if (viewModel.isWaveActive) {
                            viewModel.exitWaveMode()
                        } else {
                            viewModel.enterWaveMode(enableAutoTimeout = false)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = waveOffsetY)
                        .zIndex(1f)
                )

                // 语音态：头像区域提供一个最高层的点击出口，确保“点头像退出语音态”不被其它层拦截
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = waveOffsetY)
                        .size(centerTapTargetSize)
                        .zIndex(4f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.onCenterAvatarClick()
                        }
                )
            }
            
            // 消息显示区域 - 根据模式切换位置
            AnimatedContent(
                targetState = effectiveWaveActive,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300, 150)) togetherWith
                    fadeOut(animationSpec = tween(300))
                },
                label = "MessageTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3f)
            ) { targetIsWaveActive ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val modifier = if (targetIsWaveActive) {
                        // 波浪模式：文本在底部
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.52f)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                    } else {
                        // 正常模式：文本在波浪下方
                        Modifier
                            .align(Alignment.Center)
                            .offset(y = 72.dp)
                            .fillMaxWidth()
                            .padding(top = 40.dp, bottom = 60.dp) // Timon: 依照顶部和底部组件距离估算
                            .padding(horizontal = 16.dp)
                    }

                    MessageDisplay(
                        messages = floatContext.messages,
                        speechPreviewText = if (viewModel.isRecording) viewModel.userMessage else (pendingSpeechPreview ?: ""),
                        showSpeechOverlay = viewModel.isRecording || pendingSpeechPreview != null,
                        modifier = modifier
                    )
                }
            }
        }
        
        // 编辑面板
        EditPanel(
            visible = viewModel.isEditMode,
            editableText = viewModel.editableText,
            onTextChange = { viewModel.editableText = it },
            onCancel = { viewModel.exitEditMode() },
            onSend = { viewModel.sendEditedMessage() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        
        // 底部控制栏
        BottomControlBar(
            visible = isBottomBarVisible,
            isRecording = viewModel.isRecording,
            isProcessingSpeech = viewModel.isProcessingSpeech,
            showDragHints = viewModel.showDragHints,
            floatContext = floatContext,
            onStartVoiceCapture = { viewModel.startVoiceCapture() },
            onStopVoiceCapture = { isCancel -> viewModel.stopVoiceCapture(isCancel) },
            isWaveActive = viewModel.isWaveActive,
            onToggleWaveMode = {
                if (viewModel.isWaveActive) {
                    viewModel.exitWaveMode()
                } else {
                    viewModel.enterWaveMode(enableAutoTimeout = false)
                }
            },
            onEnterEditMode = { text -> viewModel.enterEditMode(text) },
            onShowDragHintsChange = { viewModel.showDragHints = it },
            userMessage = viewModel.inputText,
            onUserMessageChange = { viewModel.inputText = it },
            attachScreenContent = viewModel.attachScreenContent,
            onAttachScreenContentChange = { viewModel.attachScreenContent = it },
            attachNotifications = viewModel.attachNotifications,
            onAttachNotificationsChange = { viewModel.attachNotifications = it },
            attachLocation = viewModel.attachLocation,
            onAttachLocationChange = { viewModel.attachLocation = it },
            hasOcrSelection = viewModel.hasOcrSelection,
            onHasOcrSelectionChange = { viewModel.hasOcrSelection = it },
            isTtsMuted = viewModel.isStreamingTtsMuted,
            onToggleTtsMute = { viewModel.toggleStreamingTtsMuted() },
            onSendClick = { viewModel.sendInputMessage() },
            volumeLevel = volumeLevel,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun rememberNoiseBitmap(size: Int = 120): ImageBitmap {
    return remember(size) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(0)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val alpha = 8 + random.nextInt(20)
            pixels[i] = AndroidColor.argb(alpha, 255, 255, 255)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.asImageBitmap()
    }
}
