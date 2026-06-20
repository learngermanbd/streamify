package com.streamify.admin.ui.channels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamify.admin.data.AdminApi
import com.streamify.admin.data.ChannelItem
import com.streamify.admin.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChannelsFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var listContainer: LinearLayout
    private var channels: List<ChannelItem> = emptyList()
    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }
            addView(container)

            container.addView(Button(context).apply {
                text = "+ New Channel"
                setBackgroundColor(0xFFF59E0B.toInt())
                setTextColor(0xFF131931.toInt())
                setOnClickListener { showDialog(null) }
            })

            listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            container.addView(listContainer)
            loadChannels()
        }
    }

    private fun loadChannels() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            when (val r = api.getChannels()) {
                is AdminApi.ApiResult.Success -> {
                    channels = r.data.filterIsInstance<ChannelItem>()
                    if (channels.isEmpty()) listContainer.addView(TextView(requireContext()).apply {
                        text = "No channels"; setPadding(8, 24, 8, 8)
                    })
                    channels.forEach { ch ->
                        listContainer.addView(TextView(requireContext()).apply {
                            text = "${ch.name} · ${ch.categoryName} · ${if (ch.isActive) "Active" else "Inactive"}"
                            setPadding(12, 12, 12, 12); setBackgroundColor(0x1412121a.toInt())
                            setTextColor(0xFFE0E6ED.toInt()); textSize = 13f
                            setOnClickListener { showDialog(ch) }
                            setOnLongClickListener {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Delete Channel").setMessage("Delete \"${ch.name}\"?")
                                    .setPositiveButton("Delete") { _, _ -> delete(ch.id) }
                                    .setNegativeButton("Cancel", null).show()
                                true
                            }
                        })
                    }
                }
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
            }
        }
    }

    private fun showDialog(ch: ChannelItem?) {
        val fields = mutableMapOf<String, EditText>()
        val view = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        listOf("name" to (ch?.name ?: ""), "streamUrl" to (ch?.streamUrl ?: ""), "logoUrl" to (ch?.logoUrl ?: "")).forEach { (k, v) ->
            view.addView(TextView(requireContext()).apply { text = k; textSize = 11f })
            val et = EditText(requireContext()).apply { setText(v); setTextColor(0xFFE0E6ED.toInt()) }
            view.addView(et); fields[k] = et
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (ch != null) "Edit Channel" else "New Channel")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val json = JSONObject().apply {
                    fields.forEach { (k, v) -> put(k, v.text.toString()) }
                    if (ch != null) put("categoryId", ch.categoryId)
                }
                lifecycleScope.launch {
                    val r = if (ch != null) api.updateChannel(ch.id, json) else api.createChannel(json)
                    when (r) {
                        is AdminApi.ApiResult.Success -> loadChannels()
                        is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun delete(id: String) {
        lifecycleScope.launch {
            when (val result = api.deleteChannel(id)) {
                is AdminApi.ApiResult.Success -> loadChannels()
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
            }
        }
    }
}
