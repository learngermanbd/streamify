package com.streamify.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.streamify.app.R
import com.streamify.app.databinding.FragmentNetworkStreamBinding
import com.streamify.app.ui.adapters.NetworkStreamPagerAdapter
import com.streamify.app.ui.util.PlayerNavigation

/**
 * Network Player — Tabbed layout (Variant A).
 *
 * 3 tabs: Stream URL / Request Headers / DRM Decryption.
 * The floating play button (FAB) validates the URL from Tab 0
 * and launches PlayerActivity with the entered URL.
 */
class NetworkStreamFragment : Fragment() {

    private var _binding: FragmentNetworkStreamBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pagerAdapter = NetworkStreamPagerAdapter(requireActivity())
        binding.networkStreamPager.adapter = pagerAdapter
        binding.networkStreamPager.offscreenPageLimit = 2

        TabLayoutMediator(binding.networkStreamTabs, binding.networkStreamPager) { tab, position ->
            tab.text = when (position) {
                0 -> "🗲 Stream URL"
                1 -> "🌐 Headers"
                2 -> "🔐 DRM"
                else -> ""
            }
        }.attach()

        binding.networkStreamFab.setOnClickListener { onPlayClicked() }
    }

    override fun onDestroyView() {
        binding.networkStreamPager.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun onPlayClicked() {
        // Find the URL fragment from the ViewPager2's adapter fragments.
        // ViewPager2 uses "f0", "f1", "f2" as fragment tags by default.
        val urlFragment = childFragmentManager
            .findFragmentByTag("f${binding.networkStreamPager.currentItem}")
            as? NetworkStreamUrlFragment

        val typed = urlFragment?.getUrl().orEmpty()

        if (typed.isBlank()) {
            Snackbar.make(binding.root, R.string.network_stream_url_required, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!(typed.startsWith("http://") || typed.startsWith("https://"))) {
            Snackbar.make(binding.root, R.string.network_stream_url_invalid_scheme, Snackbar.LENGTH_SHORT).show()
            return
        }

        val formatLabel = urlFragment?.selectedFormatLabel().orEmpty()

        PlayerNavigation.startPlayerForVideo(
            context = requireContext(),
            videoUrl = typed,
            title = getString(R.string.network_stream_default_title)
        )
    }
}
