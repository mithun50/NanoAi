package com.nanoai.llm

import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nanoai.llm.databinding.ActivityLogsBinding
import com.nanoai.llm.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * LogActivity - Display app logs for debugging.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var logAdapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeLogs()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        logAdapter = LogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@LogActivity).apply {
                stackFromEnd = true
            }
            adapter = logAdapter
        }

        binding.btnShare.setOnClickListener { shareLogs() }
        binding.btnClear.setOnClickListener { confirmClear() }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            AppLogger.logs.collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.layoutEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.rvLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE

                // Auto-scroll to bottom
                if (logs.isNotEmpty()) {
                    binding.rvLogs.post {
                        binding.rvLogs.scrollToPosition(logs.size - 1)
                    }
                }
            }
        }
    }

    private fun shareLogs() {
        val logsText = AppLogger.getLogsAsText()
        if (logsText.isEmpty()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NanoAi Logs")
            putExtra(Intent.EXTRA_TEXT, logsText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share logs"))
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Clear") { _, _ ->
                AppLogger.clear()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Log entry adapter.
     */
    private inner class LogAdapter :
        ListAdapter<AppLogger.LogEntry, LogAdapter.ViewHolder>(LogDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvLevel: TextView = itemView.findViewById(R.id.tvLevel)
            private val tvTag: TextView = itemView.findViewById(R.id.tvTag)
            private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

            fun bind(entry: AppLogger.LogEntry) {
                tvTime.text = entry.timeFormatted
                tvLevel.text = entry.levelChar.toString()
                tvTag.text = entry.tag
                tvMessage.text = entry.message

                // Color by level
                val levelColor = when (entry.level) {
                    AppLogger.Level.DEBUG -> getColor(R.color.text_hint)
                    AppLogger.Level.INFO -> getColor(R.color.info)
                    AppLogger.Level.WARN -> getColor(R.color.warning)
                    AppLogger.Level.ERROR -> getColor(R.color.error)
                }
                tvLevel.setTextColor(levelColor)
            }
        }
    }

    private class LogDiffCallback : DiffUtil.ItemCallback<AppLogger.LogEntry>() {
        override fun areItemsTheSame(
            oldItem: AppLogger.LogEntry,
            newItem: AppLogger.LogEntry
        ) = oldItem.timestamp == newItem.timestamp && oldItem.message == newItem.message

        override fun areContentsTheSame(
            oldItem: AppLogger.LogEntry,
            newItem: AppLogger.LogEntry
        ) = oldItem == newItem
    }
}
