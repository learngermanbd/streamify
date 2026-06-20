package com.streamify.admin.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamify.admin.data.AdminApi
import com.streamify.admin.data.CategoryItem
import com.streamify.admin.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

class CategoriesFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            }
            addView(container)
            container.addView(Button(context).apply {
                text = "+ New Category"
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
            when (val r = api.getCategories()) {
                is AdminApi.ApiResult.Success -> {
                    val items = r.data.filterIsInstance<CategoryItem>()
                    if (items.isEmpty()) listContainer.addView(TextView(requireContext()).apply {
                        text = "No categories"; setPadding(8, 24, 8, 8)
                    })
                    items.forEach { cat ->
                        listContainer.addView(TextView(requireContext()).apply {
                            text = "${cat.name} · ${if (cat.isVisible) "Visible" else "Hidden"} · Sort: ${cat.sortOrder}"
                            setPadding(12, 12, 12, 12); setBackgroundColor(0x1412121a.toInt())
                            setTextColor(0xFFE0E6ED.toInt()); textSize = 13f
                            setOnClickListener { showDialog(cat) }
                            setOnLongClickListener {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Delete Category").setMessage("Delete \"${cat.name}\"?")
                                    .setPositiveButton("Delete") { _, _ -> deleteItem(cat.id) }
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

    private fun showDialog(cat: CategoryItem?) {
        val fields = mutableMapOf<String, EditText>()
        val view = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        listOf("name" to (cat?.name ?: ""), "iconUrl" to (cat?.iconUrl ?: "")).forEach { (k, v) ->
            view.addView(TextView(requireContext()).apply { text = k; textSize = 11f })
            val et = EditText(requireContext()).apply { setText(v); setTextColor(0xFFE0E6ED.toInt()) }
            view.addView(et); fields[k] = et
        }
        view.addView(TextView(requireContext()).apply { text = "Visible"; textSize = 11f })
        val cb = CheckBox(requireContext()).apply { isChecked = cat?.isVisible ?: true }
        view.addView(cb)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (cat != null) "Edit Category" else "New Category")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val json = JSONObject().apply {
                    put("name", fields["name"]?.text.toString())
                    put("iconUrl", fields["iconUrl"]?.text.toString().ifEmpty { null })
                    put("isVisible", cb.isChecked)
                    put("sortOrder", cat?.sortOrder ?: 0)
                }
                lifecycleScope.launch {
                    val r = if (cat != null) api.updateCategory(cat.id, json) else api.createCategory(json)
                    when (r) {
                        is AdminApi.ApiResult.Success -> load()
                        is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteItem(id: String) {
        lifecycleScope.launch {
            when (val result = api.deleteCategory(id)) {
                is AdminApi.ApiResult.Success -> load()
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
            }
        }
    }
}
