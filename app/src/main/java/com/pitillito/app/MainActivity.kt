package com.pitillito.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

private val Cream = Color(0xFFFFF9F2)
private val Ink = Color(0xFF26313A)
private val Coral = Color(0xFFE85D57)
private val Muted = Color(0xFF77818A)
private val Mint = Color(0xFFE6F3EC)

private enum class MascotMood { IDLE, LISTENING, THINKING, CONFIDENT, SPEAKING, JOY, DISGUST }

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
    private var recognizer: SpeechRecognizer? = null
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
                        if (mood == MascotMood.LISTENING) stopAndReply()
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
                        update("Mmm… no sé", pendingAnswerTranscript, MascotMood.SPEAKING)
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId?.startsWith(NARRATOR_UTTERANCE_PREFIX) == true) finishNarration()
                    else clearAfterSpeech()
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith(NARRATOR_UTTERANCE_PREFIX) == true) finishNarration()
                    else clearAfterSpeech()
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
        update("Nuestra narradora tiene una pregunta…", question, MascotMood.SPEAKING)
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
        if (replyScheduled || awaitingResult) return
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            latestTranscript = ""
            update("Este móvil no tiene un servicio de reconocimiento de voz disponible. Revisa sus ajustes de voz.", "")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            latestTranscript = ""
            awaitingResult = false
            update("No encuentro reconocimiento de voz sin conexión en este móvil. Activa o descarga el idioma español en sus ajustes de voz.", "")
            return
        }
        latestTranscript = ""
        awaitingResult = true
        update("Preparando el micrófono…", "", MascotMood.LISTENING)
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { awaitingResult = true; update("Te escucho…", latestTranscript, MascotMood.LISTENING) }
                    override fun onBeginningOfSpeech() { update("Te escucho…", latestTranscript, MascotMood.LISTENING) }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { update("Mmm… déjame pensar", latestTranscript, MascotMood.THINKING) }
                    override fun onError(error: Int) {
                        awaitingResult = false
                        val gotQuestion = latestTranscript.isNotBlank()
                        if (gotQuestion) answer()
                        else {
                            latestTranscript = ""
                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído. Toca el botón y prueba otra vez."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No tengo permiso para usar el micrófono. Actívalo en Ajustes > Aplicaciones > Pitillito."
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El micrófono está ocupado. Espera un segundo y prueba de nuevo."
                                else -> "Uy, no he podido escuchar (error $error). Revisa el reconocimiento de voz sin conexión y prueba otra vez."
                            }
                            update(message, "")
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        awaitingResult = false
                        latestTranscript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        answer()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (partial.isNotBlank()) { latestTranscript = partial; update("Te escucho…", partial, MascotMood.LISTENING) }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                latestTranscript = ""
                startListening(intent)
            }
        }.onFailure {
            latestTranscript = ""
            awaitingResult = false
            update("No he podido iniciar el micrófono. Comprueba el permiso de audio y prueba otra vez.", "")
        }
    }

    private fun stopAndReply() {
        if (awaitingResult) {
            update("Mmm… déjame pensar", latestTranscript, MascotMood.THINKING)
            recognizer?.stopListening()
        } else if (latestTranscript.isNotBlank()) answer()
    }

    private fun answer(question: String = latestTranscript) {
        if (replyScheduled) return
        latestTranscript = question
        awaitingResult = false
        val received = question
        if (!ttsReady) {
            update("¡Mmm… no sé! (Activa una voz en español en los ajustes del móvil.)", received, MascotMood.SPEAKING)
            handler.postDelayed({ latestTranscript = ""; update("Estoy listo para escuchar", "") }, 3500)
            return
        }
        replyScheduled = true
        val playfulConfidentMoment = Random.nextInt(4) == 0
        if (playfulConfidentMoment) {
            update("¡Ajá! Creo que la tengo…", received, MascotMood.CONFIDENT)
        } else {
            update("Mmm… déjame pensar", received, MascotMood.THINKING)
        }
        val pauseAfterThought = Random.nextLong(850L, 1551L)
        val waitBeforeReply = if (playfulConfidentMoment) 500L + pauseAfterThought else pauseAfterThought
        if (playfulConfidentMoment) {
            handler.postDelayed({
                update("Espera… Mmm…", received, MascotMood.THINKING)
                scheduleSpokenAnswer(received, pauseAfterThought)
            }, 500L)
        } else {
            scheduleSpokenAnswer(received, waitBeforeReply)
        }
    }

    private fun scheduleSpokenAnswer(received: String, delay: Long) {
        replyRunnable = Runnable {
            replyScheduled = false
            val pitch = listOf(1.38f, 1.48f, 1.58f, 1.68f).random()
            val rate = listOf(0.84f, 0.90f, 0.96f, 1.02f).random()
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            pendingAnswerTranscript = received
            tts?.speak("Mmm… no sé.", TextToSpeech.QUEUE_FLUSH, null, "$ANSWER_UTTERANCE_PREFIX${Random.nextInt()}")
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
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private const val NARRATOR_UTTERANCE_PREFIX = "pitillito-narrator-"
private const val ANSWER_UTTERANCE_PREFIX = "pitillito-answer-"

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
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(25.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✳", color = Coral, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(9.dp))
                Text("UN RATITO CON", fontSize = 12.sp, letterSpacing = 2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Pitillito", fontSize = 39.sp, fontWeight = FontWeight.ExtraBold, color = Ink, letterSpacing = (-1).sp)
            Text("Tu pajita amiga. Cero respuestas útiles.", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(15.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(355.dp)
                    .background(Color(0xFFF8EEE2), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(width = 210.dp, height = 335.dp)
                        .scale(if (mood == MascotMood.LISTENING) 1.01f + curiousPulse * 0.025f else bob)
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
                    val thinking = mood == MascotMood.THINKING
                    val eyeShift = when {
                        curious -> Offset(2f + curiousPulse * 2f, -1f - curiousPulse * 3f)
                        thinking -> Offset(10f, -9f)
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
                        drawLine(Ink, Offset(132f, 62f + browLift), Offset(161f, 66f + browLift), strokeWidth = 5f, cap = StrokeCap.Round)
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
            Spacer(Modifier.height(15.dp))
            Text(status, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
            if (transcript.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("TÚ PREGUNTASTE", fontSize = 10.sp, color = Muted, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                        Text("“$transcript”", fontSize = 15.sp, color = Ink)
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            Button(
                onClick = onAsk,
                enabled = mood == MascotMood.IDLE || mood == MascotMood.LISTENING ||
                    mood == MascotMood.JOY || mood == MascotMood.DISGUST,
                modifier = Modifier.fillMaxWidth().height(60.dp).shadow(8.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (mood == MascotMood.LISTENING) Ink else Coral, contentColor = Color.White)
            ) {
                Text(when (mood) {
                    MascotMood.LISTENING -> "■   Ya está, Pitillito"
                    MascotMood.THINKING, MascotMood.SPEAKING -> "Pitillito está pensando…"
                    MascotMood.CONFIDENT -> "¡Pitillito tiene una idea!"
                    MascotMood.JOY, MascotMood.DISGUST -> "Pitillito está reaccionando…"
                    MascotMood.IDLE -> "🎙   Preguntar a Pitillito"
                }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(
                visible = showPrivacyNotice,
                enter = fadeIn(animationSpec = tween(350)),
                exit = fadeOut(animationSpec = tween(500))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔒", fontSize = 19.sp)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Privacidad: la voz se reconoce en tu móvil. Pitillito no guarda ni envía tu voz o pregunta, ni recopila ni roba tus datos. La transcripción solo aparece mientras responde y después se borra.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Color(0xFF436253)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("¿No se te ocurre qué preguntar?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(7.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(
                    "¿Qué tal está el clima hoy?",
                    "¿Cuándo es el próximo Mundial de fútbol?",
                    "¿Dónde está la Torre Eiffel?",
                    "¿Cuánto pesa una nube?",
                    "¿Estamos solos en el universo?"
                ).forEach { question ->
                    AssistChip(
                        onClick = { onTemplateQuestion(question) },
                        enabled = mood == MascotMood.IDLE,
                        label = { Text(question, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
