package com.streamify.app.ui.fragments

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.databinding.FragmentNetworkStreamHeadersBinding

class NetworkStreamHeadersFragment : Fragment() {

    private var _binding: FragmentNetworkStreamHeadersBinding? = null
    private val binding get() = _binding!!
    private val safeBinding get() = _binding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkStreamHeadersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.headerCookieLayout.setEndIconOnClickListener { pasteInto(binding.headerCookieInput) }
        binding.headerRefererLayout.setEndIconOnClickListener { pasteInto(binding.headerRefererInput) }
        binding.headerOriginLayout.setEndIconOnClickListener { pasteInto(binding.headerOriginInput) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    fun getCookie(): String = safeBinding?.headerCookieInput?.text?.toString()?.trim().orEmpty()
    fun getReferer(): String = safeBinding?.headerRefererInput?.text?.toString()?.trim().orEmpty()
    fun getOrigin(): String = safeBinding?.headerOriginInput?.text?.toString()?.trim().orEmpty()

    private fun pasteInto(target: com.google.android.material.textfield.TextInputEditText) {
        val b = safeBinding ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Snackbar.make(b.root, R.string.network_stream_paste_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(requireContext())?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Snackbar.make(b.root, R.string.network_stream_paste_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        target.setText(text)
        target.setSelection(text.length)
    }
}
