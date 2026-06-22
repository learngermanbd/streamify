package com.streamify.app.ui.fragments

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.databinding.FragmentNetworkStreamDrmBinding

class NetworkStreamDrmFragment : Fragment() {

    private var _binding: FragmentNetworkStreamDrmBinding? = null
    private val binding get() = _binding!!
    private val safeBinding get() = _binding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkStreamDrmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.drmLicenseUrlLayout.setEndIconOnClickListener { pasteInto(binding.drmLicenseUrlInput) }

        // Setup dropdowns
        val decryptionOptions = arrayOf("Default", "AES-128", "SAMPLE-AES")
        val schemeOptions = arrayOf("ClearKey", "Widevine", "PlayReady")

        binding.drmDecryptionDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, decryptionOptions))
        binding.drmSchemeDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, schemeOptions))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    fun getLicenseUrl(): String = safeBinding?.drmLicenseUrlInput?.text?.toString()?.trim().orEmpty()
    fun getDecryption(): String = safeBinding?.drmDecryptionDropdown?.text?.toString() ?: "Default"
    fun getScheme(): String = safeBinding?.drmSchemeDropdown?.text?.toString() ?: "ClearKey"

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
