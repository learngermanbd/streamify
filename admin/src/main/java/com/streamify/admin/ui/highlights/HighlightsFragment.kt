package com.streamify.admin.ui.highlights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamify.admin.data.AdminApi
import com.streamify.admin.data.HighlightItem
import com.streamify.admin.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

class HighlightsFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            }
            addView(container)

            container.addView(Button(context).apply {
                text = "+ New Highlight"
                setBackgroundColor(0xFFF59E0B.toInt()); setTextColor(0xFF131931.toInt())
                setOnClickListener { showDialog(null) }
            })

            listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            container.addView(listContainer)
            load()
        }
    }

    private fun load() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            when (val r = api.getHighlights()) {
                is AdminApi.ApiResult.Success -> {
                    val items = r.data.filterIsInstance<HighlightItem>()
                    if (items.isEmpty()) listContainer.addView(TextView(requireContext()).apply {
                        text = "No highlights"; setPadding(8, 24, 8, 8)
                    })
                    items.forEach { h ->
                        val min = h.duration / 60; val sec = (h.duration % 60).toString().padStart(2, '0')
                        listContainer.addView(TextView(requireContext()).apply {
                            text = "${h.title} · $min:$sec · ${h.views} views"
                            setPadding(12, 12, 12, 12); setBackgroundColor(0x1412121a.toInt())
                            setTextColor(0xFFE0E6ED.toInt()); textSize = 13f
                            setOnClickListener { showDialog(h) }
                            setOnLongClickListener {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Delete Highlight").setMessage("Delete \"${h.title}\"?")
                                    .setPositiveButton("Delete") { _, _ -> deleteItem(h.id) }
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

    private fun showDialog(h: HighlightItem?) {
        val fields = mutableMapOf<String, EditText>()
        val view = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        listOf("title" to (h?.title ?: ""), "thumbnailUrl" to (h?.thumbnailUrl ?: ""), "videoUrl" to (h?.videoUrl ?: ""),
            "duration" to (h?.duration?.toString() ?: "0"), "views" to (h?.views?.toString() ?: "0")).forEach { (k, v) ->
            view.addView(TextView(requireContext()).apply { text = k; textSize = 11f })
            val et = EditText(requireContext()).apply { setText(v); setTextColor(0xFFE0E6ED.toInt()) }
            view.addView(et); fields[k] = et
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (h != null) "Edit Highlight" else "New Highlight")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val json = JSONObject().apply {
                    put("title", fields["title"]?.text.toString())
                    put("thumbnailUrl", fields["thumbnailUrl"]?.text.toString())
                    put("videoUrl", fields["videoUrl"]?.text.toString())
                    put("duration", fields["duration"]?.text.toString().toIntOrNull() ?: 0)
                    put("views", fields["views"]?.text.toString().toIntOrNull() ?: 0)
                    put("date", System.currentTimeMillis())
                }
                lifecycleScope.launch {
                    val r = if (h != null) api.updateHighlight(h.id, json) else api.createHighlight(json)
                    when (r) {
                        is AdminApi.ApiResult.Success -> load()
                        is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteItem(id: String) {
        lifecycleScope.launch {
            when (val result = api.deleteHighlight(id)) {
                is AdminApi.ApiResult.Success -> load()
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
            }
        }
    }
}
