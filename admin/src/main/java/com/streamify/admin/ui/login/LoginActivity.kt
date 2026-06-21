package com.streamify.admin.ui.login

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.streamify.admin.StreamifyAdminApp
import com.streamify.admin.databinding.ActivityLoginBinding
import com.streamify.admin.ui.dashboard.DashboardActivity
import com.streamify.admin.ui.login.LoginViewModel.LoginState
import kotlinx.coroutines.CancellationException

/**
 * Phase 8 \u00b7 Step 8.13 \u2014 Login screen.
 *
 * Hosts the email + password form and delegates the actual auth flow to
 * [LoginViewModel]. On success we jump to [DashboardActivity] and `finish()`
 * the login so back-press doesn't return here. On failure the VM surfaces
 * a [Snackbar] message.
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

        viewModel.state.observe(this) { state ->
            render(state)
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
