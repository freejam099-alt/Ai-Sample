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

package com.example.jetpacker.feature.expenses

import kotlinx.coroutines.CancellationException
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import android.graphics.Bitmap
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptParserImpl @Inject constructor() : ReceiptParser {
  private val generativeModel by lazy {
    Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.5-flash-lite")
  }

  // Receipt parsing always runs on a cloud Gemini model, so it is supported on every device.
  override suspend fun isSupported(): Boolean = true

  override suspend fun parseReceipt(bitmap: Bitmap): String {
    val prompt =
      "Determine if the image looks like a type of expense or receipt. " +
        "If it is not an expense or receipt, return false. " +
        "Otherwise parse the image for the following information and return it in JSON format: " +
        "1. title: Generate a title for the expense less than 6 words. The title should be based on the name of the restaurant or activity. The title should not include the word receipt." +
        "2. amount: Total amount of the entire expense. Look for values at the bottom of the receipt and words like total or balance due. Do not include any currency signs." +
        "3. currency: The ISO 4217 currency code of the amount (e.g., USD, INR, EUR, GBP, JPY, SGD). If not found, default to USD." +
        "4. category: Type of expense, whether it's travel, food, activity, shopping, entertainment, or other " +
        "The JSON should follow this structure: {\"title\": \"...\", \"amount\": 0.0, \"currency\": \"...\", \"category\": \"...\"}"

    var fullResponse = ""
    try {
      val response =
        generativeModel.generateContent(
          content {
            image(bitmap)
            text(prompt)
          }
        )
      fullResponse = response.text ?: ""
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e("ReceiptParser", "Error using cloud Gemini model", e)
    }
    return fullResponse
  }
}
