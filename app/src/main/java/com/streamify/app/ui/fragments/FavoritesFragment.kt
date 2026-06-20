package com.streamify.app.ui.fragments

import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.StreamifyApp
import com.streamify.app.data.local.FavoriteEntity
import com.streamify.app.databinding.FragmentFavoritesBinding
import com.streamify.app.ui.adapters.FavoriteAdapter
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.util.PlayerNavigation
import com.streamify.app.ui.viewmodels.FavoritesViewModel
import kotlinx.coroutines.launch

/**
 * Phase 5 · Step 5.1 — Favorites screen.
 *
 * Reads [FavoriteEntity] rows from the Room-backed [FavoritesRepository]
 * Flow via [FavoritesViewModel]; renders them with a [FavoriteAdapter];
 * supports:
 *
 *  - **Tap row** → [PlayerNavigation.startPlayerForChannel]
 *  - **Tap heart** → un-favorite via [FavoritesViewModel.remove] (the
 *    row vanishes from the list because the underlying Flow re-fires).
 *  - **Swipe row** → un-favorite via ItemTouchHelper; an undo Snackbar
 *    restores the entity via [FavoritesViewModel.restore].
 *
 * Lifecycle: collects [FavoritesViewModel.state] inside
 * [repeatOnLifecycle(STARTED)] so the empty-state + loading indicator
 * flip correctly across rotation and tab-switching without leaks.
 */
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    /**
     * Fragment-scoped VM via the standard `by viewModels { ... }` Kotlin
     * DSL — the same pattern `androidx.fragment.app.viewModels` ships
     * with Fragment 1.5+. The factory pulls [FavoritesViewModel] from
     * the application's already-wired repository module, no extra glue
     * needed. Replaces the prior custom `lazilyResolveViewModel()`
     * extension so the pattern is consistent with the rest of the app.
     */
    private val favoritesVm: FavoritesViewModel by viewModels {
        val app = requireActivity().application as StreamifyApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                FavoritesViewModel(app.repository.favoritesRepository) as T
        }
    }

    private lateinit var adapter: FavoriteAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = FavoriteAdapter(
            onClick = { entity ->
                PlayerNavigation.startPlayerForChannel(
                    context = requireContext(),
                    channelId = entity.channelId,
                    fallbackTitle = entity.name
                )
            },
            onFavorite = { entity -> favoritesVm.remove(entity.channelId) }
        )
        binding.favoritesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.favoritesRecyclerView.adapter = adapter

        attachSwipeToDelete(binding.favoritesRecyclerView)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoritesVm.state.collect(::renderState)
            }
        }
        favoritesVm.refresh()
    }

    private fun renderState(state: UiState<List<FavoriteEntity>>) {
        when (state) {
            is UiState.Loading -> {
                binding.favoritesLoadingIndicator.isVisible = true
                // Reset stale Error/Success copy on retry so a previous
                // swipe-undo failure doesn't linger under the spinner.
                binding.favoritesEmptyText.text = ""
                binding.favoritesEmptyText.isVisible = false
                binding.favoritesRecyclerView.isVisible = false
            }
            is UiState.Success -> {
                binding.favoritesLoadingIndicator.isVisible = false
                binding.favoritesEmptyText.text = ""
                adapter.submitList(state.value)
                // Phase 6 · Step 6.3 — staggered fall-down cascade on
                // every fresh favorites list (initial load + swipe-undo
                // restore). Empty-state guarded so the empty card stays
                // static when a swipe-undo returns the last removed row
                // (in which case the cascade plays once more thereafter).
                if (state.value.isNotEmpty()) binding.favoritesRecyclerView.scheduleLayoutAnimation()
                val empty = state.value.isEmpty()
                binding.favoritesEmptyText.isVisible = empty
                binding.favoritesRecyclerView.isVisible = !empty
            }
            is UiState.Error -> {
                binding.favoritesLoadingIndicator.isVisible = false
                binding.favoritesEmptyText.text = state.message
                binding.favoritesEmptyText.isVisible = true
                binding.favoritesRecyclerView.isVisible = false
            }
            UiState.Idle -> Unit
        }
    }

    /**
     * Attach an ItemTouchHelper that un-favorites the row on
     * LEFT/RIGHT swipe and shows an undo Snackbar. The swipe
     * background is intentionally recreated per [attachSwipeToDelete]
     * invocation (i.e. per Fragment lifecycle), NOT per onChildDraw
     * call — the optimizer's worry that draw-hook access re-allocates
     * was unfounded: only the outer's `bg = ColorDrawable(...)` runs
     * once, while `onChildDraw` only mutates `.setBounds()` / `.draw()`
     * on the captured instance.
     *
     * Resolving fresh per Fragmenent creation tracks theme changes
     * (light/dark mode) correctly, since the cached drawable from the
     * previous theme would otherwise render the wrong hue.
     */
    private fun attachSwipeToDelete(recycler: RecyclerView) {
        val swipeBackground = ColorDrawable(
            ContextCompat.getColor(
                requireContext(),
                com.streamify.app.R.color.swipe_delete_background
            )
        )
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                src: RecyclerView.ViewHolder,
                dst: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val entity = adapter.currentList.getOrNull(pos) ?: return
                favoritesVm.remove(entity.channelId)
                Snackbar.make(
                    binding.root,
                    getString(com.streamify.app.R.string.favorites_removed_template, entity.name),
                    Snackbar.LENGTH_LONG
                ).setAction(
                    getString(com.streamify.app.R.string.favorites_action_undo)
                ) { favoritesVm.restore(entity) }.show()
            }

            override fun onChildDraw(
                c: Canvas,
                recycler: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                swipeBackground.setBounds(
                    itemView.left,
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                swipeBackground.draw(c)
                super.onChildDraw(c, recycler, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    override fun onDestroyView() {
        binding.favoritesRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
