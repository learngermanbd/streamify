package com.streamify.admin.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.streamify.admin.data.AdminApi
import com.streamify.admin.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

class ConfigFragment : Fragment() {

    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var maintCb: CheckBox
    private var featureFlags: JSONObject = JSONObject()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            }
            addView(container)

            listOf("apiBaseUrl", "updateUrl", "telegramLink", "noticeText", "minAppVersion").forEach { key ->
                container.addView(TextView(requireContext()).apply { text = key; textSize = 11f })
                val et = EditText(requireContext()).apply { setTextColor(0xFFE0E6ED.toInt()) }
                container.addView(et); fields[key] = et
            }
            container.addView(TextView(requireContext()).apply { text = "Maintenance Mode"; textSize = 11f })
            maintCb = CheckBox(requireContext()).apply { container.addView(this) }

            container.addView(Button(context).apply {
                text = "Save Config"; setBackgroundColor(0xFFF59E0B.toInt()); setTextColor(0xFF131931.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
                setOnClickListener { save() }
            })

            load()
        }
    }

    private fun load() {
        lifecycleScope.launch {
            when (val r = api.getConfig()) {
                is AdminApi.ApiResult.Success -> {
                    val c = r.data
                    fields["apiBaseUrl"]?.setText(c.optString("apiBaseUrl", ""))
                    fields["updateUrl"]?.setText(c.optString("updateUrl", ""))
                    fields["telegramLink"]?.setText(c.optString("telegramLink", ""))
                    fields["noticeText"]?.setText(c.optString("noticeText", ""))
                    fields["minAppVersion"]?.setText(c.optString("minAppVersion", ""))
                    maintCb.isChecked = c.optBoolean("maintenanceMode", false)
                    featureFlags = c.optJSONObject("featureFlags") ?: JSONObject()
                }
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(r.message)
            }
        }
    }

    private fun save() {
        val json = JSONObject().apply {
            fields.forEach { (k, v) -> put(k, v.text.toString()) }
            put("maintenanceMode", maintCb.isChecked)
            put("featureFlags", featureFlags)
        }
        lifecycleScope.launch {
            when (val result = api.updateConfig(json)) {
                is AdminApi.ApiResult.Success -> (requireActivity() as DashboardActivity).toast("Config saved")
                is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
            }
        }
    }
}
