package com.nanoai.llm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanoai.llm.databinding.ActivityModelManagerBinding
import com.nanoai.llm.model.CatalogModel
import com.nanoai.llm.model.DownloadProgress
import com.nanoai.llm.model.LoadingState
import com.nanoai.llm.model.ModelCatalog
import com.nanoai.llm.model.ModelInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ModelManagerActivity - Manage installed GGUF models.
 */
class ModelManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagerBinding
    private lateinit var modelAdapter: ModelAdapter

    private val modelManager by lazy { nanoAiApp.modelManager }

    // File picker for importing models
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    val importResult = modelManager.importModel(uri)
                    importResult.onSuccess {
                        Toast.makeText(this@ModelManagerActivity,
                            "Model imported: ${it.name}", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this@ModelManagerActivity,
                            "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
    }

    private fun setupUI() {
        // Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Models list
        modelAdapter = ModelAdapter(
            onActivate = { model -> activateModel(model) },
            onDelete = { model -> confirmDelete(model) }
        )
        binding.rvModels.apply {
            layoutManager = LinearLayoutManager(this@ModelManagerActivity)
            adapter = modelAdapter
        }

        // FAB for adding models
        binding.fabAdd.setOnClickListener { showAddOptions() }

        // Update storage info
        updateStorageInfo()
    }

    private fun observeState() {
        // Model list
        lifecycleScope.launch {
            modelManager.installedModels.collectLatest { models ->
                modelAdapter.submitList(models)
                binding.layoutEmpty.visibility =
                    if (models.isEmpty()) View.VISIBLE else View.GONE
                binding.rvModels.visibility =
                    if (models.isEmpty()) View.GONE else View.VISIBLE
                updateStorageInfo()
            }
        }

        // Active model
        lifecycleScope.launch {
            modelManager.activeModel.collectLatest { active ->
                modelAdapter.setActiveModel(active)
            }
        }

        // Loading state
        lifecycleScope.launch {
            modelManager.loadingState.collectLatest { state ->
                when (state) {
                    is LoadingState.Downloading -> {
                        binding.layoutDownloading.visibility = View.VISIBLE
                    }
                    is LoadingState.Importing -> {
                        binding.layoutDownloading.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "Importing model..."
                        binding.progressDownload.isIndeterminate = true
                    }
                    is LoadingState.Loading -> {
                        binding.layoutDownloading.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "Loading: ${state.modelName}"
                        binding.progressDownload.isIndeterminate = true
                    }
                    is LoadingState.Error -> {
                        binding.layoutDownloading.visibility = View.GONE
                        Toast.makeText(this@ModelManagerActivity,
                            state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.layoutDownloading.visibility = View.GONE
                    }
                }
            }
        }

        // Download progress
        lifecycleScope.launch {
            modelManager.downloadProgress.collectLatest { progress ->
                progress?.let {
                    binding.tvDownloadStatus.text =
                        "${it.fileName}: ${it.downloadedMB.toInt()} / ${it.totalMB.toInt()} MB"
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = it.percent
                }
            }
        }
    }

    private fun updateStorageInfo() {
        val used = nanoAiApp.getUsedModelStorage()
        val available = nanoAiApp.getAvailableStorage()
        val total = used + available

        val usedMB = used / (1024.0 * 1024.0)
        val totalGB = total / (1024.0 * 1024.0 * 1024.0)

        binding.tvStorageInfo.text = String.format(
            "Models: %.1f MB  •  Available: %.1f GB",
            usedMB, totalGB
        )

        val percentage = if (total > 0) ((used * 100) / total).toInt() else 0
        binding.progressStorage.progress = percentage
    }

    private fun showAddOptions() {
        val options = arrayOf("Browse Model Catalog", "Import from storage", "Download from URL")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Model")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showModelCatalog()
                    1 -> importFromStorage()
                    2 -> showDownloadDialog()
                }
            }
            .show()
    }

    private fun showModelCatalog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_catalog, null)
        val rvCatalog = dialogView.findViewById<RecyclerView>(R.id.rvCatalog)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Model Catalog")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        val catalogAdapter = CatalogAdapter { model ->
            dialog.dismiss()
            confirmCatalogDownload(model)
        }

        rvCatalog.apply {
            layoutManager = LinearLayoutManager(this@ModelManagerActivity)
            adapter = catalogAdapter
        }

        catalogAdapter.submitList(ModelCatalog.models)
        dialog.show()
    }

    private fun confirmCatalogDownload(model: CatalogModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Download ${model.name}?")
            .setMessage("Size: ${model.sizeMB} MB\nRAM required: ${model.ramRequired}\n\nThis will download the model from HuggingFace.")
            .setPositiveButton("Download") { _, _ ->
                lifecycleScope.launch {
                    modelManager.downloadModel(model.downloadUrl, model.fileName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromStorage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "*/*"
            ))
        }
        importLauncher.launch(intent)
    }

    private fun showDownloadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_model, null)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etUrl)
        val etFileName = dialogView.findViewById<TextInputEditText>(R.id.etFileName)

        MaterialAlertDialogBuilder(this)
            .setTitle("Download Model")
            .setView(dialogView)
            .setPositiveButton("Download") { _, _ ->
                val url = etUrl.text?.toString()?.trim() ?: return@setPositiveButton
                val fileName = etFileName.text?.toString()?.trim() ?: "model.gguf"

                if (url.isNotEmpty()) {
                    lifecycleScope.launch {
                        modelManager.downloadModel(url, fileName)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun activateModel(model: ModelInfo) {
        lifecycleScope.launch {
            val result = modelManager.activateModel(model)
            result.onSuccess {
                Toast.makeText(this@ModelManagerActivity,
                    "Model activated: ${model.name}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@ModelManagerActivity,
                    "Failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(model: ModelInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_model))
            .setMessage("Delete ${model.name}?\n\n${getString(R.string.dialog_delete_confirm)}")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    modelManager.deleteModel(model)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Model list adapter
     */
    private inner class ModelAdapter(
        private val onActivate: (ModelInfo) -> Unit,
        private val onDelete: (ModelInfo) -> Unit
    ) : ListAdapter<ModelInfo, ModelAdapter.ViewHolder>(ModelDiffCallback()) {

        private var activeModelId: String? = null

        fun setActiveModel(model: ModelInfo?) {
            activeModelId = model?.id
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_model, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvModelName)
            private val tvSize: TextView = itemView.findViewById(R.id.tvModelSize)
            private val tvQuant: TextView = itemView.findViewById(R.id.tvQuantization)
            private val tvActive: TextView = itemView.findViewById(R.id.tvActive)
            private val viewActive: View = itemView.findViewById(R.id.viewActive)
            private val btnActivate: MaterialButton = itemView.findViewById(R.id.btnActivate)
            private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDelete)

            fun bind(model: ModelInfo) {
                tvName.text = model.name
                tvSize.text = "Size: ${model.sizeFormatted}"
                tvQuant.text = "Quant: ${model.quantization}"

                val isActive = model.id == activeModelId
                viewActive.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                tvActive.visibility = if (isActive) View.VISIBLE else View.GONE
                btnActivate.isEnabled = !isActive
                btnActivate.text = if (isActive) "Active" else "Activate"

                btnActivate.setOnClickListener { onActivate(model) }
                btnDelete.setOnClickListener { onDelete(model) }
            }
        }
    }

    private class ModelDiffCallback : DiffUtil.ItemCallback<ModelInfo>() {
        override fun areItemsTheSame(oldItem: ModelInfo, newItem: ModelInfo) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ModelInfo, newItem: ModelInfo) =
            oldItem == newItem
    }

    /**
     * Catalog model adapter for displaying available models to download.
     */
    private inner class CatalogAdapter(
        private val onDownload: (CatalogModel) -> Unit
    ) : ListAdapter<CatalogModel, CatalogAdapter.ViewHolder>(CatalogDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_catalog_model, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvModelName)
            private val tvDescription: TextView = itemView.findViewById(R.id.tvModelDescription)
            private val tvSize: TextView = itemView.findViewById(R.id.tvModelSize)
            private val tvRam: TextView = itemView.findViewById(R.id.tvRamRequired)
            private val btnDownload: MaterialButton = itemView.findViewById(R.id.btnDownload)

            fun bind(model: CatalogModel) {
                tvName.text = "${model.name} ${model.quantization}"
                tvDescription.text = model.description
                tvSize.text = "${model.sizeMB} MB"
                tvRam.text = model.ramRequired

                btnDownload.setOnClickListener { onDownload(model) }
                itemView.setOnClickListener { onDownload(model) }
            }
        }
    }

    private class CatalogDiffCallback : DiffUtil.ItemCallback<CatalogModel>() {
        override fun areItemsTheSame(oldItem: CatalogModel, newItem: CatalogModel) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CatalogModel, newItem: CatalogModel) =
            oldItem == newItem
    }
}
