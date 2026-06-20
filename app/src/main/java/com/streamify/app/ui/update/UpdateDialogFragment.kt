package com.streamify.app.ui.update

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.databinding.DialogUpdateContentBinding
import kotlinx.coroutines.launch

/**
 * Phase 6 · Step 6.2 — Update UX sheet.
 *
 * One fragment, two modes:
 *  - **Forced** — drag-to-dismiss is disabled, "Quit" replaces "Later",
 *    `canceledOnTouchOutside` is false. Hosted by
 *    [UpdateActivity] on splash gate.
 *  - **Optional** — drag-to-dismiss works, "Later" persists the
 *    dismissed-version to [com.streamify.app.data.prefs.UpdatePrefs] so
 *    the same nag doesn't appear twice. Hosted by
 *    [com.streamify.app.ui.activities.MainActivity] from the
 *    drawer's Update entry.
 *
 * The bottom-sheet state is locked to EXPANDED so that on small screens
 * the entire body is visible without scrolling.
 */
class UpdateDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogUpdateContentBinding? = null
    private val binding get() = _binding!!

    private val isForced: Boolean
        get() = arguments?.getBoolean(ARG_IS_FORCED) ?: false

    private val latestVersion: String
        get() = arguments?.getString(ARG_LATEST).orEmpty()

    private val minVersion: String
        get() = arguments?.getString(ARG_MIN).orEmpty()

    private val apkUrl: String
        get() = arguments?.getString(ARG_URL).orEmpty()

    private val changelog: String
        get() = arguments?.getString(ARG_CHANGELOG).orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { d ->
            (d as BottomSheetDialog).behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = !isForced
                skipCollapsed = true
            }
        }
        dialog.setCanceledOnTouchOutside(!isForced)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUpdateContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.updateForcedLabel.visibility =
            if (isForced) View.VISIBLE else View.GONE
        binding.updateQuitButton.visibility =
            if (isForced) View.VISIBLE else View.GONE
        binding.updateLaterButton.visibility =
            if (isForced) View.GONE else View.VISIBLE
        binding.updateLaterDivider.visibility =
            if (isForced) View.GONE else View.VISIBLE

        binding.updateTitle.text = if (isForced) {
            getString(R.string.update_forced_title, minVersion)
        } else {
            getString(R.string.update_optional_title, latestVersion)
        }
        binding.updateBody.text = if (isForced) {
            getString(R.string.update_forced_body)
        } else {
            changelog.ifBlank { getString(R.string.update_optional_default_changelog) }
        }
        binding.updateNowButton.setOnClickListener { startUpdateDownload() }
        binding.updateQuitButton.setOnClickListener {
            requireActivity().finishAffinity()
        }
        binding.updateLaterButton.setOnClickListener { onOptionalLater() }
    }

    override fun onCancel(dialog: DialogInterface) {
        if (isForced) {
            // Forced sheet cannot be cancelled by external gestures.
            requireActivity().finishAffinity()
        } else {
            super.onCancel(dialog)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun startUpdateDownload() {
        val app = requireActivity().application as StreamifyApp
        // We pass `apkUrl` directly to startDownload rather than going
        // through an UpdateDecision intermediate — the `decision` variable
        // is empty (the `decision.apkUrl` accessor would fail because
        // Kotlin's if-else widens to the sealed parent UpdateDecision
        // which has no `apkUrl` field).
        val id = app.updateManager.startDownload(apkUrl)
        if (id == -1L) {
            Toast.makeText(
                requireContext(),
                R.string.update_download_failed,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            requireContext(),
            R.string.update_downloading,
            Toast.LENGTH_SHORT
        ).show()
        if (!isForced) dismiss()
    }

    private fun onOptionalLater() {
        val app = requireActivity().application as StreamifyApp
        if (latestVersion.isBlank()) {
            dismiss()
            return
        }
        // Phase 6 · Step 6.2 review-pass MAJOR: persist BEFORE dismiss
        // so a mid-launch activity tear-down (rotation, recent-task
        // swipe) doesn't cancel the DataStore write before it commits.
        // We await the write inside the same lifecycle scope so the
        // dialog only goes away once the dismissal is durable.
        viewLifecycleOwner.lifecycleScope.launch {
            app.updatePrefs.dismissOptional(latestVersion)
            dismiss()
        }
    }

    companion object {
        private const val ARG_IS_FORCED = "is_forced"
        private const val ARG_LATEST = "latest"
        private const val ARG_MIN = "min"
        private const val ARG_URL = "url"
        private const val ARG_CHANGELOG = "changelog"

        fun newForced(min: String, apkUrl: String): UpdateDialogFragment =
            UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_FORCED, true)
                    putString(ARG_MIN, min)
                    putString(ARG_URL, apkUrl)
                }
            }

        fun newOptional(
            latest: String,
            apkUrl: String,
            changelog: String = ""
        ): UpdateDialogFragment = UpdateDialogFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_IS_FORCED, false)
                putString(ARG_LATEST, latest)
                putString(ARG_URL, apkUrl)
                putString(ARG_CHANGELOG, changelog)
            }
        }

        /** Tag used by both SplashActivity (forced gate) + MainActivity (drawer). */
        const val TAG = "UpdateDialogFragment"
    }
}

// Small extension so Splash + MainActivity both have a single call site.
fun FragmentManager.showUpdateDialog(fragment: UpdateDialogFragment, tag: String = UpdateDialogFragment.TAG) {
    // beginTransaction now; commit would defer — we want the dialog on-screen
    // by the next frame so the forced gate doesn't flash.
    beginTransaction().add(fragment, tag).commitNowAllowingStateLoss()
}
