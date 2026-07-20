package com.ai.assistance.operit.ui.features.settings.oauth

import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.ModelOAuthClient
import com.ai.assistance.operit.api.chat.llmprovider.ModelOAuthConfig
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.credentials.ModelOAuthTokenStore
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.features.token.webview.WebViewConfig
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.launch

private const val TAG = "ModelOAuthLoginWebView"

/**
 * Embedded-WebView OAuth 2.0 + PKCE login for a model config, mirroring
 * [com.ai.assistance.operit.ui.features.github.GitHubLoginWebViewDialog]'s embedded path but driven by
 * the provider-agnostic [ModelOAuthClient]. Loads [oauthConfig]'s authorize URL, captures the redirect
 * to [ModelOAuthConfig.redirectUri], verifies the state, extracts the code, and exchanges it for tokens
 * (persisted into [ModelOAuthTokenStore] keyed by [configId]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelOAuthLoginWebViewDialog(
    configId: String,
    oauthConfig: ModelOAuthConfig,
    onDismissRequest: () -> Unit,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val client = remember { ModelOAuthClient(ModelOAuthTokenStore(context)) }
    val authRequest = remember(oauthConfig) {
        client.buildAuthorizeRequest(oauthConfig, GitHubAuthPreferences.createOAuthState())
    }
    val webView = remember {
        WebViewConfig.createWebView(context).apply {
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var isCompleting by remember { mutableStateOf(false) }
    var hasHandledRedirect by remember { mutableStateOf(false) }

    fun reportFailure(message: String) {
        isCompleting = false
        isLoading = false
        AppLogger.e(TAG, message)
        onFailure(message)
    }

    DisposableEffect(webView) {
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    if (request?.isForMainFrame != false && handleModelOAuthRedirect(
                            uri = request?.url,
                            redirectUri = oauthConfig.redirectUri,
                            expectedState = authRequest.state,
                            shouldHandle = { !hasHandledRedirect },
                            onStartHandling = {
                                hasHandledRedirect = true
                                isLoading = true
                                isCompleting = true
                            },
                            onExtractCode = { code ->
                                scope.launch {
                                    client.exchangeCode(
                                        configId,
                                        oauthConfig,
                                        code,
                                        authRequest.codeVerifier
                                    ).fold(
                                        onSuccess = {
                                            onSuccess()
                                            onDismissRequest()
                                        },
                                        onFailure = { error ->
                                            reportFailure(error.message.orEmpty())
                                        }
                                    )
                                }
                            },
                            onCancelled = onDismissRequest,
                            onFailure = ::reportFailure
                        )) {
                        return true
                    }
                    return false
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    if (handleModelOAuthRedirect(
                            uri = url?.let(Uri::parse),
                            redirectUri = oauthConfig.redirectUri,
                            expectedState = authRequest.state,
                            shouldHandle = { !hasHandledRedirect },
                            onStartHandling = {
                                hasHandledRedirect = true
                                isLoading = true
                                isCompleting = true
                            },
                            onExtractCode = { code ->
                                scope.launch {
                                    client.exchangeCode(
                                        configId,
                                        oauthConfig,
                                        code,
                                        authRequest.codeVerifier
                                    ).fold(
                                        onSuccess = {
                                            onSuccess()
                                            onDismissRequest()
                                        },
                                        onFailure = { error ->
                                            reportFailure(error.message.orEmpty())
                                        }
                                    )
                                }
                            },
                            onCancelled = onDismissRequest,
                            onFailure = ::reportFailure
                        )) {
                        view?.stopLoading()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isCompleting) {
                        isLoading = false
                    }
                }
            }

        onDispose {
            releaseWebView(webView)
        }
    }

    LaunchedEffect(authRequest.authorizeUrl) {
        webView.loadUrl(authRequest.authorizeUrl)
    }

    Dialog(
        onDismissRequest = {
            if (!isCompleting) {
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CustomScaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.model_oauth_authorize)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismissRequest,
                                enabled = !isCompleting
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { webView.reload() },
                                enabled = !isCompleting
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading || isCompleting) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns true when [uri] is this config's OAuth redirect (matched by scheme + host of [redirectUri]),
 * meaning the WebView should consume it. Verifies [expectedState], extracts the code, and routes to the
 * caller's handlers. A false return lets the WebView keep navigating (login pages, consent screens).
 */
private fun handleModelOAuthRedirect(
    uri: Uri?,
    redirectUri: String,
    expectedState: String,
    shouldHandle: () -> Boolean,
    onStartHandling: () -> Unit,
    onExtractCode: (String) -> Unit,
    onCancelled: () -> Unit,
    onFailure: (String) -> Unit
): Boolean {
    if (uri == null) {
        return false
    }
    val expected = Uri.parse(redirectUri)
    val matches = uri.scheme.equals(expected.scheme, ignoreCase = true) &&
        uri.host.equals(expected.host, ignoreCase = true)
    if (!matches) {
        return false
    }

    if (!shouldHandle()) {
        return true
    }

    onStartHandling()

    val error = uri.getQueryParameter("error")
    if (error != null) {
        if (error == "access_denied") {
            onCancelled()
        } else {
            onFailure(error)
        }
        return true
    }

    val returnedState = uri.getQueryParameter("state")
    if (returnedState != expectedState) {
        onFailure("OAuth state mismatch")
        return true
    }

    val code = uri.getQueryParameter("code")
    if (code.isNullOrEmpty()) {
        onFailure("Missing authorization code")
        return true
    }

    onExtractCode(code)
    return true
}

private fun releaseWebView(webView: WebView) {
    try {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    } catch (e: Exception) {
        AppLogger.e(TAG, "Failed to release model OAuth login WebView", e)
    }
}
