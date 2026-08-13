package com.example.trex_kotlin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** What the app currently knows about the camera permission. */
internal enum class CameraPermissionUiState {
    /** Not asked yet in this session and not already held. */
    UNKNOWN,
    GRANTED,
    DENIED,

    /** Declined in a way that no longer shows the system prompt; only Settings can undo it. */
    PERMANENTLY_DENIED,
}

internal class CameraPermissionController(
    val state: CameraPermissionUiState,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/**
 * The automatic prompt fires at most once per process. Composables hosting the controller can be
 * disposed and recreated (phase changes, screen re-entry), and a declined dialog must not chase
 * the user across every remount; explicit request buttons are unaffected.
 */
private object CameraPermissionAutoRequest {
    var consumed: Boolean = false
}

/**
 * Asks for the camera once, then keeps the answer current.
 *
 * The app had no runtime permission flow before this screen, so the behaviour is defined here:
 * ask automatically on first arrival, never re-ask on recomposition or rotation, and re-read the
 * real permission whenever the app comes back to the foreground so a trip to Settings is noticed.
 */
@Composable
internal fun rememberCameraPermissionController(): CameraPermissionController {
    val context = LocalContext.current
    val activity = context.findActivity()

    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        permanentlyDenied = when {
            result -> false
            activity == null -> false
            else -> !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested && !CameraPermissionAutoRequest.consumed) {
            CameraPermissionAutoRequest.consumed = true
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val appPaused = rememberTrexLifecyclePaused()
    LaunchedEffect(appPaused) {
        if (!appPaused) {
            val nowGranted = context.hasCameraPermission()
            granted = nowGranted
            if (nowGranted) {
                permanentlyDenied = false
            }
        }
    }

    val state = when {
        granted -> CameraPermissionUiState.GRANTED
        permanentlyDenied -> CameraPermissionUiState.PERMANENTLY_DENIED
        // A prompt earlier in this process counts: a freshly remounted surface must show the
        // declined state, not pretend the question was never asked.
        requested || CameraPermissionAutoRequest.consumed -> CameraPermissionUiState.DENIED
        else -> CameraPermissionUiState.UNKNOWN
    }

    return CameraPermissionController(
        state = state,
        request = {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        },
        openSettings = {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        },
    )
}

private fun android.content.Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
