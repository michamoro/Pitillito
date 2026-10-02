package com.pitillito.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random
import kotlin.math.sqrt
import kotlin.math.ceil

private val Cream = Color(0xFFFFF9F2)
private val Ink = Color(0xFF26313A)
private val Coral = Color(0xFFE85D57)
private val Muted = Color(0xFF77818A)
private val Mint = Color(0xFFE6F3EC)

private enum class MascotMood { IDLE, LISTENING, CURIOUS, THINKING, ANALYZING, CONFIDENT, NARRATING, SPEAKING, JOY, DISGUST }

private fun makeStrawStripeCenters(): List<Offset> {
    val pathPoints = buildList {
        for (step in 0..70) {
            val fraction = step / 70f
            add(Offset(127f, 293f + (177f - 293f) * fraction))
        }
        for (step in 1..35) {
            val t = step / 35f
            val oneMinusT = 1f - t
            val x = oneMinusT * oneMinusT * oneMinusT * 127f +
                3f * oneMinusT * oneMinusT * t * 127f +
                3f * oneMinusT * t * t * 115.36f + t * t * t * 101f
            val y = oneMinusT * oneMinusT * oneMinusT * 177f +
                3f * oneMinusT * oneMinusT * t * 162.64f +
                3f * oneMinusT * t * t * 151f + t * t * t * 151f
            add(Offset(x, y))
        }
        for (step in 1..24) {
            val fraction = step / 24f
            add(Offset(101f + (53f - 101f) * fraction, 151f))
        }
    }

    val centers = mutableListOf<Offset>()
    val spacing = 15f
    var nextMarkAt = 10f
    var distanceAlongStraw = 0f
    for (index in 0 until pathPoints.lastIndex) {
        val start = pathPoints[index]
        val end = pathPoints[index + 1]
        val dx = end.x - start.x
        val dy = end.y - start.y
        val segmentLength = sqrt(dx * dx + dy * dy)
        while (segmentLength > 0f && nextMarkAt <= distanceAlongStraw + segmentLength) {
            val fraction = (nextMarkAt - distanceAlongStraw) / segmentLength
            centers += Offset(start.x + dx * fraction, start.y + dy * fraction)
            nextMarkAt += spacing
        }
        distanceAlongStraw += segmentLength
    }
    return centers
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pitillitoVoice: Voice? = null
    private var narratorVoice: Voice? = null
    private var screenUpdate: ((String, String, MascotMood) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speakingFallback: Runnable? = null
    private var replyRunnable: Runnable? = null
    private var awaitingResult = false
    private var replyScheduled = false
    private var latestTranscript = ""
    private var pendingAnswerTranscript = ""
    private var interactionReset: Runnable? = null
    private var speakingAnswer = false
    private var recordingStartedAt = 0L
    private var recordingTicker: Runnable? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginListening() else update("Permite el micrófono para preguntarme.", "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Estoy listo para escuchar") }
                var transcript by remember { mutableStateOf("") }
                var mood by remember { mutableStateOf(MascotMood.IDLE) }
                DisposableEffect(Unit) {
                    screenUpdate = { newStatus, newTranscript, newMood ->
                        status = newStatus
                        transcript = newTranscript
                        mood = newMood
                    }
                    onDispose { screenUpdate = null }
                }
                PitillitoHome(
                    status = status,
                    transcript = transcript,
                    mood = mood,
                    onTemplateQuestion = { question -> narrateTemplateQuestion(question) },
                    onMascotTap = { reactToMascotTap() },
                    onAsk = {
                        if (awaitingResult) stopAndReply()
                        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        else beginListening()
                    }
                )
            }
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val spanish = tts?.setLanguage(Locale("es", "ES"))
            ttsReady = spanish != null && spanish != TextToSpeech.LANG_MISSING_DATA &&
                spanish != TextToSpeech.LANG_NOT_SUPPORTED
            pitillitoVoice = tts?.voice
            val spanishVoices = tts?.voices.orEmpty().filter { it.locale.language == "es" }
            narratorVoice = spanishVoices.firstOrNull { voice ->
                val voiceName = voice.name.lowercase(Locale.ROOT)
                listOf("female", "femin", "mujer", "-eef", "-eec", "-esf").any(voiceName::contains)
            } ?: spanishVoices.firstOrNull()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId?.startsWith(ANSWER_UTTERANCE_PREFIX) == true) {
                        speakingAnswer = true
                        update("Mmm… no sé", pendingAnswerTranscript, MascotMood.SPEAKING)
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId?.startsWith(NARRATOR_UTTERANCE_PREFIX) == true) finishNarration()
                    else { speakingAnswer = false; clearAfterSpeech() }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith(NARRATOR_UTTERANCE_PREFIX) == true) finishNarration()
                    else { speakingAnswer = false; clearAfterSpeech() }
                }
            })
        }
    }

    private fun narrateTemplateQuestion(question: String) {
        if (!ttsReady) {
            answer(question)
            return
        }
        if (replyScheduled) return
        latestTranscript = question
        replyScheduled = true
        update("Nuestra narradora tiene una pregunta…", question, MascotMood.NARRATING)
        tts?.setVoice(narratorVoice ?: pitillitoVoice)
        tts?.setPitch(1.04f)
        tts?.setSpeechRate(0.91f)
        val utteranceId = "$NARRATOR_UTTERANCE_PREFIX${Random.nextInt()}"
        val result = tts?.speak(
            "Pitillito, tú que todo lo sabes, responde: $question",
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) finishNarration()
    }

    private fun finishNarration() {
        runOnUiThread {
            if (!replyScheduled || isFinishing || isDestroyed) return@runOnUiThread
            tts?.setVoice(pitillitoVoice)
            replyScheduled = false
            answer(latestTranscript)
        }
    }

    private fun update(status: String, transcript: String = latestTranscript, mood: MascotMood = MascotMood.IDLE) {
        runOnUiThread { screenUpdate?.invoke(status, transcript, mood) }
    }

    private fun reactToMascotTap() {
        if (replyScheduled || awaitingResult || speakingAnswer) return
        interactionReset?.let(handler::removeCallbacks)
        val reaction = listOf(MascotMood.JOY, MascotMood.DISGUST).random()
        val message = when (reaction) {
            MascotMood.JOY -> "¡Je, je! ¡Eso me gusta!"
            MascotMood.DISGUST -> "¡Puaj! Esa cosquilla no me gustó…"
            else -> "¡Je, je!"
        }
        update(message, "", reaction)
        interactionReset = Runnable { update("Estoy listo para escuchar", "", MascotMood.IDLE) }
        handler.postDelayed(interactionReset!!, 1500)
    }

    private fun beginListening() {
        replyRunnable?.let(handler::removeCallbacks)
        replyRunnable = null
        replyScheduled = false
        tts?.stop()
        replyScheduled = false
        tts?.stop()
        update("Preparando el micrófono…", "", MascotMood.LISTENING)
        runCatching {
            val sampleRate = 16_000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "No se pudo iniciar el micrófono" }
            audioRecord?.release()
            audioRecord = recorder
            recorder.startRecording()
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
            awaitingResult = true
            update("Te escucho… 0 s", "", MascotMood.LISTENING)
            val discardBuffer = ShortArray(bufferSize / 2)
            Thread {
                while (awaitingResult) {
                    val read = runCatching { recorder.read(discardBuffer, 0, discardBuffer.size) }.getOrDefault(-1)
                    if (read < 0) break
                }
            }.apply { name = "PitillitoMicDiscard"; isDaemon = true; start() }
            scheduleRecordingTicker()
        }.onFailure {
            awaitingResult = false
            audioRecord?.runCatching { release() }
            audioRecord = null
            update("No he podido iniciar el micrófono. Comprueba el permiso de audio y prueba otra vez.", "")
        }
    }

    private fun stopAndReply() {
        if (awaitingResult) {
            val durationSeconds = ceil((android.os.SystemClock.elapsedRealtime() - recordingStartedAt) / 1000.0).toInt().coerceAtLeast(1)
            awaitingResult = false
            recordingTicker?.let(handler::removeCallbacks)
            recordingTicker = null
            runCatching { audioRecord?.stop() }
            audioRecord?.release()
            audioRecord = null
            answer(durationSeconds)
        }
    }

    private fun scheduleRecordingTicker() {
        recordingTicker?.let(handler::removeCallbacks)
        recordingTicker = Runnable {
            if (awaitingResult) {
                val seconds = ((android.os.SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L).toInt()
                val listeningExpression = when ((seconds / 2) % 3) {
                    0 -> MascotMood.LISTENING
                    1 -> MascotMood.CURIOUS
                    else -> MascotMood.ANALYZING
                }
                update("Te escucho… ${seconds} s", "", listeningExpression)
                handler.postDelayed(recordingTicker!!, 250)
            }
        }
        handler.postDelayed(recordingTicker!!, 250)
    }

    private fun answer(durationSeconds: Int) {
        answer(GENERIC_QUESTIONS.random().format(durationSeconds))
    }

    private fun answer(question: String) {
        if (replyScheduled) return
        latestTranscript = question
        awaitingResult = false
        val received = question
        if (!ttsReady) {
            update("¡Mmm… no sé! (Activa una voz en español en los ajustes del móvil.)", received, MascotMood.THINKING)
            handler.postDelayed({ latestTranscript = ""; update("Estoy listo para escuchar", "") }, 3500)
            return
        }
        replyScheduled = true
        val playfulConfidentMoment = Random.nextInt(4) == 0
        val thinkingComment = THINKING_COMMENTS.random()
        if (playfulConfidentMoment) {
            update("¡Ajá! Creo que la tengo…", received, MascotMood.CONFIDENT)
        } else {
            update(thinkingComment, received, MascotMood.THINKING)
        }
        val pauseAfterThought = Random.nextLong(2400L, 3601L)
        if (playfulConfidentMoment) {
            handler.postDelayed({
                update(thinkingComment, received, MascotMood.THINKING)
                scheduleSpokenAnswer(received, Random.nextLong(2500L, 3501L))
            }, 650L)
        } else {
            scheduleSpokenAnswer(received, pauseAfterThought)
        }
    }

    private fun scheduleSpokenAnswer(received: String, delay: Long) {
        replyRunnable = Runnable {
            replyScheduled = false
            val pitch = listOf(1.38f, 1.48f, 1.58f, 1.68f).random()
            val rate = listOf(0.70f, 0.76f, 0.82f, 0.88f).random()
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            pendingAnswerTranscript = received
            tts?.speak("Mmm… mmm… no sé.", TextToSpeech.QUEUE_FLUSH, null, "$ANSWER_UTTERANCE_PREFIX${Random.nextInt()}")
            speakingFallback?.let(handler::removeCallbacks)
            speakingFallback = Runnable { latestTranscript = ""; update("Estoy listo para escuchar", "") }
            handler.postDelayed(speakingFallback!!, 5000)
        }
        handler.postDelayed(replyRunnable!!, delay)
    }

    private fun clearAfterSpeech() {
        handler.postDelayed({
            speakingFallback?.let(handler::removeCallbacks)
            latestTranscript = ""
            update("Estoy listo para escuchar", "")
        }, 500)
    }

    override fun onDestroy() {
        interactionReset?.let(handler::removeCallbacks)
        speakingFallback?.let(handler::removeCallbacks)
        awaitingResult = false
        recordingTicker?.let(handler::removeCallbacks)
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private const val NARRATOR_UTTERANCE_PREFIX = "pitillito-narrator-"
private const val ANSWER_UTTERANCE_PREFIX = "pitillito-answer-"
private val THINKING_COMMENTS = listOf(
    "Revisando datos estadísticos…",
    "Buscando con IA generativa de alto nivel…",
    "Consultando mi base de datos imaginaria…",
    "Procesando la pregunta a velocidad de pajita…",
    "Analizando las pistas del universo…",
    "Ordenando mis ideas… estaban todas dobladas.",
    "Calculando… ¿alguien vio mi calculadora?",
    "Activando mi modo experto… creo.",
    "Consultando a mis dos neuronas…",
    "Buscando una respuesta entre mis rayitas…",
    "Haciendo una pausa dramática pequeñita…",
    "Revisando mis apuntes invisibles…"
)
private val GENERIC_QUESTIONS = listOf(
    "Una pregunta muy interesante de %d segundos.",
    "Una pregunta increíble de %d segundos.",
    "Una pregunta misteriosa de %d segundos.",
    "Una pregunta que me hizo pensar durante %d segundos.",
    "Una pregunta curiosísima de %d segundos.",
    "Una pregunta digna de un gran sabio, de %d segundos.",
    "Una pregunta con mucho suspenso: %d segundos.",
    "Una pregunta brillante de %d segundos.",
    "Una pregunta que sonó muy importante durante %d segundos.",
    "Una pregunta de otro planeta, de %d segundos.",
    "Una pregunta fascinante de %d segundos.",
    "Una pregunta que puso a trabajar mis neuronas por %d segundos.",
    "Una pregunta súper difícil de %d segundos.",
    "Una pregunta llena de intriga, de %d segundos.",
    "Una pregunta especial de %d segundos."
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PitillitoHome(
    status: String,
    transcript: String,
    mood: MascotMood,
    onTemplateQuestion: (String) -> Unit,
    onMascotTap: () -> Unit,
    onAsk: () -> Unit
) {
    val strawStripeCenters = remember { makeStrawStripeCenters() }
    var showPrivacyNotice by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(10_000)
        showPrivacyNotice = false
    }
    val motion = rememberInfiniteTransition(label = "pitillito")
    val bob by motion.animateFloat(
        initialValue = 0.98f, targetValue = 1.025f,
        animationSpec = infiniteRepeatable(tween(1250, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bounce"
    )
    val curiousPulse by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "curiosity"
    )
    val listeningSurprise by motion.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3200
                0f at 0
                0f at 2100
                1f at 2300 using FastOutSlowInEasing
                1f at 2450
                0f at 2600 using FastOutSlowInEasing
                0f at 3200
            },
            RepeatMode.Restart
        ),
        label = "listening-surprise"
    )
    val mouthIsMoving = mood == MascotMood.SPEAKING
    val mouthOpening by animateFloatAsState(
        targetValue = if (mouthIsMoving) 1f else 0f,
        animationSpec = if (mouthIsMoving) {
            infiniteRepeatable(tween(145, easing = FastOutSlowInEasing), RepeatMode.Reverse)
        } else {
            tween(100)
        },
        label = "talking-mouth"
    )
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 900.dp
            val tight = maxHeight < 680.dp
            val horizontalPadding = if (compact) 18.dp else 26.dp
            val heroHeight = when {
                tight && showPrivacyNotice -> 180.dp
                tight -> 220.dp
                compact && showPrivacyNotice -> 250.dp
                compact -> 300.dp
                else -> 355.dp
            }
            val characterScale = if (compact) ((heroHeight - 12.dp) / 335.dp) else 1f
            val gap = if (compact) 6.dp else 15.dp
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Spacer(Modifier.height(if (compact) 17.dp else 35.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UN RATITO CON", fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
            Text("Pitillito", fontSize = if (compact) 32.sp else 39.sp, fontWeight = FontWeight.ExtraBold, color = Ink, letterSpacing = (-1).sp)
            Text("Tu pajita amiga. Cero respuestas útiles.", fontSize = if (compact) 12.sp else 14.sp, color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (compact) 7.dp else 15.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(heroHeight)
                    .background(Color(0xFFF8EEE2), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(width = (210 * characterScale).dp, height = (335 * characterScale).dp)
                        .scale(if (mood == MascotMood.LISTENING || mood == MascotMood.CURIOUS || mood == MascotMood.ANALYZING) 1.01f + curiousPulse * 0.025f else bob)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMascotTap
                        )
                ) {
                    val sx = size.width / 210f
                    val sy = size.height / 335f
                    drawContext.transform.scale(sx, sy, Offset.Zero)
                    // Soft oval shadow under the character.
                    drawOval(Color(0x1C26313A), topLeft = Offset(46f, 300f), size = Size(118f, 17f))
                    val straw = Path().apply {
                        moveTo(127f, 293f)
                        lineTo(127f, 177f)
                        cubicTo(127f, 162.64f, 115.36f, 151f, 101f, 151f)
                        lineTo(53f, 151f)
                    }
                    drawPath(straw, Color(0xFFE8E2DE), style = Stroke(width = 25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(straw, Color.White, style = Stroke(width = 21f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(straw, Color(0xFFF6F1ED), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    // Identical diagonal bands continue at equal intervals over the whole bendy straw.
                    strawStripeCenters.forEach { center ->
                        drawLine(
                            Coral,
                            Offset(center.x - 5.5f, center.y + 5.5f),
                            Offset(center.x + 5.5f, center.y - 5.5f),
                            strokeWidth = 5.5f,
                            cap = StrokeCap.Round
                        )
                    }
                    // Large friendly eyes, looking a little askance.
                    drawOval(Color.White, topLeft = Offset(54f, 62f), size = Size(63f, 67f))
                    drawOval(Color.White, topLeft = Offset(113f, 71f), size = Size(63f, 67f))
                    val curious = mood == MascotMood.LISTENING
                    val thinking = mood == MascotMood.THINKING || mood == MascotMood.CURIOUS
                    val eyeShift = when {
                        curious -> Offset(2f + curiousPulse * 2f, -1f - curiousPulse * 3f)
                        thinking -> Offset(10f, -9f)
                        mood == MascotMood.ANALYZING -> Offset(-8f + curiousPulse * 5f, -6f)
                        else -> Offset.Zero
                    }
                    drawCircle(Color(0xFF2A2531), radius = 16f, center = Offset(89f, 95f) + eyeShift)
                    drawCircle(Color(0xFF2A2531), radius = 16f, center = Offset(138f, 101f) + eyeShift)
                    drawCircle(Color.White, radius = 5f, center = Offset(84f, 89f) + eyeShift)
                    drawCircle(Color.White, radius = 5f, center = Offset(133f, 95f) + eyeShift)
                    // Listening lifts the brows; thinking looks up; the rare confident beat gets a playful wink.
                    if (mood == MascotMood.CONFIDENT) {
                        drawLine(Ink, Offset(54f, 59f), Offset(83f, 51f), strokeWidth = 5f, cap = StrokeCap.Round)
                        drawLine(Ink, Offset(132f, 62f), Offset(161f, 66f), strokeWidth = 5f, cap = StrokeCap.Round)
                        drawOval(Color.White, topLeft = Offset(113f, 71f), size = Size(63f, 67f))
                        val wink = Path().apply { moveTo(124f, 104f); cubicTo(135f, 114f, 149f, 114f, 162f, 103f) }
                        drawPath(wink, Ink, style = Stroke(width = 5f, cap = StrokeCap.Round))
                    } else {
                        val browLift = if (curious) -6f - curiousPulse * 3f else if (thinking) -3f else 0f
                        drawLine(Ink, Offset(54f, 61f + browLift), Offset(83f, 54f + browLift), strokeWidth = 5f, cap = StrokeCap.Round)
                        val rightBrowLift = if (mood == MascotMood.ANALYZING) -5f else browLift
                        drawLine(Ink, Offset(132f, 62f + rightBrowLift), Offset(161f, 66f + rightBrowLift), strokeWidth = 5f, cap = StrokeCap.Round)
                    }
                    when (mood) {
                        MascotMood.DISGUST -> {
                            drawLine(Ink, Offset(54f, 57f), Offset(83f, 65f), strokeWidth = 5f, cap = StrokeCap.Round)
                            drawLine(Ink, Offset(132f, 67f), Offset(161f, 59f), strokeWidth = 5f, cap = StrokeCap.Round)
                            val frown = Path().apply { moveTo(104f, 121f); cubicTo(109f, 110f, 119f, 110f, 124f, 121f) }
                            drawPath(frown, Coral, style = Stroke(width = 4f, cap = StrokeCap.Round))
                        }
                        MascotMood.JOY -> {
                            val happy = Path().apply { moveTo(101f, 112f); cubicTo(107f, 134f, 121f, 134f, 128f, 112f) }
                            drawPath(happy, Coral, style = Stroke(width = 4.5f, cap = StrokeCap.Round))
                            drawCircle(Color(0xFFFFC3AE), 7f, Offset(52f, 122f))
                            drawCircle(Color(0xFFFFC3AE), 7f, Offset(171f, 127f))
                        }
                        MascotMood.LISTENING, MascotMood.CURIOUS, MascotMood.ANALYZING -> {
                            if (listeningSurprise > 0.05f) {
                                val opening = 3f + listeningSurprise * 10f
                                drawOval(Coral, topLeft = Offset(106f, 118f - opening / 2f), size = Size(17f, opening))
                                drawOval(
                                    Color(0xFF8F3940),
                                    topLeft = Offset(110f, 119f - opening * 0.25f),
                                    size = Size(9f, (opening * 0.38f).coerceAtLeast(1f))
                                )
                            } else {
                                val attentiveSmile = Path().apply { moveTo(104f, 113f); cubicTo(109f, 122f, 119f, 122f, 124f, 114f) }
                                drawPath(attentiveSmile, Coral, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
                            }
                        }
                        MascotMood.SPEAKING -> {
                            val opening = 3.5f + mouthOpening * 13f
                            drawOval(Coral, topLeft = Offset(104f, 117f - opening / 2f), size = Size(21f, opening))
                            drawOval(
                                Color(0xFF8F3940),
                                topLeft = Offset(108f, 118f - opening * 0.28f),
                                size = Size(13f, (opening * 0.55f).coerceAtLeast(1f))
                            )
                        }
                        else -> {
                            val smile = Path().apply { moveTo(104f, 113f); cubicTo(109f, if (mood == MascotMood.SPEAKING) 129f else 122f, 119f, if (mood == MascotMood.SPEAKING) 129f else 122f, 124f, 114f) }
                            drawPath(smile, Coral, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
                        }
                    }
                    drawCircle(Color(0xFFFFC3AE), 5f, Offset(52f, 122f))
                    drawCircle(Color(0xFFFFC3AE), 5f, Offset(171f, 127f))
                    drawContext.transform.scale(1f / sx, 1f / sy, Offset.Zero)
                }
                Text("✦", modifier = Modifier.align(Alignment.TopEnd).padding(36.dp), color = Color(0xFFE3B052), fontSize = 21.sp)
                Text("·", modifier = Modifier.align(Alignment.CenterStart).padding(start = 27.dp, top = 125.dp), color = Coral, fontSize = 42.sp)
            }
            Spacer(Modifier.height(gap))
            Text(
                status,
                fontSize = when {
                    mood == MascotMood.SPEAKING -> if (compact) 22.sp else 26.sp
                    compact -> 14.sp
                    else -> 16.sp
                },
                fontWeight = if (mood == MascotMood.SPEAKING) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (mood == MascotMood.SPEAKING) Coral else Ink,
                textAlign = TextAlign.Center
            )
            if (transcript.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("TU PREGUNTA", fontSize = 10.sp, color = Muted, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                        Text("“$transcript”", fontSize = 15.sp, color = Ink)
                    }
                }
            }
            if (mood == MascotMood.IDLE || mood == MascotMood.LISTENING || mood == MascotMood.CURIOUS ||
                mood == MascotMood.ANALYZING || mood == MascotMood.JOY || mood == MascotMood.DISGUST) {
                Spacer(Modifier.height(if (compact) 7.dp else 15.dp))
                Button(
                    onClick = onAsk,
                    enabled = mood == MascotMood.IDLE || mood == MascotMood.LISTENING ||
                        mood == MascotMood.CURIOUS || mood == MascotMood.THINKING || mood == MascotMood.ANALYZING ||
                        mood == MascotMood.JOY || mood == MascotMood.DISGUST,
                    modifier = Modifier.fillMaxWidth().height(if (compact) 52.dp else 60.dp).shadow(8.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (mood == MascotMood.LISTENING || mood == MascotMood.CURIOUS || mood == MascotMood.ANALYZING) Ink else Coral, contentColor = Color.White)
                ) {
                    Text(when (mood) {
                        MascotMood.LISTENING -> "■   Ya está, Pitillito"
                        MascotMood.CURIOUS -> "■   Ya está, Pitillito"
                        MascotMood.THINKING -> "Pitillito está pensando…"
                        MascotMood.ANALYZING -> "■   Ya está, Pitillito"
                        MascotMood.NARRATING -> "La narradora tiene una pregunta…"
                        MascotMood.SPEAKING -> "Mmm… no sé"
                        MascotMood.CONFIDENT -> "¡Pitillito tiene una idea!"
                        MascotMood.JOY, MascotMood.DISGUST -> "Pitillito está reaccionando…"
                        MascotMood.IDLE -> "🎙   Preguntar a Pitillito"
                    }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(if (compact) 7.dp else 12.dp))
            }
            AnimatedVisibility(
                visible = showPrivacyNotice,
                enter = fadeIn(animationSpec = tween(350)),
                exit = fadeOut(animationSpec = tween(500))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(16.dp))
                        .padding(horizontal = if (compact) 10.dp else 15.dp, vertical = if (compact) 8.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔒", fontSize = if (compact) 16.sp else 19.sp)
                    Spacer(Modifier.width(if (compact) 7.dp else 11.dp))
                    Text(
                        "PRIVACIDAD: Tranquilo no robaremos tus datos, no guardaremos tus preguntas, ni pediremos creditos a tu nombre ;) jeje",
                        fontSize = if (compact) 10.sp else 12.sp,
                        lineHeight = if (compact) 12.sp else 17.sp,
                        color = Color(0xFF436253)
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 7.dp else 12.dp))
            Text("¿No se te ocurre qué preguntar?", fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(if (compact) 3.dp else 7.dp))
            val sampleQuestions = listOf(
                "¿Qué tal está el clima hoy?",
                "¿Cuándo es el próximo Mundial de fútbol?",
                "¿Dónde está la Torre Eiffel?",
                "¿Cuánto pesa una nube?",
                "¿Estamos solos en el universo?"
            )
            if (compact) {
                sampleQuestions.chunked(2).forEach { rowQuestions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rowQuestions.size == 1) Spacer(Modifier.weight(0.5f))
                        rowQuestions.forEach { question ->
                            AssistChip(
                                onClick = { onTemplateQuestion(question) },
                                enabled = mood == MascotMood.IDLE,
                                modifier = Modifier.weight(1f),
                                label = { Text(question, fontSize = 10.sp, maxLines = 2, lineHeight = 11.sp, textAlign = TextAlign.Center) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        if (rowQuestions.size == 1) Spacer(Modifier.weight(0.5f))
                    }
                }
            } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                sampleQuestions.forEach { question ->
                    AssistChip(
                        onClick = { onTemplateQuestion(question) },
                        enabled = mood == MascotMood.IDLE,
                        label = { Text(question, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            }
            Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
            }
        }
    }
}
