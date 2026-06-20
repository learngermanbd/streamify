package com.streamify.admin.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.streamify.admin.StreamifyAdminApp
import com.streamify.admin.data.AdminApi
import kotlinx.coroutines.launch

/**
 * Phase 8 · Step 8.14 — Login state machine with proper token persistence.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Success(val token: String, val refreshToken: String, val name: String, val role: String) : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _state = MutableLiveData<LoginState>(LoginState.Idle)
    val state: LiveData<LoginState> = _state

    private val api: AdminApi by lazy {
        val app = getApplication<StreamifyAdminApp>()
        AdminApi(
            baseUrl = StreamifyAdminApp.ADMIN_API_BASE_URL,
            httpClient = app.httpClient,
            tokenProvider = { app.adminToken }
        )
    }

    fun login(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            _state.value = LoginState.Error("Email and password are required")
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
                        // Persist token in Application
                        val app = getApplication<StreamifyAdminApp>()
                        app.adminToken = r.token
                        app.adminRefreshToken = r.refreshToken
                        app.adminName = r.user?.name ?: email.split("@")[0]
                        app.adminRole = r.user?.role ?: "EDITOR"
                        _state.value = LoginState.Success(
                            token = r.token,
                            refreshToken = r.refreshToken,
                            name = app.adminName,
                            role = app.adminRole
                        )
                    }
                }
                is AdminApi.ApiResult.Failure ->
                    _state.value = LoginState.Error(result.message)
            }
        }
    }
}
