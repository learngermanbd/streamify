package com.streamify.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.prefs.ThemePrefs
import com.streamify.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

/**
 * Phase 3 \u00b7 Step 3.2 \u2192 Phase 6 \u00b7 Step 6.4 \u2014 Settings Fragment.
 *
 * Hosts the THEME picker (System / Light / Dark) wired to
 * [com.streamify.app.data.prefs.ThemePrefs]. Future settings rows
 * (Playback defaults, Notification preferences, etc.) append below the
 * chip group via the themeHelpText chain anchor.
 *
 * Theme-apply protocol (thinker-gemini Step 6.4 review point #3):
 *   1. chipClick \u2192 [ThemePrefs.setThemeMode]   [DataStore edit]
 *   2. [AppCompatDelegate.setDefaultNightMode] \u2014 the framework
 *      auto-recreates every started Activity. We DO NOT call
 * [android.app.Activity.recreate] manually; that would
 *      double-recreate and produce visible flicker.
 *   3. The persisted value is read at next cold start by
 *      [com.streamify.app.StreamifyApp.onCreate] as the
 *      boot-time ground-truth, so the choice survives process death.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as StreamifyApp

        // Reflect the persisted mode ONCE on view-create (DataStore
        // .first) into the chip group's check state. Programmatic
        // isChecked setters do NOT fire onClickListener so we can
        // safely initialise state without triggering another
        // setThemeMode write.
        viewLifecycleOwner.lifecycleScope.launch {
            val current = app.themePrefs.currentMode()
            binding.themeSystemChip.isChecked = current == ThemePrefs.ThemeMode.SYSTEM
            binding.themeLightChip.isChecked = current == ThemePrefs.ThemeMode.LIGHT
            binding.themeDarkChip.isChecked = current == ThemePrefs.ThemeMode.DARK
        }

        binding.themeSystemChip.setOnClickListener { onChipTapped(app, ThemePrefs.ThemeMode.SYSTEM) }
        binding.themeLightChip.setOnClickListener  { onChipTapped(app, ThemePrefs.ThemeMode.LIGHT) }
        binding.themeDarkChip.setOnClickListener   { onChipTapped(app, ThemePrefs.ThemeMode.DARK) }

        // Footer \u2014 reflect packageManager versionName into the trailing
        // version text. Resilient against hand-uninstalled test builds.
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName ?: "1.0.0"
            binding.versionText.text = getString(
                R.string.fragment_settings_version_template, versionName
            )
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            binding.versionText.visibility = View.GONE
        }
    }

    /**
     * Persist the chip selection + propagate
     * [AppCompatDelegate.setDefaultNightMode]. Activity recreation is
     * the framework's responsibility \u2014 it's intentionally NOT called
     * here to avoid the double-recreate flicker (thinker-gemini Step
     * 6.4 review point #3).
     */
    private fun onChipTapped(app: StreamifyApp, mode: ThemePrefs.ThemeMode) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.themePrefs.setThemeMode(mode)
            AppCompatDelegate.setDefaultNightMode(app.themePrefs.toNightModeFlag(mode))
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
