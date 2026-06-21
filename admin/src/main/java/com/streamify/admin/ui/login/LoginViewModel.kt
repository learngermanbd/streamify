package com.streamify.admin.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.admin.StreamifyAdminApp
import com.streamify.admin.data.AdminApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 8 · Step 8.14 — Login state machine with proper token persistence.
 *
 * post-v1.1.0 fix — Converted from `MutableLiveData` to `MutableStateFlow`
 * so the Activity can collect via `repeatOnLifecycle(STARTED)`. StateFlow
 * always replays its current `.value` to a new collector, which matches
 * the previous LiveData behaviour perfectly.
 *
 * On `Success` we now write the session to the DataStore-backed
 * [StreamifyAdminApp.saveSession] helper so a cold restart auto-logs in
 * instead of forcing the admin back to the form on every launch.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Success(val token: String, val refreshToken: String, val name: String, val role: String) : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private val api: AdminApi by lazy {
        val app = getApplication<StreamifyAdminApp>()
        AdminApi(
            baseUrl = StreamifyAdminApp.ADMIN_API_BASE_URL,
            httpClient = app.httpClient,
            tokenProvider = { app.adminToken }
        )
    }

    /**
     * post-v1.1.0 fix — Email format validation. The server is the
     * source of truth for which credentials it accepts; this is just a
     * friendly first-pass filter so a typo'd email gets a clearer
     * message than "Server returned HTTP 400".
     */
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun login(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            _state.value = LoginState.Error("Email and password are required")
            return
        }
        if (!EMAIL_REGEX.matches(email)) {
            _state.value = LoginState.Error("Enter a valid email address")
            return
        }

        _state.value = LoginState.Loading
        viewModelScope.launch {
            when (val result = api.login(email, password)) {
                is AdminApi.ApiResult.Success -> {
                    val r = result.data
                    if (r.token.isEmpty()) {
                        _state.value = LoginState.Error("Server returned no token")
                    } else {
                        // post-v1.1.0 — persist to DataStore so the next cold
                        // launch can auto-login. saveSession updates the
                        // in-memory fields synchronously first, then writes
                        // the DataStore asynchronously; the immediately-
                        // following `startActivity` to DashboardActivity sees
                        // the new token through the in-memory path.
                        val app = getApplication<StreamifyAdminApp>()
                        val session = StreamifyAdminApp.AdminSession(
                            accessToken   = r.token,
                            refreshToken  = r.refreshToken,
                            name          = r.user?.name ?: email.substringBefore("@"),
                            role          = r.user?.role ?: "EDITOR"
                        )
                        app.saveSession(session)
                        _state.value = LoginState.Success(
                            token        = session.accessToken,
                            refreshToken = session.refreshToken,
                            name         = session.name,
                            role         = session.role
                        )
                    }
                }
                is AdminApi.ApiResult.Failure ->
                    _state.value = LoginState.Error(result.message)
            }
        }
    }
}
