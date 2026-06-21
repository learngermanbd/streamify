package com.streamify.admin.ui.login

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.streamify.admin.StreamifyAdminApp
import com.streamify.admin.databinding.ActivityLoginBinding
import com.streamify.admin.ui.dashboard.DashboardActivity
import com.streamify.admin.ui.login.LoginViewModel.LoginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Phase 8 \u00b7 Step 8.13 \u2014 Login screen.
 *
 * Hosts the email + password form and delegates the actual auth flow to
 * [LoginViewModel]. On success we jump to [DashboardActivity] and `finish()`
 * the login so back-press doesn't return here. On failure the VM surfaces
 * a [Snackbar] message.
 *
 * post-v1.1.0 fix \u2014 Auto-login: if [StreamifyAdminApp.loadSession] finds
 * a persisted token we skip the form, dispatch straight to Dashboard.
 * Also migrated from `LiveData.observe(this)` to the more robust
 * `repeatOnLifecycle(STARTED) { state.collect { ... } }` pattern, which
 * guarantees we never fire navigation while the activity is STOPPED
 * (e.g. user rotates mid-login).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            try {
                viewModel.login(email, password)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t   // never swallow coroutine cancellation
                // Catch synchronous throws from `viewModel.login()` (e.g. lazy
                // AdminApi init failure) so the next tap doesn't kill the
                // process — surface the class + message in a Snackbar AND log
                // the full stack trace under the stable [StreamifyAdminApp.TAG]
                // for easy retrieval via `adb logcat -s StreamifyAdminApp.Crash`.
                Log.e(StreamifyAdminApp.TAG, "Synchronous failure on login click", t)
                Snackbar.make(
                    binding.root,
                    "${t.javaClass.simpleName}: ${t.message ?: "<no message>"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        // post-v1.1.0 — observe via repeatOnLifecycle so navigation never
        // fires while the activity is STOPPED (preventing the
        // "startActivity from non-foreground" IllegalStateException on
        // rotation mid-login).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }

        // post-v1.1.0 — auto-login when a persisted session already exists.
        // Runs while STARTED so process death + recreation still hits this path.
        if (savedInstanceState == null) launchAutoLogin()
    }

    private fun launchAutoLogin() {
        val app = applicationContext as StreamifyAdminApp
        // post-v1.1.0 race fix — DashboardActivity.logout() wipes the in-memory
        // quartet synchronously before startActivity. By the time this gate
        // runs, app.adminToken is already blank for the just-logged-out
        // window, so we short-circuit to avoid a stale-session auto-login.
        if (app.adminToken.isBlank()) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val session = app.loadSession()
                if (session != null && session.accessToken.isNotBlank()) {
                    // Already logged in — dispatch straight to Dashboard and
                    // back out of LoginActivity so back-press returns home,
                    // not here.
                    startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun render(state: LoginState) {
        binding.progress.visibility = if (state is LoginState.Loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = state !is LoginState.Loading

        when (state) {
            is LoginState.Success -> {
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
            }
            is LoginState.Error -> {
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
            else -> Unit
        }
    }
}
