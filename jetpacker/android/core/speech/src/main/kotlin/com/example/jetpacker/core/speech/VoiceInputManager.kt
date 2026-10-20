/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.jetpacker.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.jetpacker.core.flags.FeatureFlags
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Cloud-only voice input.
 *
 * Audio is captured with [AudioRecord] and, once the user stops recording, sent to a Gemini cloud
 * model through Firebase AI Logic for transcription (and translation to English). No on-device
 * model (ML Kit GenAI / AICore) is used, and nothing heavy happens at construction time, so
 * injecting this singleton can never crash app start-up.
 */
@Singleton
class VoiceInputManager @Inject constructor() {

  private val errorHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(TAG, "Unhandled error in VoiceInputManager", throwable)
    showError(getUserFriendlyErrorMessage(throwable))
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)

  private val _uiState = MutableStateFlow(VoiceInputState(statusText = "Ready", isReady = true))
  val uiState: StateFlow<VoiceInputState> = _uiState.asStateFlow()

  private var hasRecordAudioPermission = false
  private var recorder: AudioRecord? = null
  private var recordJob: Job? = null
  private var pcmBuffer = ByteArrayOutputStream()
  private var isTranscribing = false
  private var transcribeJob: Job? = null

  private val generativeModel: GenerativeModel by lazy {
    Firebase.ai(backend = GenerativeBackend.googleAI())
      .generativeModel(
        modelName = MODEL_NAME,
        generationConfig =
          generationConfig {
            responseMimeType = "application/json"
            temperature = 0f
          },
      )
  }

  fun setPermissionGranted(granted: Boolean) {
    hasRecordAudioPermission = granted
  }

  fun handleListenToggle(
    onPermissionRequired: () -> Unit,
    onResult: ((original: String, translated: String) -> Unit)? = null,
  ) {
    scope.launch {
      if (_uiState.value.isListening) {
        stopListening(onResult)
        return@launch
      }
      if (isTranscribing) return@launch
      if (!hasRecordAudioPermission) {
        onPermissionRequired()
        return@launch
      }
      startListening(onResult)
    }
  }

  @SuppressLint("MissingPermission")
  private fun startListening(onResult: ((original: String, translated: String) -> Unit)?) {
    pendingOnResult = onResult
    try {
      val minBuffer =
        AudioRecord.getMinBufferSize(
          SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      if (minBuffer <= 0) {
        showError("The microphone is not available on this device.")
        return
      }
      val audioRecord =
        AudioRecord(
          MediaRecorder.AudioSource.MIC,
          SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minBuffer * 2,
        )
      if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        showError("The microphone could not be started. Is it in use by another app?")
        return
      }
      pcmBuffer = ByteArrayOutputStream()
      audioRecord.startRecording()
      recorder = audioRecord
      _uiState.value =
        VoiceInputState(
          isListening = true,
          showDialog = true,
          statusText = "Listening...",
          isReady = true,
        )
      val buffer = ByteArray(minBuffer)
      recordJob =
        scope.launch(Dispatchers.IO) {
          while (isActive) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read <= 0) break
            pcmBuffer.write(buffer, 0, read)
            _uiState.update { it.copy(audioLevel = rmsLevel(buffer, read)) }
            if (pcmBuffer.size() >= MAX_RECORDING_BYTES) {
              scope.launch { stopListening(pendingOnResult) }
              break
            }
          }
        }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e(TAG, "Failed to start recording", e)
      showError(getUserFriendlyErrorMessage(e))
    }
  }

  private var pendingOnResult: ((original: String, translated: String) -> Unit)? = null

  private suspend fun stopListening(onResult: ((original: String, translated: String) -> Unit)?) {
    val audioRecord = recorder ?: return
    val job = recordJob
    recorder = null
    recordJob = null
    _uiState.update {
      it.copy(isListening = false, audioLevel = 0f, statusText = "Transcribing...")
    }
    try {
      // stop() first so a blocking read() returns, then wait for the read loop to finish.
      audioRecord.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to stop AudioRecord cleanly", e)
    }
    job?.cancelAndJoin()
    audioRecord.release()

    val pcm = pcmBuffer.toByteArray()
    if (pcm.size < MIN_RECORDING_BYTES) {
      _uiState.value = VoiceInputState(statusText = "Ready", isReady = true)
      return
    }
    transcribe(pcm, onResult)
  }

  private fun transcribe(
    pcm: ByteArray,
    onResult: ((original: String, translated: String) -> Unit)?,
  ) {
    isTranscribing = true
    transcribeJob =
      scope.launch {
        try {
          val wav = pcmToWav(pcm)
          val language = FeatureFlags.DEMO_LANGUAGE
          val prompt =
            "Transcribe the speech in this audio exactly as spoken. If it is not in English, " +
              "also translate it into English. The speaker most likely speaks the language with " +
              "ISO code \"$language\". Respond ONLY with JSON in this form: " +
              "{\"original\": \"...\", \"english\": \"...\"}. " +
              "If there is no speech, return empty strings."
          val response =
            generativeModel.generateContent(
              content {
                inlineData(wav, "audio/wav")
                text(prompt)
              }
            )
          val json = JSONObject(response.text.orEmpty().stripCodeFences())
          val original = json.optString("original").trim()
          val english = json.optString("english").trim().ifEmpty { original }
          if (original.isEmpty()) {
            showError("No speech was detected. Please try again.")
            return@launch
          }
          _uiState.value =
            VoiceInputState(
              isReady = true,
              statusText = "Completed.",
              transcription = original,
              translatedTranscription = english,
              transcriptionResult = english,
            )
          onResult?.invoke(original, english)
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          Log.e(TAG, "Cloud transcription failed", e)
          showError(getUserFriendlyErrorMessage(e))
        } finally {
          isTranscribing = false
        }
      }
  }

  fun clearTranscriptionResult() {
    _uiState.update { it.copy(transcriptionResult = null) }
  }

  fun cancelListening() {
    pendingOnResult = null
    transcribeJob?.cancel()
    transcribeJob = null
    isTranscribing = false
    val audioRecord = recorder
    val job = recordJob
    recorder = null
    recordJob = null
    try {
      audioRecord?.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to stop AudioRecord during cancel", e)
    }
    job?.cancel()
    audioRecord?.release()
    _uiState.value = VoiceInputState(statusText = "Ready", isReady = true)
  }

  /** Translates [text] to English with a cloud Gemini model; returns [text] on any failure. */
  suspend fun translate(text: String): String {
    if (text.isBlank() || FeatureFlags.DEMO_LANGUAGE == "en") return text
    return try {
      val response =
        generativeModel.generateContent(
          "Translate the following text to English. Respond ONLY with JSON: " +
            "{\"english\": \"...\"}\n\n$text"
        )
      JSONObject(response.text.orEmpty().stripCodeFences()).optString("english").ifBlank { text }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "Cloud translation failed, returning original text", e)
      text
    }
  }

  fun close() {
    cancelListening()
  }

  private fun showError(message: String) {
    isTranscribing = false
    _uiState.value =
      VoiceInputState(
        isReady = true,
        isError = true,
        showDialog = true,
        statusText = message,
      )
  }

  private fun getUserFriendlyErrorMessage(e: Throwable?): String {
    val msg = e?.message ?: return "An unexpected error occurred."
    return when {
      msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
        msg.contains("permission", ignoreCase = true) ->
        "Microphone permission is required for voice input."
      msg.contains("NETWORK", ignoreCase = true) ||
        msg.contains("internet", ignoreCase = true) ||
        msg.contains("timeout", ignoreCase = true) ||
        msg.contains("UnknownHost", ignoreCase = true) ->
        "Network error. Please check your connection."
      else -> "Voice input failed: $msg"
    }
  }

  private fun rmsLevel(buffer: ByteArray, length: Int): Float {
    val samples = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return 0f
    var sum = 0.0
    while (samples.hasRemaining()) {
      val s = samples.get().toDouble()
      sum += s * s
    }
    return (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
  }

  private fun pcmToWav(pcm: ByteArray): ByteArray {
    val byteRate = SAMPLE_RATE * 2
    val header =
      ByteBuffer.allocate(44)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
          put("RIFF".toByteArray())
          putInt(36 + pcm.size)
          put("WAVE".toByteArray())
          put("fmt ".toByteArray())
          putInt(16)
          putShort(1) // PCM
          putShort(1) // mono
          putInt(SAMPLE_RATE)
          putInt(byteRate)
          putShort(2) // block align
          putShort(16) // bits per sample
          put("data".toByteArray())
          putInt(pcm.size)
        }
        .array()
    return header + pcm
  }

  private fun String.stripCodeFences(): String =
    trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

  private companion object {
    const val TAG = "VoiceInputManager"
    const val MODEL_NAME = "gemini-3.5-flash-lite"
    const val SAMPLE_RATE = 16_000
    const val MIN_RECORDING_BYTES = SAMPLE_RATE / 2 // ~0.25 s of 16-bit mono audio
    const val MAX_RECORDING_BYTES = SAMPLE_RATE * 2 * 60 // 60 s cap keeps the request small
  }
}
