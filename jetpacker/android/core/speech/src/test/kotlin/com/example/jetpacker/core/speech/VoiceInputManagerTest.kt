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

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputManagerTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var voiceInputManager: VoiceInputManager

  @Before
  fun setup() {
    Dispatchers.setMain(testDispatcher)
    // Constructing the manager must never touch Firebase or any on-device model.
    voiceInputManager = VoiceInputManager()
  }

  @Test
  fun initialState_isReadyAndIdle() {
    val state = voiceInputManager.uiState.value
    assertTrue(state.isReady)
    assertFalse(state.isListening)
    assertFalse(state.isError)
    assertFalse(state.showDialog)
  }

  @Test
  fun handleListenToggle_withoutPermission_requestsPermission() = runTest {
    var permissionRequested = false
    voiceInputManager.setPermissionGranted(false)
    voiceInputManager.handleListenToggle(onPermissionRequired = { permissionRequested = true })
    advanceUntilIdle()

    assertTrue(permissionRequested)
    assertFalse(voiceInputManager.uiState.value.isListening)
  }

  @Test
  fun cancelListening_resetsToReadyState() = runTest {
    voiceInputManager.cancelListening()
    val state = voiceInputManager.uiState.value

    assertFalse(state.isListening)
    assertFalse(state.isError)
    assertFalse(state.showDialog)
    assertEquals("Ready", state.statusText)
  }

  @Test
  fun userFriendlyErrorMapping_translatesExceptionsCorrectly() {
    val method =
      VoiceInputManager::class
        .java
        .getDeclaredMethod("getUserFriendlyErrorMessage", Throwable::class.java)
    method.isAccessible = true

    val network = method.invoke(voiceInputManager, Exception("NETWORK_ERROR")) as String
    assertEquals("Network error. Please check your connection.", network)

    val permission = method.invoke(voiceInputManager, SecurityException("permission denied")) as String
    assertEquals("Microphone permission is required for voice input.", permission)

    val unknown = method.invoke(voiceInputManager, Exception("boom")) as String
    assertEquals("Voice input failed: boom", unknown)

    val none = method.invoke(voiceInputManager, null) as String
    assertEquals("An unexpected error occurred.", none)
  }
}
