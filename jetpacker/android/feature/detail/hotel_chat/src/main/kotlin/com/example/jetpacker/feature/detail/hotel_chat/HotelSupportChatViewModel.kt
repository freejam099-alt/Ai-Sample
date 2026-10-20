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

package com.example.jetpacker.feature.detail.hotel_chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException


class HotelSupportChatViewModel(val hotelName: String = "Hotel", val language: String = "English") :
  ViewModel() {

  private val _messages = MutableStateFlow<List<SupportChatMessage>>(emptyList())
  val messages: StateFlow<List<SupportChatMessage>> = _messages.asStateFlow()

  private val _currentUserId = MutableStateFlow("User")
  val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

  private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
  val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

  private val _selectedLanguage = MutableStateFlow("English")
  val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

  fun setSelectedLanguage(language: String) {
    _selectedLanguage.value = language
  }

  // Cloud Gemini model used to translate chat messages.
  private val translationModel =
    Firebase.ai(backend = GenerativeBackend.googleAI())
      .generativeModel(modelName = "gemini-3.6-flash")

  // Firebase AI for chat
  private val generativeModel =
    Firebase.ai(backend = GenerativeBackend.googleAI())
      .generativeModel(
        systemInstruction =
          content {
            text(
              "You are a helpful hotel receptionist at $hotelName only speaking $language. Answer politely in $language. The bar closes at 10pm and breakfast is from 7am to 10am. There's someone at the desk 24/7. You can retrieve your luggage from the storage room at the back of the lobby at any time."
            )
          },
        modelName = "gemini-3.6-flash",
      )

  private val chat = generativeModel.startChat()

  fun translateMessage(message: SupportChatMessage) {
    if (message.id.isEmpty()) return
    viewModelScope.launch {
      try {
        _translations.update { current -> current + (message.id to "\n\nTranslating...") }
        val lang = _selectedLanguage.value

        val prompt =
          "Translate the following text to $lang. Just return the translated sentence: ${message.text}."

        val translatedText = translationModel.generateContent(prompt).text
        val routePrefix = "[Cloud / Gemini]"

        if (translatedText != null) {
          _translations.update { current ->
            current + (message.id to "\n\n$routePrefix: $translatedText")
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("HotelSupportChatViewModel", "Cloud translation error", e)
        _translations.update { current ->
          current + (message.id to "\n\nError: ${e.message}")
        }
      }
    }
  }

  fun setUserId(userId: String) {
    // Do nothing
  }

  fun sendMessage(text: String) {
    if (text.isBlank()) return
    val botMsgId = UUID.randomUUID().toString()
    _messages.update { current ->
      val userMessage =
        SupportChatMessage(
          id = UUID.randomUUID().toString(),
          text = text,
          senderId = "User",
          senderName = "You",
          timestamp = Timestamp.now(),
        )
      val botMessage =
        SupportChatMessage(
          id = botMsgId,
          text = "Typing...",
          senderId = "AI",
          senderName = "Hotel Support",
          timestamp = Timestamp.now(),
        )
      current + userMessage + botMessage
    }

    viewModelScope.launch {
      try {
        val response = chat.sendMessage(text)
        _messages.update { current ->
          current.map { msg ->
            if (msg.id == botMsgId) {
              msg.copy(text = response.text?.trim() ?: "No response")
            } else {
              msg
            }
          }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("HotelSupportChatViewModel", "Chat Error", e)
        _messages.update { current ->
          current.map { msg ->
            if (msg.id == botMsgId) {
              msg.copy(text = "Error: ${e.message}")
            } else {
              msg
            }
          }
        }
      }
    }
  }

  data class SupportChatMessage(
    val id: String = "",
    val text: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val timestamp: Timestamp? = null,
  )
}
