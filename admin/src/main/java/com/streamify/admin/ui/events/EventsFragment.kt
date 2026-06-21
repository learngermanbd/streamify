package com.streamify.admin.ui.events

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamify.admin.R
import com.streamify.admin.data.AdminApi
import com.streamify.admin.data.EventItem
import com.streamify.admin.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

class EventsFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var listContainer: LinearLayout
    private var events: List<EventItem> = emptyList()

    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }
            addView(container)

            // Create button
            val btn = Button(context).apply {
                text = "+ New Event"
                setBackgroundColor(0xFFF59E0B.toInt())
                setTextColor(0xFF131931.toInt())
                setOnClickListener { showEventDialog(null) }
            }
            container.addView(btn)

            // Progress
            progress = ProgressBar(context).apply {
                visibility = View.GONE
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt())
            }
            container.addView(progress)

            // Status filter
            val filterRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 8)
            }
            listOf("All" to null, "Live" to "LIVE", "Scheduled" to "SCHEDULED", "Ended" to "ENDED").forEach { (label, value) ->
                filterRow.addView(Button(context).apply {
                    text = label
                    setOnClickListener { loadEvents(value) }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            container.addView(filterRow)

            listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            container.addView(listContainer)

            loadEvents(null)
        }
    }

    private fun loadEvents(status: String?) {
        progress.visibility = View.VISIBLE
        listContainer.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (val r = api.getEvents(status)) {
                    is AdminApi.ApiResult.Success -> {
                        events = r.data.filterIsInstance<EventItem>()
                        renderList()
                    }
                    is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
                }
                progress.visibility = View.GONE
            }
        }
    }

    private fun renderList() {
        if (events.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = "No events found"
                setPadding(8, 24, 8, 8)
            })
            return
        }
        events.forEach { event ->
            val card = TextView(requireContext()).apply {
                text = "${event.title}\n${event.teamAName} vs ${event.teamBName} · ${event.status}"
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0x1412121a.toInt())
                setTextColor(0xFFE0E6ED.toInt())
                textSize = 13f
                setOnClickListener { showEventDialog(event) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete Event")
                        .setMessage("Delete \"${event.title}\"?")
                        .setPositiveButton("Delete") { _, _ -> deleteEvent(event.id) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            listContainer.addView(card)
        }
    }

    private fun showEventDialog(event: EventItem?) {
        val fields = mutableMapOf<String, EditText>()
        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        listOf(
            "title" to (event?.title ?: ""),
            "teamAName" to (event?.teamAName ?: ""),
            "teamBName" to (event?.teamBName ?: ""),
            "date" to (event?.date ?: ""),
            "time" to (event?.time ?: "")
        ).forEach { (key, value) ->
            dialogView.addView(TextView(requireContext()).apply { text = key; textSize = 11f })
            val et = EditText(requireContext()).apply { setText(value); setTextColor(0xFFE0E6ED.toInt()) }
            dialogView.addView(et)
            fields[key] = et
        }

        // Status spinner. Build the ArrayAdapter as a typed local so we
        // don't have to cast `statusSpinner.adapter` later (avoids the
        // `Unchecked cast: SpinnerAdapter! -> ArrayAdapter<String>` warning
        // kotlinc emits when `adapter` is read through the Spinner property).
        val statusOptions = listOf("DRAFT", "SCHEDULED", "LIVE", "ENDED")
        val statusAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            statusOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // post-v1.1.0 review fix — coerce to >= 0 so an unknown backend
        // status (e.g. `CANCELLED`) still selects the first item instead
        // of silently deselecting (setSelection(-1) is a no-op, but it
        // also drops the highlighted affordance).
        val statusSpinner = Spinner(requireContext()).apply {
            adapter = statusAdapter
            setSelection(statusOptions.indexOf(event?.status ?: "DRAFT").coerceAtLeast(0))
        }
        dialogView.addView(statusSpinner)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (event != null) "Edit Event" else "New Event")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val json = JSONObject().apply {
                    fields.forEach { (k, v) -> put(k, v.text.toString()) }
                    put("status", statusSpinner.selectedItem.toString())
                    if (event != null) put("categoryId", event.categoryId)
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val r = if (event != null) api.updateEvent(event.id, json)
                        else api.createEvent(json)
                        when (r) {
                            is AdminApi.ApiResult.Success -> loadEvents(null)
                            is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEvent(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (val result = api.deleteEvent(id)) {
                    is AdminApi.ApiResult.Success -> loadEvents(null)
                    is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
                }
            }
        }
    }
}
