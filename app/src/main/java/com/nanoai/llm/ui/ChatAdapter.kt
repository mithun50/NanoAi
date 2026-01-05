package com.nanoai.llm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nanoai.llm.R

/**
 * ChatAdapter - RecyclerView adapter for chat messages.
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Add a new message.
     */
    fun addMessage(message: ChatMessage) {
        val newList = currentList.toMutableList()
        newList.add(message)
        submitList(newList)
    }

    /**
     * Update the last AI message (for streaming).
     */
    fun updateLastAiMessage(text: String, isComplete: Boolean = false, sources: Int = 0) {
        val newList = currentList.toMutableList()
        val lastIndex = newList.indexOfLast { it.isAi && it.isLoading }
        if (lastIndex >= 0) {
            newList[lastIndex] = newList[lastIndex].copy(
                text = text,
                isLoading = !isComplete,
                sourcesCount = sources
            )
            submitList(newList)
        }
    }

    /**
     * Add a loading placeholder for AI response.
     */
    fun addLoadingMessage(): Int {
        val newList = currentList.toMutableList()
        val message = ChatMessage(
            id = System.currentTimeMillis(),
            text = "",
            isAi = true,
            isLoading = true
        )
        newList.add(message)
        submitList(newList)
        return newList.size - 1
    }

    /**
     * Clear all messages.
     */
    fun clearMessages() {
        submitList(emptyList())
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUserMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
        private val layoutAiMessage: LinearLayout = itemView.findViewById(R.id.layoutAiMessage)
        private val tvAiMessage: TextView = itemView.findViewById(R.id.tvAiMessage)
        private val tvSources: TextView = itemView.findViewById(R.id.tvSources)
        private val layoutLoading: LinearLayout = itemView.findViewById(R.id.layoutLoading)

        fun bind(message: ChatMessage) {
            if (message.isAi) {
                tvUserMessage.visibility = View.GONE

                if (message.isLoading && message.text.isEmpty()) {
                    // Show loading indicator
                    layoutAiMessage.visibility = View.GONE
                    layoutLoading.visibility = View.VISIBLE
                } else {
                    // Show AI message
                    layoutLoading.visibility = View.GONE
                    layoutAiMessage.visibility = View.VISIBLE
                    tvAiMessage.text = message.text

                    // Show sources if available
                    if (message.sourcesCount > 0) {
                        tvSources.visibility = View.VISIBLE
                        tvSources.text = "📚 ${message.sourcesCount} sources used"
                    } else {
                        tvSources.visibility = View.GONE
                    }
                }
            } else {
                // User message
                layoutAiMessage.visibility = View.GONE
                layoutLoading.visibility = View.GONE
                tvUserMessage.visibility = View.VISIBLE
                tvUserMessage.text = message.text
            }
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Chat message data class.
 */
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isAi: Boolean,
    val isLoading: Boolean = false,
    val sourcesCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
