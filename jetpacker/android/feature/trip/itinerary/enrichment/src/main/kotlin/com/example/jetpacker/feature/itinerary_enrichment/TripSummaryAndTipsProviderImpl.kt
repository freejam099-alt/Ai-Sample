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

package com.example.jetpacker.feature.itinerary_enrichment

import android.util.Log
import com.example.jetpacker.data.itinerary.TimelineEvent
import com.example.jetpacker.data.itinerary.VoiceNoteEntity
import com.example.jetpacker.data.trips.Trip
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private enum class TripStatus {
  PAST,
  CURRENT,
  UPCOMING,
}

/** Generates trip summaries and tips with a cloud Gemini model (Firebase AI Logic). */
@Singleton
class TripSummaryAndTipsProviderImpl @Inject constructor() : TripSummaryAndTipsProvider {

  private val generativeModel by lazy {
    Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.5-flash-lite")
  }

  // Cloud models are reachable on every device, so the feature is always supported.
  override suspend fun isSupported(): Boolean = true

  private fun streamText(prompt: String): Flow<String> = flow {
    try {
      generativeModel.generateContentStream(prompt).collect { chunk -> emit(chunk.text ?: "") }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e("TripSummaryAndTips", "Cloud generation stream failed", e)
      throw e
    }
  }

  override fun generateTripSummaryAndTips(events: List<TimelineEvent>, trip: Trip): Flow<String> {
    val prompt =
      "Given this itinerary for a trip: $events, " +
        "generate the following: " +
        "overall trip summary in 1 sentence less than 15 words without a bolded title, " +
        "1 tip on how to prepare for this trip with a bolded title \"Tip\""

    val promptWithShortPhrases =
      "Given this itinerary for a trip: $events, " +
        "generate the following: " +
        "overall trip summary in 1 sentence less than 15 words without a bolded title, " +
        "1 tip on how to prepare for this trip with a bolded title \"Tip\", " +
        "2 common short phrases to learn for the trip, with a bolded title \"Useful Phrases\". Format each strictly as '* Foreign Phrase - English Translation' (e.g., '* Bonjour - Hello')."

    val countriesWithEnglishLocale = listOf("USA", "Ireland", "UK", "Australia")
    val location = trip.location

    val selectedPrompt =
      if (countriesWithEnglishLocale.any { location.contains(it) }) {
        prompt
      } else {
        promptWithShortPhrases
      }

    return streamText(selectedPrompt)
  }

  override fun generateTripSummary(
    events: List<TimelineEvent>,
    trip: Trip,
    voiceNotes: List<VoiceNoteEntity>,
  ): Flow<String> {
    val prompt =
      "Given these voice notes for a trip: $voiceNotes, use a reminiscent tone to" +
        " generate a short 2 sentence summary of the highlights. Only return the summary."
    return streamText(prompt)
  }

  override fun generateUpcomingTip(events: List<TimelineEvent>, trip: Trip): Flow<String> {
    val prompt =
      "Given this itinerary for the upcoming events of a trip: $events, " +
        "generate 2 short tips. " +
        "return only the 2 short tips."
    return streamText(prompt)
  }
}
