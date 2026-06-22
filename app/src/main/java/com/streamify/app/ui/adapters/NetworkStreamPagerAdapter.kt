package com.streamify.app.ui.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.streamify.app.ui.fragments.NetworkStreamDrmFragment
import com.streamify.app.ui.fragments.NetworkStreamHeadersFragment
import com.streamify.app.ui.fragments.NetworkStreamUrlFragment

/**
 * ViewPager2 adapter for the Network Player's 3-tab layout:
 * Tab 0: Stream URL (input + format chips + recent URLs)
 * Tab 1: Request Headers (Cookie, Referer, Origin)
 * Tab 2: DRM Decryption (License URL, Decryption, Scheme)
 */
class NetworkStreamPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> NetworkStreamUrlFragment()
        1 -> NetworkStreamHeadersFragment()
        2 -> NetworkStreamDrmFragment()
        else -> throw IllegalArgumentException("Invalid tab position: $position")
    }
}
