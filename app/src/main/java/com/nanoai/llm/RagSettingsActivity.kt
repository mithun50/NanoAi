package com.nanoai.llm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanoai.llm.databinding.ActivityRagSettingsBinding
import com.nanoai.llm.rag.IndexingState
import com.nanoai.llm.rag.RagSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * RagSettingsActivity - Configure RAG and manage indexed sources.
 */
class RagSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRagSettingsBinding
    private lateinit var sourceAdapter: SourceAdapter

    private val ragManager by lazy { nanoAiApp.ragManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRagSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
    }

    private fun setupUI() {
        // Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        // RAG toggle
        binding.switchRagEnabled.setOnCheckedChangeListener { _, isChecked ->
            ragManager.setEnabled(isChecked)
        }

        // Sliders
        binding.sliderTopK.addOnChangeListener { _, value, _ ->
            binding.tvTopK.text = value.toInt().toString()
            ragManager.setTopK(value.toInt())
        }

        binding.sliderChunkSize.addOnChangeListener { _, value, _ ->
            binding.tvChunkSize.text = value.toInt().toString()
            ragManager.setChunkSize(value.toInt())
        }

        // Sources list
        sourceAdapter = SourceAdapter { source ->
            confirmDeleteSource(source)
        }
        binding.rvSources.apply {
            layoutManager = LinearLayoutManager(this@RagSettingsActivity)
            adapter = sourceAdapter
        }

        // Add source button
        binding.btnAddSource.setOnClickListener { showAddSourceDialog() }

        // Clear all button
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }

    private fun observeState() {
        // RAG enabled state
        lifecycleScope.launch {
            ragManager.isEnabled.collectLatest { enabled ->
                binding.switchRagEnabled.isChecked = enabled
            }
        }

        // Top K
        lifecycleScope.launch {
            ragManager.topK.collectLatest { value ->
                binding.sliderTopK.value = value.toFloat()
                binding.tvTopK.text = value.toString()
            }
        }

        // Chunk size
        lifecycleScope.launch {
            ragManager.chunkSize.collectLatest { value ->
                binding.sliderChunkSize.value = value.toFloat()
                binding.tvChunkSize.text = value.toString()
            }
        }

        // Sources
        lifecycleScope.launch {
            ragManager.sources.collectLatest { sources ->
                sourceAdapter.submitList(sources)
                binding.tvNoSources.visibility =
                    if (sources.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSources.visibility =
                    if (sources.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        // Indexing state
        lifecycleScope.launch {
            ragManager.indexingState.collectLatest { state ->
                when (state) {
                    is IndexingState.Idle -> {
                        binding.layoutIndexing.visibility = View.GONE
                    }
                    is IndexingState.Scraping -> {
                        binding.layoutIndexing.visibility = View.VISIBLE
                        binding.tvIndexingStatus.text = "Scraping: ${state.url}"
                    }
                    is IndexingState.Chunking -> {
                        binding.layoutIndexing.visibility = View.VISIBLE
                        binding.tvIndexingStatus.text = "Chunking: ${state.title}"
                    }
                    is IndexingState.Embedding -> {
                        binding.layoutIndexing.visibility = View.VISIBLE
                        binding.tvIndexingStatus.text =
                            "Embedding: ${state.current}/${state.total}"
                    }
                    is IndexingState.Storing -> {
                        binding.layoutIndexing.visibility = View.VISIBLE
                        binding.tvIndexingStatus.text = "Storing vectors..."
                    }
                    is IndexingState.Complete -> {
                        binding.layoutIndexing.visibility = View.GONE
                        Toast.makeText(
                            this@RagSettingsActivity,
                            "Indexed: ${state.source.title}",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateStats()
                    }
                    is IndexingState.Error -> {
                        binding.layoutIndexing.visibility = View.GONE
                        Toast.makeText(
                            this@RagSettingsActivity,
                            "Error: ${state.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Update stats initially
        updateStats()
    }

    private fun updateStats() {
        val stats = ragManager.getStats()
        binding.tvSourcesCount.text = stats.sources.toString()
        binding.tvChunksCount.text = stats.totalChunks.toString()
        binding.tvMemoryUsage.text = "%.1f MB".format(stats.estimatedMemoryMB)
    }

    private fun showAddSourceDialog() {
        if (!LlamaBridge.isModelLoaded()) {
            Toast.makeText(this, "Load a model first for embeddings", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_source, null)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etUrl)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Source")
            .setView(dialogView)
            .setPositiveButton("Index") { _, _ ->
                val url = etUrl.text?.toString()?.trim() ?: return@setPositiveButton
                if (url.isNotEmpty()) {
                    indexUrl(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun indexUrl(url: String) {
        lifecycleScope.launch {
            ragManager.indexUrl(url)
        }
    }

    private fun confirmDeleteSource(source: RagSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Source")
            .setMessage("Delete \"${source.title}\" and all its chunks?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    ragManager.deleteSource(source.id)
                    updateStats()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All RAG Data")
            .setMessage("Delete all indexed sources and embeddings?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    ragManager.clearAll()
                    updateStats()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Source list adapter
     */
    private inner class SourceAdapter(
        private val onDelete: (RagSource) -> Unit
    ) : ListAdapter<RagSource, SourceAdapter.ViewHolder>(SourceDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tvSourceTitle)
            private val tvInfo: TextView = itemView.findViewById(R.id.tvSourceInfo)
            private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteSource)

            fun bind(source: RagSource) {
                tvTitle.text = source.title
                tvInfo.text = "${source.chunksCount} chunks • ${source.wordCount} words"
                btnDelete.setOnClickListener { onDelete(source) }
            }
        }
    }

    private class SourceDiffCallback : DiffUtil.ItemCallback<RagSource>() {
        override fun areItemsTheSame(oldItem: RagSource, newItem: RagSource) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RagSource, newItem: RagSource) =
            oldItem == newItem
    }
}
