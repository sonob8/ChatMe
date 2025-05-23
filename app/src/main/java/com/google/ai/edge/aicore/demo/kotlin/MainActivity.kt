/*
 * Copyright 2024 Google LLC. All rights reserved.
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

package com.google.ai.edge.aicore.demo.kotlin

import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.AdapterView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog // Added for history dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.demo.ContentAdapter
import com.google.ai.edge.aicore.demo.GenerationConfigDialog
import com.google.ai.edge.aicore.demo.GenerationConfigUtils
import com.google.ai.edge.aicore.demo.R
import com.google.ai.edge.aicore.generationConfig
import dev.langchain4j.data.memory.ChatMemory // Added
import dev.langchain4j.data.memory.chat.MessageWindowChatMemory // Added
import dev.langchain4j.data.message.AiMessage // Added (already there but good to note)
import dev.langchain4j.data.message.SystemMessage // Added for history display
import dev.langchain4j.data.message.UserMessage // Added (already there but good to note)
import java.util.concurrent.Future
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.future.future

/** Demonstrates the AICore SDK usage from Kotlin. */
class MainActivity : AppCompatActivity(), GenerationConfigDialog.OnConfigUpdateListener {

  private var requestEditText: EditText? = null
  private var sendButton: Button? = null
  private var streamingSwitch: CompoundButton? = null
  private var configButton: Button? = null
  private var contentRecyclerView: RecyclerView? = null
  private var model: GenerativeModel? = null
  private var personaSpinner: Spinner? = null
  private var chatModel: EdgeAiCoreChatModel? = null
  private val personaPrompts: MutableList<String> = ArrayList()
  private var chatMemory: ChatMemory? = null // Added
  private var viewHistoryButton: Button? = null // Added
  private var useStreaming = false // Will be effectively disabled for now
  private var inGenerating = false
  private var generateContentFuture: Future<Unit>? = null

  private val contentAdapter = ContentAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    requestEditText = findViewById(R.id.request_edit_text)
    sendButton = findViewById(R.id.send_button)
    sendButton!!.setOnClickListener {
      if (inGenerating) {
        generateContentFuture?.cancel(true)
        endGeneratingUi()
      } else {
        val request = requestEditText?.text.toString()
        if (TextUtils.isEmpty(request)) {
          Toast.makeText(this, R.string.prompt_is_empty, Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }

        contentAdapter.addContent(ContentAdapter.VIEW_TYPE_REQUEST, request)
        startGeneratingUi()
        generateContent(request)
      }
      inGenerating = !inGenerating
    }

    streamingSwitch = findViewById<CompoundButton>(R.id.streaming_switch)
    // Temporarily disable streaming switch's effect as EdgeAiCoreChatModel doesn't support it yet.
    // streamingSwitch!!.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
    //   useStreaming = isChecked
    // }
    // useStreaming = streamingSwitch!!.isChecked
    streamingSwitch!!.isEnabled = false // Disable the switch visually
    useStreaming = false // Force non-streaming

    personaSpinner = findViewById(R.id.persona_spinner)

    // Load persona prompts from resources
    val promptsArray = resources.getStringArray(R.array.persona_prompts_array)
    promptsArray.forEach { prompt ->
      personaPrompts.add(prompt)
    }

    // Spinner is populated by XML using persona_display_names_array.
    // No need to set adapter here if android:entries is used correctly in XML.

    personaSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        initializeChatModel()
        contentAdapter.clearMessages() // Clear chat history on persona change
        Toast.makeText(this@MainActivity, "Persona changed. Chat cleared.", Toast.LENGTH_SHORT).show()
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    configButton = findViewById(R.id.config_button)
    configButton!!.setOnClickListener {
      GenerationConfigDialog().show(supportFragmentManager, null)
    }

    viewHistoryButton = findViewById(R.id.view_history_button)
    viewHistoryButton?.setOnClickListener {
      displayConversationHistory()
    }

    contentRecyclerView = findViewById<RecyclerView>(R.id.content_recycler_view)
    contentRecyclerView!!.layoutManager = LinearLayoutManager(this)
    contentRecyclerView!!.adapter = contentAdapter

    initGenerativeModel() // Initialize GenerativeModel first
    initializeChatModel() // Then initialize EdgeAiCoreChatModel
  }

  override fun onDestroy() {
    super.onDestroy()
    model?.close()
  }

  private fun initGenerativeModel() {
    model =
      GenerativeModel(
        generationConfig {
          context = applicationContext
          temperature = GenerationConfigUtils.getTemperature(applicationContext)
          topK = GenerationConfigUtils.getTopK(applicationContext)
          maxOutputTokens = GenerationConfigUtils.getMaxOutputTokens(applicationContext)
        }
      )
  }

  private fun initializeChatModel() {
    if (model == null) {
        initGenerativeModel() // Ensure GenerativeModel is initialized
    }
    val selectedPersonaIndex = personaSpinner?.selectedItemPosition ?: 0
    val selectedSystemPrompt = if (personaPrompts.isNotEmpty() && selectedPersonaIndex < personaPrompts.size) {
        personaPrompts[selectedPersonaIndex]
    } else {
        // Default prompt from strings.xml or a hardcoded default
        getString(R.string.persona_helpful_assistant) // Fallback to the first defined persona prompt
    }
    chatModel = EdgeAiCoreChatModel(model!!, selectedSystemPrompt)
    // Initialize chatMemory here, after chatModel is (re)created
    chatMemory = MessageWindowChatMemory.withMaxMessages(10)
  }

  private fun generateContent(request: String) {
    if (chatModel == null || chatMemory == null) {
      Toast.makeText(this, "Chat model or memory not initialized.", Toast.LENGTH_SHORT).show()
      endGeneratingUi()
      inGenerating = false // Reset inGenerating state
      return
    }

    val userMessage = UserMessage(request) // Langchain4j UserMessage
    chatMemory?.add(userMessage) // Add to Langchain4j memory

    generateContentFuture =
      lifecycleScope.future {
        try {
          val langchainAiResponse = chatModel!!.generate(chatMemory!!.messages()) // Send all history
          val aiMessageText = langchainAiResponse.content().text()
          val aiMessage = AiMessage(aiMessageText) // Langchain4j AiMessage

          chatMemory?.add(aiMessage) // Add AI response to memory

          runOnUiThread { // Ensure UI updates are on the main thread
            contentAdapter.addContent(ContentAdapter.VIEW_TYPE_RESPONSE, aiMessageText)
            endGeneratingUi()
          }
        } catch (e: Exception) { // Catch generic Exception as chatModel might throw different types
          runOnUiThread {
            contentAdapter.addContent(ContentAdapter.VIEW_TYPE_RESPONSE_ERROR, e.message ?: "Unknown error from ChatModel")
            endGeneratingUi()
          }
        }
      }
  }

  private fun displayConversationHistory() {
    if (chatMemory == null || chatMemory!!.messages().isEmpty()) {
        Toast.makeText(this, "No history yet.", Toast.LENGTH_SHORT).show()
        return
    }

    val historyText = StringBuilder()
    chatMemory!!.messages().forEach { message ->
        val prefix = when (message) {
            is UserMessage -> "You: "
            is AiMessage -> "AI: "
            is SystemMessage -> "System: " // System messages are not directly added to memory in current flow
            else -> "Other: "
        }
        historyText.append(prefix).append(message.text()).append("\n\n")
    }

    // Display in an AlertDialog
    AlertDialog.Builder(this)
        .setTitle("Conversation History")
        .setMessage(historyText.toString().trim()) // Use a ScrollView for long history in a real app
        .setPositiveButton("Close", null)
        .show()
  }

  private fun startGeneratingUi() {
    sendButton?.setText(R.string.button_cancel)
    requestEditText?.setText(R.string.empty)
    streamingSwitch?.isEnabled = false
    configButton?.isEnabled = false
  }

  private fun endGeneratingUi() {
    sendButton?.setText(R.string.button_send)
    streamingSwitch?.isEnabled = true
    configButton?.isEnabled = true
    contentRecyclerView?.smoothScrollToPosition(contentAdapter.itemCount - 1)
  }

  override fun onConfigUpdated() {
    model?.close()
    initGenerativeModel()
    initializeChatModel() // Re-initialize chatModel with potentially new GenerativeModel config
  }
}
