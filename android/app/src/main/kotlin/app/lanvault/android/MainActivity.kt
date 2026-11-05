package app.lanvault.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lanvault.core.designsystem.LanVaultTheme
import app.lanvault.core.network.LOCAL_NETWORK_PERMISSION
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private var backupModel: BackupViewModel? = null

    override fun onResume() {
        super.onResume()
        backupModel?.foreground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) backupModel?.background()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: BackupViewModel = viewModel()
            backupModel = model
            val state by model.state.collectAsStateWithLifecycle()
            var pendingAction by rememberSaveable { mutableStateOf("") }
            var pendingSyncAction by rememberSaveable { mutableStateOf("syncAll") }
            fun dispatch(action: String) {
                when (action) {
                    "pair" -> model.pair()
                    "check" -> model.check()
                    "send" -> model.send()
                    "retry" -> model.retry()
                    "syncAll" -> model.syncAll()
                    "verify" -> model.syncAll(verifyCopies = true)
                }
            }
            val networkPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted ->
                    if (granted) dispatch(pendingAction) else model.localNetworkDenied()
                    pendingAction = ""
                }
            fun networkAction(action: String) {
                if (Build.VERSION.SDK_INT >= 37) {
                    pendingAction = action
                    networkPermission.launch(LOCAL_NETWORK_PERMISSION)
                } else dispatch(action)
            }
            val mediaPermissions =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    model.refreshAccess()
                    model.load(reset = true)
                }
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    networkAction(pendingSyncAction)
                }
            fun syncLibrary(verifyCopies: Boolean = false) {
                pendingSyncAction = if (verifyCopies) "verify" else "syncAll"
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else networkAction(pendingSyncAction)
            }
            LaunchedEffect(state.initialized, state.trusted) {
                val preferences = getSharedPreferences("lanvault-auto-sync", MODE_PRIVATE)
                if (
                    state.initialized &&
                        state.trusted != null &&
                        Build.VERSION.SDK_INT >= 33 &&
                        !preferences.getBoolean("notification-requested", false)
                ) {
                    preferences.edit().putBoolean("notification-requested", true).apply()
                    if (
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            fun requestMedia() {
                mediaPermissions.launch(
                    buildList {
                        if (Build.VERSION.SDK_INT >= 33) {
                            add(Manifest.permission.READ_MEDIA_IMAGES)
                            add(Manifest.permission.READ_MEDIA_VIDEO)
                        } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        if (Build.VERSION.SDK_INT >= 34)
                            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                        .toTypedArray()
                )
            }
            val scanQr =
                rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let(model::importQr)
                }
            LanVaultTheme {
                app.lanvault.android.ui.LanVaultApp(
                    state = state,
                    onScan = {
                        scanQr.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Наведите камеру на QR-код в LanVault на компьютере")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                    onImport = model::importQr,
                    onNetworkAction = ::networkAction,
                    onSync = { syncLibrary() },
                    onVerify = { syncLibrary(true) },
                    onMediaAccess = ::requestMedia,
                    onAutomatic = model::setAutomatic,
                    onPause = model::stopAutomatic,
                    onStop = model::stop,
                    onLoad = { model.load(reset = it) },
                    onSelect = model::toggle,
                    onForget = model::forget,
                )
            }
        }
    }
}
