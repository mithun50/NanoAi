package com.nanoai.llm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nanoai.llm.databinding.ActivityMainBinding
import com.nanoai.llm.model.LoadingState
import com.nanoai.llm.tools.ToolManager
import com.nanoai.llm.tools.WebFetcher
import com.nanoai.llm.ui.ChatAdapter
import com.nanoai.llm.ui.ChatMessage
import com.nanoai.llm.util.AppLogger
import com.nanoai.llm.util.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity - Main chat interface for NanoAi.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter

    private val modelManager by lazy { nanoAiApp.modelManager }
    private val ragManager by lazy { nanoAiApp.ragManager }

    private var isGenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupUI()
        observeState()

        // Auto-load last used model
        loadLastModel()
    }

    private fun setupWindowInsets() {
        // Handle system bars and keyboard insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply top padding to toolbar for status bar
            binding.toolbar.updatePadding(top = systemBars.top)

            // Apply bottom padding to input area for navigation bar or keyboard
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            binding.layoutInput.updatePadding(bottom = bottomPadding)

            // Apply left/right padding to root for gesture navigation
            view.updatePadding(left = systemBars.left, right = systemBars.right)

            // Scroll to bottom when keyboard appears
            if (ime.bottom > 0 && ::chatAdapter.isInitialized) {
                binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
            }

            WindowInsetsCompat.CONSUMED
        }

        // Request insets to be applied immediately
        ViewCompat.requestApplyInsets(binding.rootLayout)
    }

    private fun loadLastModel() {
        lifecycleScope.launch {
            try {
                AppLogger.i(TAG, "Loading last active model...")
                modelManager.loadLastActiveModel()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load last model: ${e.message}", e)
            }
        }
    }

    private fun setupUI() {
        // Setup RecyclerView
        chatAdapter = ChatAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Send button
        binding.fabSend.setOnClickListener {
            if (isGenerating) {
                stopGeneration()
            } else {
                sendMessage()
            }
        }

        // Enter key sends message
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // Ensure keyboard opens when input field is clicked
        binding.etMessage.setOnClickListener {
            binding.etMessage.requestFocus()
            showKeyboard()
        }

        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboard()
            }
        }

        // Load model button (empty state)
        binding.btnLoadModel.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }

        // RAG button
        binding.btnRag.setOnClickListener {
            startActivity(Intent(this, RagSettingsActivity::class.java))
        }

        // Update RAG button color based on state
        lifecycleScope.launch {
            ragManager.isEnabled.collectLatest { enabled ->
                val color = if (enabled && ragManager.vectorStore.totalChunks > 0) {
                    getColor(R.color.accent)
                } else {
                    getColor(R.color.text_secondary)
                }
                binding.btnRag.setColorFilter(color)
            }
        }

        // Menu button
        binding.btnMenu.setOnClickListener { showMenu(it) }
    }

    private fun observeState() {
        // Model loading state
        lifecycleScope.launch {
            modelManager.loadingState.collectLatest { state ->
                updateModelStatus(state)
            }
        }

        // Active model
        lifecycleScope.launch {
            modelManager.activeModel.collectLatest { model ->
                val hasModel = model != null
                binding.layoutEmpty.visibility = if (hasModel) View.GONE else View.VISIBLE
                binding.etMessage.isEnabled = hasModel
                binding.fabSend.isEnabled = hasModel

                if (model != null) {
                    binding.tvModelStatus.text = model.name.take(15)
                } else {
                    binding.tvModelStatus.text = getString(R.string.label_no_model)
                }
            }
        }
    }

    private fun updateModelStatus(state: LoadingState) {
        when (state) {
            is LoadingState.Idle -> {
                binding.progressLoading.visibility = View.GONE
            }
            is LoadingState.Loading -> {
                binding.progressLoading.visibility = View.VISIBLE
                binding.tvModelStatus.text = getString(R.string.label_loading_model)
            }
            is LoadingState.Loaded -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvModelStatus.text = state.modelName.take(15)
            }
            is LoadingState.Error -> {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
            else -> {
                binding.progressLoading.visibility = View.VISIBLE
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        if (!LlamaBridge.isModelLoaded()) {
            Toast.makeText(this, R.string.label_no_model, Toast.LENGTH_SHORT).show()
            return
        }

        // Add user message
        chatAdapter.addMessage(ChatMessage(text = text, isAi = false))
        binding.etMessage.text?.clear()

        // Hide empty state
        binding.layoutEmpty.visibility = View.GONE

        // Scroll to bottom
        binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)

        // Add loading placeholder
        chatAdapter.addLoadingMessage()
        binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)

        // Start generation
        startGeneration(text)
    }

    private fun startGeneration(userMessage: String) {
        isGenerating = true
        updateSendButton()

        // Show generating notification
        NotificationHelper.showGeneratingNotification(this, userMessage)

        lifecycleScope.launch {
            try {
                // Check if message contains a URL for web fetching
                val extractedUrl = ToolManager.extractUrlFromMessage(userMessage)
                val needsWebFetch = extractedUrl != null || ToolManager.needsWebBrowsing(userMessage)

                // Check if RAG is enabled and has data
                val useRag = ragManager.isEnabled.value &&
                        ragManager.vectorStore.totalChunks > 0

                val result = when {
                    // Priority 1: Direct URL fetch
                    extractedUrl != null -> {
                        AppLogger.i(TAG, "Fetching URL: $extractedUrl")
                        chatAdapter.updateLastAiMessage(
                            text = "Fetching web page...",
                            isComplete = false
                        )
                        // Show web fetch notification
                        NotificationHelper.showWebFetchNotification(this@MainActivity, extractedUrl)

                        val fetchResult = ToolManager.fetchAndSummarize(
                            url = extractedUrl,
                            userQuestion = userMessage,
                            params = GenerationParams.BALANCED
                        ).map { com.nanoai.llm.rag.RagResponse(it, emptyList(), 0) }

                        NotificationHelper.cancelWebFetchNotification(this@MainActivity)
                        fetchResult
                    }

                    // Priority 2: RAG with documents
                    useRag -> {
                        ragManager.generateWithRag(
                            userQuery = userMessage,
                            params = GenerationParams.BALANCED
                        )
                    }

                    // Priority 3: Tool-enabled generation (model can request URLs)
                    needsWebFetch -> {
                        ToolManager.generateWithTools(
                            prompt = userMessage,
                            params = GenerationParams.BALANCED
                        ).map { com.nanoai.llm.rag.RagResponse(it.response, emptyList(), 0) }
                    }

                    // Default: Standard generation
                    else -> {
                        val prompt = ragManager.buildPrompt(userQuery = userMessage)
                        LlamaBridge.generateAsync(prompt, GenerationParams.BALANCED)
                            .map { com.nanoai.llm.rag.RagResponse(it, emptyList(), 0) }
                    }
                }

                result.onSuccess { response ->
                    chatAdapter.updateLastAiMessage(
                        text = response.response,
                        isComplete = true,
                        sources = response.chunksUsed
                    )
                }.onFailure { error ->
                    chatAdapter.updateLastAiMessage(
                        text = "Error: ${error.message}",
                        isComplete = true
                    )
                }

            } catch (e: Exception) {
                chatAdapter.updateLastAiMessage(
                    text = "Error: ${e.message}",
                    isComplete = true
                )
            } finally {
                isGenerating = false
                updateSendButton()
                // Cancel notifications
                NotificationHelper.cancelGeneratingNotification(this@MainActivity)
                NotificationHelper.cancelWebFetchNotification(this@MainActivity)
                binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun stopGeneration() {
        LlamaBridge.stopGeneration()
        isGenerating = false
        updateSendButton()
        // Cancel notifications
        NotificationHelper.cancelGeneratingNotification(this)
        NotificationHelper.cancelWebFetchNotification(this)
    }

    private fun updateSendButton() {
        if (isGenerating) {
            binding.fabSend.setImageResource(R.drawable.ic_stop)
            binding.fabSend.contentDescription = getString(R.string.btn_stop)
        } else {
            binding.fabSend.setImageResource(R.drawable.ic_send)
            binding.fabSend.contentDescription = getString(R.string.btn_send)
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_models -> {
                    startActivity(Intent(this, ModelManagerActivity::class.java))
                    true
                }
                R.id.menu_rag -> {
                    startActivity(Intent(this, RagSettingsActivity::class.java))
                    true
                }
                R.id.menu_clear -> {
                    chatAdapter.clearMessages()
                    binding.layoutEmpty.visibility =
                        if (LlamaBridge.isModelLoaded()) View.GONE else View.VISIBLE
                    true
                }
                R.id.menu_logs -> {
                    startActivity(Intent(this, LogActivity::class.java))
                    true
                }
                R.id.menu_settings -> {
                    // TODO: Settings activity
                    Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh states
        if (LlamaBridge.isModelLoaded()) {
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun showKeyboard() {
        binding.etMessage.requestFocus()
        // Use WindowInsetsController for more reliable keyboard show
        Handler(Looper.getMainLooper()).postDelayed({
            val controller = WindowInsetsControllerCompat(window, binding.etMessage)
            controller.show(WindowInsetsCompat.Type.ime())
        }, 100)
    }

    @Suppress("UNUSED")
    private fun hideKeyboard() {
        val controller = WindowInsetsControllerCompat(window, binding.etMessage)
        controller.hide(WindowInsetsCompat.Type.ime())
    }
}
