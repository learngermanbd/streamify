package com.streamify.admin.ui.notifications

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

class NotificationsFragment : Fragment() {

    private val api: AdminApi get() = (requireActivity() as DashboardActivity).api

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return ScrollView(requireContext()).apply {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            }
            addView(container)

            container.addView(TextView(context).apply {
                text = "Send Push Notification"; textSize = 16f; setTextColor(0xFFE0E6ED.toInt())
                setPadding(0, 0, 0, 12)
            })

            val fields = mutableMapOf<String, EditText>()
            listOf("Title" to "nlTitle", "Body" to "nlBody", "Target Topic" to "nlTopic",
                "Target Token" to "nlToken", "Deep Link" to "nlLink").forEach { (label, id) ->
                container.addView(TextView(context).apply { text = label; textSize = 11f })
                val et = EditText(context).apply { setTextColor(0xFFE0E6ED.toInt()) }
                container.addView(et); fields[id] = et
            }

            container.addView(Button(context).apply {
                text = "Send Notification"; setBackgroundColor(0xFFF59E0B.toInt()); setTextColor(0xFF131931.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
                setOnClickListener {
                    val json = JSONObject().apply {
                        put("title", fields["nlTitle"]?.text.toString())
                        put("body", fields["nlBody"]?.text.toString())
                        val topic = fields["nlTopic"]?.text.toString()
                        val token = fields["nlToken"]?.text.toString()
                        if (topic.isNotEmpty()) put("targetTopic", topic)
                        if (token.isNotEmpty()) put("targetToken", token)
                        val link = fields["nlLink"]?.text.toString()
                        if (link.isNotEmpty()) put("deepLink", link)
                    }
                    lifecycleScope.launch {
                        when (val result = api.sendNotification(json)) {
                            is AdminApi.ApiResult.Success -> (requireActivity() as DashboardActivity).toast("Notification sent!")
                            is AdminApi.ApiResult.Failure -> (requireActivity() as DashboardActivity).toast(result.message)
                        }
                    }
                }
            })
        }
    }
}
