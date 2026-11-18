package com.privatespace.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.data.*
import com.privatespace.android.security.PasswordManager
import com.privatespace.android.security.RecoveryKeyAuthenticationException
import com.privatespace.android.security.VaultSessionPolicy
import com.privatespace.android.security.VaultImportControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val Ink = Color(0xFF080D18)
private val Panel = Color(0xFF111B2D)
private val Panel2 = Color(0xFF18263C)
private val Line = Color(0xFF293A54)
private val White = Color(0xFFF4F7FC)
private val Muted = Color(0xFF9BAEC5)
private val Blue = Color(0xFF315FBA)
private val Cyan = Color(0xFF69E0D0)
private val Red = Color(0xFFFF7888)
private val Green = Color(0xFF70D6A6)
private val Gold = Color(0xFFF3C16D)

/**
 * Prefer Samsung My Files only when the installed version explicitly handles
 * Android's directory-tree picker contract. Otherwise retain the standard SAF
 * picker, which returns the persistable tree URI required by recovery storage.
 */
private class SamsungPreferredOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val standardIntent = super.createIntent(context, input)
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            return standardIntent
        }

        val samsungIntent = Intent(standardIntent).setPackage(SAMSUNG_MY_FILES_PACKAGE)
        return if (samsungIntent.resolveActivity(context.packageManager) != null) {
            samsungIntent
        } else {
            standardIntent
        }
    }

    private companion object {
        const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"
    }
}

class MainActivity : ComponentActivity() {
    var onVaultShouldLock: (() -> Unit)? = null
    var isResumedForVault: Boolean = false
        private set
    var foregroundGeneration: Long = 0L
        private set
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerScreenOffReceiver()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 24)
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 24)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    onPrimary = Color.White,
                    primaryContainer = Blue.copy(alpha = 0.20f),
                    onPrimaryContainer = White,
                    secondary = Cyan,
                    onSecondary = Ink,
                    tertiary = Green,
                    background = Ink,
                    surface = Panel,
                    surfaceVariant = Panel2,
                    onBackground = White,
                    onSurface = White,
                    onSurfaceVariant = Muted,
                    outline = Line,
                    error = Red,
                    onError = Ink
                )
            ) { PrivateSpaceRoot() }
        }
    }

    /**
     * ACTION_SCREEN_OFF is a protected system broadcast and must be received
     * through a context-registered receiver. Register with the application
     * context so it remains active after this Activity is paused or stopped.
     *
     * A normal app switch does not emit this action, so backgrounding alone
     * continues to preserve the unlocked vault session.
     */
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (VaultSessionPolicy.shouldLockForDeviceScreenOff(intent.action)) {
                    // Route through the same callback used by the existing
                    // onPause keyguard check and the in-app lock implementation.
                    onVaultShouldLock?.invoke()
                }
            }
        }

        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiver = receiver
    }

    override fun onResume() {
        super.onResume()
        isResumedForVault = true
    }

    override fun onDestroy() {
        val receiver = screenOffReceiver
        screenOffReceiver = null
        if (receiver != null) {
            runCatching { applicationContext.unregisterReceiver(receiver) }
        }
        super.onDestroy()
    }

    override fun onPause() {
        isResumedForVault = false
        foregroundGeneration = if (foregroundGeneration == Long.MAX_VALUE) {
            0L
        } else {
            foregroundGeneration + 1L
        }
        super.onPause()
        if (VaultSessionPolicy.shouldLockForScreenLock(
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            )
        ) {
            onVaultShouldLock?.invoke()
        }
    }

    override fun onStop() {
        // Backgrounding is not an explicit lock request. The vault remains
        // unlocked unless the user taps Lock or Android reports a locked screen.
        isResumedForVault = false
        super.onStop()
    }
}

private data class FilePickerTarget(
    val label: String,
    val component: ComponentName,
    val intent: Intent
)

private data class HelpArticle(
    val id: String,
    val title: String,
    val body: String,
    val keywords: String
)

private val privateSpaceHelpArticles = listOf(
    HelpArticle(
        id = "getting-started",
        title = "Create and unlock your vault",
        body = "Create a strong vault password the first time you open Private Space. Enter it to unlock the vault on later visits. Your files remain encrypted in the app's private storage while they are in the vault.",
        keywords = "password setup sign in unlock first launch"
    ),
    HelpArticle(
        id = "recovery",
        title = "Set up or restore an encrypted recovery copy",
        body = "Recovery is optional. Choose a folder on this device when the recovery prompt appears, or use the Recovery card on the dashboard to set it up later. Private Space asks you to confirm your current vault password before protecting the recovery key. Keep the selected folder and the password used to protect it. Restoring a recovery folder replaces the current on-device vault; newer local files are not merged. Without a recovery copy, uninstalling the app or clearing its data can permanently remove the on-device vault. Recovery is device-local, not cloud sync.",
        keywords = "backup reinstall restore internal storage sd card forgotten password"
    ),
    HelpArticle(
        id = "add-files",
        title = "Add documents, photos and videos",
        body = "Open a topic and choose Add file(s). You can select multiple items in the Android file picker. Private Space imports copies into the selected topic; it does not move or modify the original files.",
        keywords = "import upload picker multiple files topic"
    ),
    HelpArticle(
        id = "import-progress",
        title = "Understand import progress and cancellation",
        body = "The import dialog shows the current file, completed files and byte progress when the provider reports a file size. Choose Cancel to stop the remaining work. Files that were already securely added stay in the topic; an item being finalized is allowed to finish its safe commit.",
        keywords = "progress loading cancel stop failed incomplete"
    ),
    HelpArticle(
        id = "open-share-export",
        title = "Understand Open, Share and Export",
        body = "Open prepares a temporary decrypted copy for the viewer app you choose. Share and Export ask for confirmation first. Any copy received or saved by another app or destination is outside Private Space protection, and another app may retain it.",
        keywords = "view open share send export download plaintext external app privacy"
    ),
    HelpArticle(
        id = "recycle-bin",
        title = "Restore a deleted file",
        body = "Deleting a file or topic moves its files to the Recycle Bin. Open Recycle Bin from the dashboard and choose Restore to return a file to its original topic. If the original topic no longer exists, Private Space restores the topic as needed.",
        keywords = "trash recycle bin undo recover restore deleted topic"
    ),
    HelpArticle(
        id = "permanent-delete",
        title = "Permanently delete a file",
        body = "In Recycle Bin, choose Delete forever and confirm. This removes the vault copy and cannot be undone. It does not delete the original file outside Private Space or copies you previously shared or exported. It is not a guarantee that every physical trace is erased from the device's storage.",
        keywords = "erase remove forever irreversible storage"
    ),
    HelpArticle(
        id = "media-preview",
        title = "Why a photo or video preview may be missing",
        body = "Private Space limits thumbnail work to protect memory and avoid decrypting very large media files just to draw a list row. A missing preview does not mean the stored original is damaged. Opening a file also depends on a compatible viewer or media decoder being installed on your device.",
        keywords = "thumbnail image video preview codec format playback memory"
    ),
    HelpArticle(
        id = "change-password",
        title = "Change your vault password",
        body = "Open the dashboard menu and choose Change password. Enter your current password, then the new password twice. Keep the new password safe: it is also used to protect an enabled recovery copy. There is no password reset service.",
        keywords = "change password rotate recovery security"
    ),
    HelpArticle(
        id = "storage",
        title = "Understand storage usage",
        body = "Vault Details shows the encrypted storage used by the vault. Files in the Recycle Bin continue to use storage until they are permanently deleted. Exported copies use the destination's storage and are not counted as vault storage.",
        keywords = "space capacity size encrypted files trash recycle"
    )
)

private const val FILE_PICKER_PREFERENCES = "private_space_ui"
private const val FILE_PICKER_PREFERENCE = "preferred_import_file_picker_component"
private const val RECOVERY_SETUP_PROMPT_DISMISSED = "recovery_setup_prompt_dismissed"
private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"
private const val SAMSUNG_VIDEO_PLAYER_PACKAGE = "com.samsung.android.video"
private const val LEGACY_SAMSUNG_VIDEO_PLAYER_PACKAGE = "com.sec.android.app.videoplayer"

/**
 * Repository lock cleanup can wait for an in-flight large-file/recovery
 * transaction. Keep that wait off the main thread and independent of any
 * Activity-owned coroutine scope so screen-lock cleanup survives recreation.
 */
private object VaultLockDispatcher {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "private-space-vault-lock").apply { isDaemon = true }
    }

    fun execute(task: () -> Unit) {
        executor.execute(task)
    }
}

private enum class VaultReleaseAction { SHARE, EXPORT }

private data class PendingVaultRelease(
    val action: VaultReleaseAction,
    val displayName: String,
    val fileIds: List<String> = emptyList(),
    val topicId: String? = null,
    val allTopics: Boolean = false
)

private enum class VaultImportStatus { QUEUED, RUNNING, PAUSED, FINALIZING, COMPLETED, FAILED, STOPPED }

private data class VaultImportQueueItem(
    val id: String,
    val topicId: String,
    val uri: Uri,
    val displayName: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val status: VaultImportStatus = VaultImportStatus.QUEUED,
    val error: String? = null
)

@Composable
private fun PrivateSpaceRoot() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    var repository by remember { mutableStateOf<VaultRepository?>(null) }
    var initializationError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(applicationContext, retryCount) {
        repository = null
        initializationError = null
        try {
            repository = withContext(Dispatchers.IO) {
                VaultRepository.getInstance(applicationContext).also {
                    it.ensureInitialized()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            initializationError = failure.message ?: "Private Space could not prepare its encrypted vault."
        }
    }

    val readyRepository = repository
    if (readyRepository == null) {
        VaultInitializationScreen(
            error = initializationError,
            onRetry = { retryCount++ }
        )
        return
    }

    PrivateSpaceContent(readyRepository)
}

@Composable
private fun VaultInitializationScreen(
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Line)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (error == null) {
                    CircularProgressIndicator(color = Cyan)
                    Text("Preparing your encrypted vault…", color = White, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Red, modifier = Modifier.size(34.dp))
                    Text("Vault initialization failed", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(error, color = Muted, fontSize = 13.sp)
                    Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateSpaceContent(repository: VaultRepository) {
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var unhandledOperationFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope {
        CoroutineExceptionHandler { _, failure ->
            // Do not pretend the process can safely recover from VM-level failures
            // such as OutOfMemoryError. Ordinary unexpected coroutine failures,
            // however, must fail closed instead of leaving an unlocked UI behind.
            if (failure !is Exception || failure is CancellationException) {
                throw failure
            }
            mainHandler.post {
                unhandledOperationFailure =
                    "An unexpected operation failed. Private Space will lock to protect your files. Please reopen the app and try again."
            }
        }
    }

    // Tracks long authentication/recovery work which must unwind on lock or Activity teardown.
    val activeLifecycleCancellation = remember { AtomicReference<AtomicBoolean?>(null) }

    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!PasswordManager.configured(context)) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var vaultLockPending by remember { mutableStateOf(false) }
    val vaultLockGeneration = remember { AtomicLong(0L) }
    var restoreProgress by remember { mutableStateOf<VaultRestoreProgress?>(null) }
    var fileOperationProgress by remember { mutableStateOf<VaultFileOperationProgress?>(null) }
    var fileOperationTitle by remember { mutableStateOf("") }
    var fileOperationToken by remember { mutableLongStateOf(0L) }
    var fileOperationCancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    var activeBatchImportControl by remember { mutableStateOf<VaultImportControl?>(null) }
    val lastFileProgressPostAt = remember { AtomicLong(0L) }
    var recoveryTreeUri by remember { mutableStateOf<Uri?>(null) }
    var choosingExistingRecovery by remember { mutableStateOf(false) }
    var pendingRecoveryPassword by remember { mutableStateOf<CharArray?>(null) }
    var showRecoverySetup by remember { mutableStateOf(false) }
    var recoverySetupError by remember { mutableStateOf("") }
    var showRecoveryPasswordPrompt by remember { mutableStateOf(false) }
    var recoveryPasswordError by remember { mutableStateOf("") }
    var showRecoveryReconnectConfirmation by remember { mutableStateOf(false) }
    var recoveryConflict by remember { mutableStateOf(false) }
    val recoveryPromptPreferences = remember(context) {
        context.getSharedPreferences(FILE_PICKER_PREFERENCES, Context.MODE_PRIVATE)
    }
    var lockUntil by remember { mutableLongStateOf(PasswordManager.lockedUntil(context)) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var topics by remember { mutableStateOf<List<VaultTopic>>(emptyList()) }
    var vaultFiles by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var trashEntries by remember { mutableStateOf<List<VaultTrashEntry>>(emptyList()) }
    var storageUsage by remember { mutableStateOf(VaultStorageUsage()) }
    var activeTopicId by remember { mutableStateOf<String?>(null) }
    var topicFilesNewestFirst by remember { mutableStateOf(true) }
    var activeMediaId by remember { mutableStateOf<String?>(null) }
    var activeMediaFile by remember { mutableStateOf<PreparedVaultFile?>(null) }
    var mediaViewerLoading by remember { mutableStateOf(false) }
    var mediaRequestToken by remember { mutableLongStateOf(0L) }
    var topicQuery by remember { mutableStateOf("")}
    var showVaultDetails by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSecurityInfo by remember { mutableStateOf(false) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var trashEntryToPermanentlyDelete by remember { mutableStateOf<VaultTrashEntry?>(null) }
    var showCreateTopic by remember { mutableStateOf(false) }
    var topicName by remember { mutableStateOf("") }
    var showChangePassword by remember { mutableStateOf(false) }
    var topicToDelete by remember { mutableStateOf<VaultTopic?>(null) }
    var fileToDelete by remember { mutableStateOf<VaultFile?>(null) }

    var pendingImportTopic by remember { mutableStateOf<String?>(null) }
    var importQueue by remember { mutableStateOf<List<VaultImportQueueItem>>(emptyList()) }
    var importControls by remember { mutableStateOf<Map<String, VaultImportControl>>(emptyMap()) }
    var activeImportJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var pendingFilePickerTargets by remember { mutableStateOf<List<FilePickerTarget>?>(null) }
    var pendingExportFile by remember { mutableStateOf<String?>(null) }
    var pendingExportTopic by remember { mutableStateOf<String?>(null) }
    var pendingExportAll by remember { mutableStateOf(false) }
    var pendingExportSelection by remember { mutableStateOf<List<String>?>(null) }
    var pendingReleaseConfirmation by remember { mutableStateOf<PendingVaultRelease?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun beginFileOperation(title: String): Long {
        fileOperationToken += 1L
        lastFileProgressPostAt.set(0L)
        fileOperationTitle = title
        fileOperationProgress = VaultFileOperationProgress(
            phase = "Preparing",
            completedBytes = 0L,
            totalBytes = 0L
        )
        return fileOperationToken
    }

    fun publishFileOperationProgress(token: Long, progress: VaultFileOperationProgress) {
        val now = System.currentTimeMillis()
        val previous = lastFileProgressPostAt.get()
        val isFinal = (progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes) ||
            (progress.totalFiles > 0 && progress.completedFiles >= progress.totalFiles)
        if (now - previous < 100L && !isFinal) return
        lastFileProgressPostAt.set(now)
        mainHandler.post {
            if (unlocked && fileOperationToken == token) {
                fileOperationProgress = progress
            }
        }
    }

    fun finishFileOperation(token: Long) {
        if (fileOperationToken == token) {
            fileOperationToken += 1L
            fileOperationProgress = null
            fileOperationTitle = ""
            fileOperationCancel = null
        }
    }

    fun discardViewerFile(prepared: PreparedVaultFile?) {
        if (prepared == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.discardMediaForViewing(prepared) }
            }
            result.onFailure {
                toast("Temporary media could not be removed from the private cache.")
            }
        }
    }

    fun lockVault() {
        // Stop authentication/recovery migrations before waiting for repository storage locks.
        activeLifecycleCancellation.getAndSet(null)?.set(true)
        // Stop any in-flight import/open/export before invalidating its UI token.
        // In particular, an export must not continue releasing plaintext after
        // the vault has been explicitly locked or locked by the screen policy.
        fileOperationCancel?.invoke()
        activeBatchImportControl?.stop()
        // Mark the UI locked immediately, but never wait on the repository
        // monitor here. Large imports/restores/exports may hold it while blocked
        // on storage providers. Authentication remains disabled until the
        // background lock has acquired the monitor and wiped the recovery key.
        val lockGeneration = vaultLockGeneration.incrementAndGet()
        vaultLockPending = true
        VaultLockDispatcher.execute {
            try {
                repository.lockPersistentStorage()
            } finally {
                mainHandler.post {
                    if (vaultLockGeneration.get() == lockGeneration) {
                        vaultLockPending = false
                    }
                }
            }
        }
        fileOperationToken += 1L
        fileOperationProgress = null
        fileOperationTitle = ""
        fileOperationCancel = null
        activeBatchImportControl = null
        restoreProgress = null
        pendingRecoveryPassword?.fill('\u0000')
        pendingRecoveryPassword = null
        recoveryTreeUri = null
        choosingExistingRecovery = false
        showRecoverySetup = false
        recoverySetupError = ""
        showRecoveryPasswordPrompt = false
        recoveryPasswordError = ""
        showRecoveryReconnectConfirmation = false
        recoveryConflict = false
        unlocked = false
        password = ""
        confirmation = ""
        authError = ""
        activeTopicId = null
        mediaRequestToken += 1L
        discardViewerFile(activeMediaFile)
        activeMediaFile = null
        activeMediaId = null
        mediaViewerLoading = false
        topicQuery = ""
        showVaultDetails = false
        showHelp = false
        showSecurityInfo = false
        topMenuExpanded = false
        topics = emptyList()
        vaultFiles = emptyList()
        trashEntries = emptyList()
        storageUsage = VaultStorageUsage()
        showTrash = false
        trashEntryToPermanentlyDelete = null
        topicName = ""
        showCreateTopic = false
        showChangePassword = false
        topicToDelete = null
        fileToDelete = null
        importControls.values.forEach { it.stop() }
        importControls = emptyMap()
        importQueue = emptyList()
        activeImportJobs = emptyMap()
        pendingImportTopic = null
        pendingFilePickerTargets = null
        pendingExportFile = null
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = null
        pendingReleaseConfirmation = null
    }

    DisposableEffect(activity) {
        activity?.onVaultShouldLock = { lockVault() }
        onDispose {
            // Backgrounding does not dispose this effect. If the Activity is
            // actually destroyed/recreated, stop work owned by its old UI so
            // IO callbacks cannot keep writing or exposing temporary files.
            activeLifecycleCancellation.getAndSet(null)?.set(true)
            fileOperationCancel?.invoke()
            activeBatchImportControl?.stop()
            importControls.values.forEach { it.stop() }
            activeImportJobs.values.forEach { it.cancel() }
            activity?.onVaultShouldLock = null
        }
    }

    fun closeMediaViewer() {
        mediaRequestToken += 1L
        discardViewerFile(activeMediaFile)
        activeMediaFile = null
        activeMediaId = null
        mediaViewerLoading = false
    }

    fun applySnapshot(snapshot: VaultSnapshot): Boolean {
        if (!unlocked) return false
        topics = snapshot.topics
        vaultFiles = snapshot.files
        trashEntries = snapshot.trash
        storageUsage = snapshot.storageUsage
        return true
    }

    fun failClosed(failure: Throwable) {
        val message = failure.message ?: "The vault could not be accessed safely."
        lockVault()
        authError = message
        toast(message)
    }

    LaunchedEffect(unhandledOperationFailure) {
        val message = unhandledOperationFailure ?: return@LaunchedEffect
        unhandledOperationFailure = null
        if (unlocked) lockVault()
        authError = message
        toast(message)
    }

    suspend fun refreshVault(): Boolean {
        if (!unlocked) return false
        return try {
            val snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            applySnapshot(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (unlocked) failClosed(failure)
            false
        }
    }

    LaunchedEffect(unlocked) {
        if (unlocked) {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.cleanupExpiredOutgoing()
                    repository.snapshot()
                }
                applySnapshot(snapshot)

                // Reclaim expired external-share and staging sessions even when
                // the user keeps the vault unlocked for a long time.
                while (unlocked) {
                    delay(5 * 60 * 1_000L)
                    if (!unlocked) break
                    withContext(Dispatchers.IO) {
                        repository.cleanupExpiredOutgoing()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (unlocked) failClosed(failure)
            }
        }
    }

    LaunchedEffect(lockUntil) {
        while (lockUntil > System.currentTimeMillis()) {
            clockNow = System.currentTimeMillis()
            delay(1_000)
        }
        clockNow = System.currentTimeMillis()
    }

    fun updateImportItem(id: String, transform: (VaultImportQueueItem) -> VaultImportQueueItem) {
        importQueue = importQueue.map { item -> if (item.id == id) transform(item) else item }
    }

    fun ensureImportWorkers() {
        if (!unlocked) return
        val availableSlots = (2 - activeImportJobs.size).coerceAtLeast(0)
        if (availableSlots == 0) return
        val candidates = importQueue
            .filter { it.status == VaultImportStatus.QUEUED && it.id !in activeImportJobs }
            .take(availableSlots)

        candidates.forEach { next ->
            val control = importControls[next.id] ?: VaultImportControl().also {
                importControls = importControls + (next.id to it)
            }
            updateImportItem(next.id) { it.copy(status = VaultImportStatus.RUNNING, error = null) }
            val job = scope.launch {
                var failure: Exception? = null
                try {
                    val importedFile = withContext(Dispatchers.IO) {
                        var lastProgressPostAt = 0L
                        repository.addFileWithProgress(
                            next.topicId,
                            next.uri,
                            control,
                            onProgress = { completed, total ->
                                val now = System.currentTimeMillis()
                                if (now - lastProgressPostAt >= 150L || (total != null && completed >= total)) {
                                    lastProgressPostAt = now
                                    mainHandler.post {
                                        if (unlocked && importControls[next.id] === control) updateImportItem(next.id) {
                                            it.copy(
                                                completedBytes = completed.coerceAtLeast(0L),
                                                totalBytes = total?.takeIf { value -> value >= 0L }
                                            )
                                        }
                                    }
                                }
                            },
                            onFinalizing = {
                                mainHandler.post {
                                    if (unlocked && importControls[next.id] === control) updateImportItem(next.id) {
                                        it.copy(status = VaultImportStatus.FINALIZING)
                                    }
                                }
                            }
                        )
                    }
                    if (unlocked && importControls[next.id] === control) {
                        updateImportItem(next.id) {
                            it.copy(
                                displayName = importedFile.displayName,
                                status = VaultImportStatus.COMPLETED,
                                completedBytes = importedFile.sizeBytes,
                                totalBytes = importedFile.sizeBytes,
                                error = null
                            )
                        }
                        refreshVault()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (caught: Exception) {
                    failure = caught
                    if (unlocked && importControls[next.id] === control) updateImportItem(next.id) {
                        it.copy(
                            status = if (control.isStopped) VaultImportStatus.STOPPED else VaultImportStatus.FAILED,
                            error = if (control.isStopped) null else caught.message ?: "Import failed."
                        )
                    }
                } finally {
                    activeImportJobs = activeImportJobs - next.id
                    if (failure != null && unlocked && !control.isStopped && importControls[next.id] === control) {
                        toast(failure?.message ?: "A file could not be imported.")
                    }
                    ensureImportWorkers()
                }
            }
            activeImportJobs = activeImportJobs + (next.id to job)
        }
    }

    fun pauseImport(id: String) {
        importQueue.firstOrNull { it.id == id } ?: return
        val control = importControls[id]
        if (control?.isFinalizing == true) return
        control?.pause()
        updateImportItem(id) { it.copy(status = VaultImportStatus.PAUSED) }
    }

    fun resumeImport(id: String) {
        val item = importQueue.firstOrNull { it.id == id } ?: return
        if (item.status != VaultImportStatus.PAUSED) return
        importControls[id]?.resume()
        updateImportItem(id) { it.copy(status = if (id in activeImportJobs) VaultImportStatus.RUNNING else VaultImportStatus.QUEUED) }
        ensureImportWorkers()
    }

    fun stopImport(id: String) {
        val control = importControls[id]
        val accepted = control?.stop() ?: true
        if (!accepted && control?.isFinalizing == true) {
            toast("This file is already being finalized and can no longer be stopped.")
            return
        }
        updateImportItem(id) { it.copy(status = VaultImportStatus.STOPPED, error = null) }
    }

    fun restartImport(id: String) {
        val item = importQueue.firstOrNull { it.id == id } ?: return
        if (item.status in setOf(VaultImportStatus.COMPLETED, VaultImportStatus.FINALIZING)) return
        val previousControl = importControls[id]
        val alreadyFailedOrStopped = item.status in setOf(VaultImportStatus.FAILED, VaultImportStatus.STOPPED)
        if (previousControl?.isFinalizing == true && !alreadyFailedOrStopped) return
        if (previousControl != null) {
            val accepted = previousControl.stop()
            if (!accepted && previousControl.isFinalizing && !alreadyFailedOrStopped) return
        }
        val control = VaultImportControl()
        importControls = importControls + (id to control)
        updateImportItem(id) {
            it.copy(
                completedBytes = 0L,
                totalBytes = null,
                status = VaultImportStatus.QUEUED,
                error = null
            )
        }
        ensureImportWorkers()
    }

    fun dismissImport(id: String) {
        val item = importQueue.firstOrNull { it.id == id } ?: return
        if (item.status in setOf(VaultImportStatus.RUNNING, VaultImportStatus.PAUSED, VaultImportStatus.QUEUED)) return
        importQueue = importQueue.filterNot { it.id == id }
        importControls = importControls - id
    }

    fun handleImportUris(selectedUris: List<Uri>) {
        pendingFilePickerTargets = null
        val topicId = pendingImportTopic
        pendingImportTopic = null
        val uniqueUris = selectedUris.distinct()
        if (topicId == null || uniqueUris.isEmpty()) return
        if (!unlocked) {
            toast("The vault was locked. Unlock it before importing files.")
            return
        }
        if (busy || fileOperationProgress != null) {
            toast("Another file operation is already running.")
            return
        }
        // Content-provider metadata calls may block on cloud-backed, removable,
        // or otherwise slow document providers. Start the operation immediately,
        // then perform the provider queries on IO rather than freezing the UI.
        val token = beginFileOperation(
            if (uniqueUris.size == 1) "Preparing selected file" else "Preparing ${uniqueUris.size} selected files"
        )
        fileOperationProgress = VaultFileOperationProgress(
            phase = "Preparing files",
            completedBytes = 0L,
            totalBytes = 0L,
            totalFiles = uniqueUris.size,
            currentFileName = "Preparing selected files"
        )
        busy = true
        var activeControl: VaultImportControl? = null
        val cancelRequested = AtomicBoolean(false)
        val job = scope.launch {
            var completedBytes = 0L
            var completedFiles = 0
            var cancelled = false
            try {
                val selectedMetadata = withContext(Dispatchers.IO) {
                    uniqueUris.mapIndexed { index, uri ->
                        val values = runCatching {
                            context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (!cursor.moveToFirst()) {
                                    null
                                } else {
                                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    val name = if (nameColumn >= 0) {
                                        runCatching { cursor.getString(nameColumn) }.getOrNull()
                                    } else {
                                        null
                                    }
                                    val size = if (sizeColumn >= 0) {
                                        runCatching {
                                            VaultInputValidation.reportedSize(
                                                cursor.getLong(sizeColumn),
                                                cursor.isNull(sizeColumn)
                                            )
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                                    name to size
                                }
                            }
                        }.getOrNull()
                        val name = values?.first?.takeIf { it.isNotBlank() }
                            ?: "Selected file ${index + 1}"
                        val size = values?.second?.takeIf { it > 0L }
                        name to size
                    }
                }
                val names = selectedMetadata.map { it.first }
                val sizes = selectedMetadata.map { it.second }
                val knownTotal = sizes.takeIf { values -> values.all { it != null } }
                    ?.let { values ->
                        runCatching {
                            values.fold(0L) { total, size ->
                                Math.addExact(total, size ?: 0L)
                            }
                        }.getOrNull()
                    }
                fileOperationTitle = if (uniqueUris.size == 1) {
                    "Adding ${names.first()} to topic"
                } else {
                    "Adding ${uniqueUris.size} files to topic"
                }
                fileOperationProgress = VaultFileOperationProgress(
                    phase = "Preparing files",
                    completedBytes = 0L,
                    totalBytes = knownTotal ?: 0L,
                    totalFiles = uniqueUris.size,
                    currentFileName = names.firstOrNull().orEmpty(),
                    currentFileTotalBytes = sizes.firstOrNull() ?: 0L
                )

                uniqueUris.forEachIndexed { index, uri ->
                    if (!unlocked || cancelRequested.get()) {
                        throw CancellationException("Import cancelled before the next file.")
                    }
                    val control = VaultImportControl()
                    activeControl = control
                    activeBatchImportControl = control
                    val fileSize = sizes[index] ?: 0L
                    withContext(Dispatchers.IO) {
                        repository.addFileWithProgress(topicId, uri, control,
                            onProgress = { current, reportedTotal ->
                                if (cancelRequested.get()) throw CancellationException("Import cancelled.")
                                if (!control.isStopped) publishFileOperationProgress(token,
                                    VaultFileOperationProgress(
                                        phase = if (control.isFinalizing) "Finalizing encrypted file" else "Encrypting",
                                        completedBytes = completedBytes + current.coerceAtLeast(0L),
                                        totalBytes = knownTotal ?: 0L, completedFiles = completedFiles,
                                        totalFiles = uniqueUris.size, currentFileName = names[index],
                                        currentFileCompletedBytes = current.coerceAtLeast(0L),
                                        currentFileTotalBytes = reportedTotal ?: fileSize
                                    ))
                            },
                            onFinalizing = {
                                publishFileOperationProgress(token, VaultFileOperationProgress(
                                    phase = "Finalizing encrypted file",
                                    completedBytes = completedBytes + fileSize, totalBytes = knownTotal ?: 0L,
                                    completedFiles = completedFiles, totalFiles = uniqueUris.size,
                                    currentFileName = names[index], currentFileCompletedBytes = fileSize,
                                    currentFileTotalBytes = fileSize
                                ))
                            }
                        )
                    }
                    completedBytes += fileSize
                    completedFiles++
                    activeControl = null
                    activeBatchImportControl = null
                    publishFileOperationProgress(token, VaultFileOperationProgress(
                        phase = "File added securely", completedBytes = completedBytes,
                        totalBytes = knownTotal ?: 0L, completedFiles = completedFiles,
                        totalFiles = uniqueUris.size, currentFileName = names[index],
                        currentFileCompletedBytes = fileSize, currentFileTotalBytes = fileSize
                    ))
                }
                refreshVault()
                toast(if (uniqueUris.size == 1) "File added securely to the topic." else "${completedFiles} files added securely to the topic.")
            } catch (cancelledFailure: CancellationException) {
                cancelled = true
                if (completedFiles > 0) withContext(NonCancellable) { refreshVault() }
                throw cancelledFailure
            } catch (failure: Exception) {
                cancelled = activeControl?.isStopped == true || cancelRequested.get()
                if (!cancelled) toast(failure.message ?: "One or more files could not be added.")
                if (completedFiles > 0) refreshVault()
            } finally {
                activeBatchImportControl = null
                activeControl = null
                finishFileOperation(token)
                busy = false
                if (cancelled && unlocked) toast("Adding files was cancelled. Files already completed remain safely in the topic.")
            }
        }
        fileOperationCancel = {
            cancelRequested.set(true)
            val control = activeBatchImportControl
            if (control != null && !control.stop()) {
                // The active file has crossed its atomic commit boundary. Let it
                // finish, then the loop guard prevents any following file from
                // starting. Cancelling the coroutine here could hide a committed
                // file from the refreshed topic snapshot.
                toast("The current file is being securely finalized. Remaining files will be cancelled.")
            } else {
                job.cancel()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selectedUris ->
        handleImportUris(selectedUris)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultIntent = result.data
        val selectedUris = if (result.resultCode == Activity.RESULT_OK) {
            buildList {
                resultIntent?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index).uri?.let(::add)
                    }
                }
                resultIntent?.data?.let(::add)
            }.distinct()
        } else {
            emptyList()
        }
        handleImportUris(selectedUris)
    }

    val recoveryFolderLauncher = rememberLauncherForActivityResult(
        SamsungPreferredOpenDocumentTree()
    ) { selectedTreeUri ->
        if (selectedTreeUri == null) {
            if (choosingExistingRecovery) {
                choosingExistingRecovery = false
                authError = "Recovery folder selection was cancelled. You can unlock your on-device vault with its current password and try again from the Recovery card."
            } else {
                recoverySetupError = "Select a device-storage folder to protect your vault."
            }
        } else if (selectedTreeUri.authority != "com.android.externalstorage.documents") {
            if (choosingExistingRecovery) {
                choosingExistingRecovery = false
                authError = "Choose a folder in this device's internal or SD-card storage, not a cloud provider."
            } else {
                recoverySetupError = "Choose a folder in this device's internal or SD-card storage, not a cloud provider."
            }
        } else {
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val grantResult = runCatching {
                context.contentResolver.takePersistableUriPermission(selectedTreeUri, grantFlags)
            }
            if (grantResult.isFailure) {
                val message = "Android could not retain access to the selected recovery folder. Choose a device Documents folder and try again."
                if (choosingExistingRecovery) {
                    choosingExistingRecovery = false
                    authError = message
                } else {
                    recoverySetupError = message
                }
            } else if (choosingExistingRecovery) {
                choosingExistingRecovery = false
                pendingRecoveryPassword?.fill('\u0000')
                pendingRecoveryPassword = null
                showRecoverySetup = false
                recoverySetupError = ""
                recoveryTreeUri = selectedTreeUri
                setup = false
                password = ""
                confirmation = ""
                authError = "Enter the password that protected this encrypted recovery vault."
            } else {
                val secret = pendingRecoveryPassword?.copyOf()
                if (secret == null) {
                    recoverySetupError = "The setup session expired. Unlock Private Space again."
                } else {
                    val operationCancellation = AtomicBoolean(false)
                    activeLifecycleCancellation.set(operationCancellation)
                    scope.launch {
                        try {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    repository.enablePersistentStorage(selectedTreeUri, secret) {
                                        if (operationCancellation.get()) {
                                            throw CancellationException("Recovery setup cancelled because the vault was locked.")
                                        }
                                    }
                                }
                            }
                            if (operationCancellation.get()) return@launch
                            busy = false
                            result.onSuccess {
                                if (!unlocked || activity?.isResumedForVault != true) {
                                    repository.lockPersistentStorage()
                                    pendingRecoveryPassword?.fill(0.toChar())
                                    pendingRecoveryPassword = null
                                    showRecoverySetup = false
                                    return@onSuccess
                                }
                                pendingRecoveryPassword?.fill(0.toChar())
                                pendingRecoveryPassword = null
                                showRecoverySetup = false
                                recoverySetupError = ""
                                recoveryPromptPreferences.edit()
                                    .putBoolean(RECOVERY_SETUP_PROMPT_DISMISSED, true)
                                    .apply()
                                toast("Encrypted recovery storage is ready. Your vault can now be restored after reinstalling Private Space.")
                            }.onFailure {
                                recoverySetupError = it.message ?: "Could not prepare encrypted recovery storage."
                                toast(recoverySetupError)
                            }
                        } catch (_: CancellationException) {
                            // Locking is expected cancellation, not a recovery setup failure.
                        } finally {
                            secret.fill('\u0000')
                            activeLifecycleCancellation.compareAndSet(operationCancellation, null)
                            busy = false
                        }
                    }
                }
            }
        }
    }

    val filePickerPreferences = remember(context) {
        context.getSharedPreferences(FILE_PICKER_PREFERENCES, Context.MODE_PRIVATE)
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destination ->
        val fileId = pendingExportFile
        pendingExportFile = null
        if (destination != null && fileId != null && unlocked) {
            scope.launch {
                busy = true
                val file = vaultFiles.firstOrNull { it.id == fileId }
                val operationToken = beginFileOperation("Exporting ${file?.displayName ?: "file"}")
                val cancelRequested = AtomicBoolean(false)
                fileOperationCancel = { cancelRequested.set(true) }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.exportToUri(fileId, destination) { progress ->
                            if (cancelRequested.get()) throw CancellationException("Export cancelled.")
                            publishFileOperationProgress(operationToken, progress)
                        }
                    }
                }
                finishFileOperation(operationToken)
                busy = false
                result.onSuccess { toast("File exported. The exported copy is outside Private Space protection.") }
                    .onFailure {
                        toast(if (cancelRequested.get()) "Export cancelled." else it.message ?: "Export failed.")
                    }
            }
        } else if (destination != null) {
            // CreateDocument creates the destination before returning its URI.
            // If the vault was locked while the picker was open, lockVault()
            // clears pendingExportFile; still remove the unapproved empty target.
            runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
            if (fileId != null || !unlocked) {
                toast("The vault was locked. Unlock it and start the export again.")
            }
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { destination ->
        val topicId = pendingExportTopic
        val allTopics = pendingExportAll
        val selectedFileIds = pendingExportSelection
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = null
        if (destination != null && unlocked) {
            scope.launch {
                busy = true
                val operationTitle = when {
                    allTopics -> "Exporting all topics"
                    selectedFileIds != null -> "Exporting ${selectedFileIds.size} selected files"
                    else -> {
                        val topicName = topics.firstOrNull { it.id == topicId }?.name ?: "topic"
                        "Exporting $topicName"
                    }
                }
                val operationToken = beginFileOperation(operationTitle)
                val cancelRequested = AtomicBoolean(false)
                fileOperationCancel = { cancelRequested.set(true) }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = DocumentFile.fromTreeUri(context, destination)
                            ?: error("The selected folder is unavailable.")
                        val progressCallback: (VaultFileOperationProgress) -> Unit = { progress ->
                            if (cancelRequested.get()) throw CancellationException("Export cancelled.")
                            publishFileOperationProgress(operationToken, progress)
                        }
                        when {
                            allTopics -> repository.exportAllToFolder(folder, progressCallback)
                            selectedFileIds != null -> repository.exportFilesToFolder(selectedFileIds, folder, progressCallback)
                            else -> repository.exportTopicToFolder(
                                topicId ?: error("No topic was selected for export."),
                                folder,
                                progressCallback
                            )
                        }
                    }
                }
                finishFileOperation(operationToken)
                busy = false
                result.onSuccess {
                    toast("Export complete. Exported files are outside Private Space protection.")
                }.onFailure {
                    toast(if (cancelRequested.get()) "Export cancelled." else it.message ?: "Export failed. Check the selected folder.")
                }
            }
        } else if (destination != null) {
            toast("The vault was locked. Unlock it and start the export again.")
        }
    }

    fun launchSystemDocumentPicker() {
        runCatching { importLauncher.launch(arrayOf("*/*")) }.onFailure {
            pendingImportTopic = null
            toast("Could not open the Android document picker.")
        }
    }

    fun launchFilePickerTarget(target: FilePickerTarget, rememberChoice: Boolean): Boolean {
        if (rememberChoice) {
            filePickerPreferences.edit()
                .putString(FILE_PICKER_PREFERENCE, target.component.flattenToString())
                .apply()
        }
        return runCatching {
            filePickerLauncher.launch(Intent(target.intent))
            true
        }.getOrElse {
            filePickerPreferences.edit().remove(FILE_PICKER_PREFERENCE).apply()
            false
        }
    }

    fun launchImport(topicId: String) {
        pendingImportTopic = topicId
        // Some file managers register ACTION_OPEN_DOCUMENT, others register
        // ACTION_GET_CONTENT. Query both while preferring the document contract
        // when the same activity supports both.
        val pickerIntents = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT),
            Intent(Intent.ACTION_GET_CONTENT)
        ).map { intent ->
            intent.apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val preferredPackages = listOf(
            "com.sec.android.app.myfiles",
            "com.alphainventor.filemanager"
        )
        val targets = runCatching {
            pickerIntents.flatMap { pickerIntent ->
                context.packageManager
                    .queryIntentActivities(pickerIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNull { resolved ->
                        val info = resolved.activityInfo ?: return@mapNotNull null
                        val component = ComponentName(info.packageName, info.name)
                        val label = runCatching {
                            resolved.loadLabel(context.packageManager).toString()
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: runCatching { info.loadLabel(context.packageManager).toString() }
                                .getOrNull()?.takeIf { it.isNotBlank() }
                            ?: info.packageName
                        FilePickerTarget(
                            label = label,
                            component = component,
                            intent = Intent(pickerIntent).setComponent(component)
                        )
                    }
            }
                .distinctBy { it.component }
                .sortedWith(
                    compareBy<FilePickerTarget> {
                        val index = preferredPackages.indexOf(it.component.packageName)
                        if (index < 0) preferredPackages.size else index
                    }.thenBy { it.label.lowercase() }
                )
        }.getOrDefault(emptyList())

        val savedComponent = filePickerPreferences
            .getString(FILE_PICKER_PREFERENCE, null)
            ?.let { ComponentName.unflattenFromString(it) }
        if (savedComponent != null) {
            val savedTarget = targets.firstOrNull { it.component == savedComponent }
            if (savedTarget != null && launchFilePickerTarget(savedTarget, rememberChoice = false)) {
                return
            }
            filePickerPreferences.edit().remove(FILE_PICKER_PREFERENCE).apply()
        }

        if (targets.isNotEmpty()) {
            pendingFilePickerTargets = targets
        } else {
            launchSystemDocumentPicker()
        }
    }

    fun openSingleFileExportPicker(file: VaultFile) {
        pendingExportFile = file.id
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = null
        runCatching { exportFileLauncher.launch(file.displayName) }.onFailure {
            pendingExportFile = null
            toast("Could not open the export picker.")
        }
    }

    fun openTopicExportPicker(topicId: String) {
        pendingExportFile = null
        pendingExportSelection = null
        pendingExportTopic = topicId
        pendingExportAll = false
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            pendingExportTopic = null
            toast("Could not open the folder picker.")
        }
    }

    fun openSelectedFilesExportPicker(files: List<VaultFile>) {
        if (files.isEmpty()) return
        pendingExportFile = null
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = files.map { it.id }
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            pendingExportSelection = null
            toast("Could not open the folder picker.")
        }
    }

    fun openAllExportPicker() {
        if (topics.isEmpty()) {
            toast("There are no topics to export.")
            return
        }
        pendingExportFile = null
        pendingExportSelection = null
        pendingExportTopic = null
        pendingExportAll = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            pendingExportAll = false
            toast("Could not open the folder picker.")
        }
    }

    fun openExternalVaultFile(
        file: VaultFile,
        preferredSamsungPackages: List<String> = emptyList(),
        mimeTypeOverride: String? = null
    ) {
        if (!unlocked || busy) return
        busy = true
        val operationToken = beginFileOperation("Opening ${file.displayName}")
        fileOperationProgress = VaultFileOperationProgress(
            "Decrypting",
            0L,
            file.sizeBytes,
            currentFileName = file.displayName,
            currentFileCompletedBytes = 0L,
            currentFileTotalBytes = file.sizeBytes
        )
        val cancelRequested = AtomicBoolean(false)
        fileOperationCancel = { cancelRequested.set(true) }
        scope.launch {
            var prepared: PreparedVaultFile? = null
            var handedOff = false
            try {
                prepared = withContext(Dispatchers.IO) {
                    repository.prepareDecryptedFile(file.id) { progress ->
                        if (cancelRequested.get()) throw CancellationException("Opening cancelled.")
                        publishFileOperationProgress(operationToken, progress)
                    }
                }
                if (cancelRequested.get()) throw CancellationException("Opening cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) {
                    toast("Private Space was locked before the file could be opened.")
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "com.privatespace.android.fileprovider",
                    prepared.file
                )
                val baseIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeTypeOverride ?: prepared.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                baseIntent.clipData = ClipData.newUri(
                    context.contentResolver,
                    file.displayName,
                    uri
                )

                val samsungIntents = if (
                    Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                ) {
                    preferredSamsungPackages.map { packageName ->
                        Intent(baseIntent).setPackage(packageName)
                    }
                } else {
                    emptyList()
                }
                // Try Samsung targets directly before Android's generic resolver.
                // Avoid resolveActivity pre-filtering on OEM builds because some
                // Samsung handlers are not consistently reported by the resolver.
                val launchCandidates = samsungIntents + baseIntent
                withContext(Dispatchers.IO) {
                    repository.markOutgoingHandedOff(listOf(checkNotNull(prepared)))
                }
                if (cancelRequested.get()) throw CancellationException("Opening cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch
                var launchFailure: Exception? = null
                for (candidate in launchCandidates) {
                    try {
                        context.startActivity(candidate)
                        handedOff = true
                        break
                    } catch (failure: Exception) {
                        launchFailure = failure
                    }
                }
                if (!handedOff) {
                    throw launchFailure ?: IllegalStateException(
                        "No compatible app is installed to open this file."
                    )
                }
            } catch (cancelled: CancellationException) {
                toast("Opening file cancelled.")
            } catch (memoryFailure: OutOfMemoryError) {
                toast("There is not enough available memory to prepare this file.")
            } catch (failure: Exception) {
                toast(failure.message ?: "The file could not be opened by another app.")
            } finally {
                val staged = prepared
                if (!handedOff && staged != null) {
                    val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            repository.discardPreparedOutgoing(listOf(staged))
                        }.exceptionOrNull()
                    }
                    if (cleanupFailure != null) {
                        toast("A temporary decrypted copy could not be removed from private cache.")
                    }
                }
                finishFileOperation(operationToken)
                busy = false
            }
        }
    }

    fun openVaultFile(file: VaultFile) {
        if (busy) return
        if (
            activeTopicId != file.topicId ||
            vaultFiles.none { it.id == file.id } ||
            !unlocked
        ) {
            toast("This file is no longer available in the open topic.")
            return
        }

        val kind = VaultFormatSupport.kindForFile(file.displayName, file.mimeType)
        val extensionMimeType = VaultFormatSupport.mimeTypeForName(file.displayName)
        val targetMimeType = extensionMimeType ?: file.mimeType
        val samsungTargets = when (kind) {
            VaultFormatSupport.FileKind.PHOTO -> listOf(SAMSUNG_GALLERY_PACKAGE)
            VaultFormatSupport.FileKind.VIDEO -> listOf(
                SAMSUNG_GALLERY_PACKAGE,
                SAMSUNG_VIDEO_PLAYER_PACKAGE,
                LEGACY_SAMSUNG_VIDEO_PLAYER_PACKAGE
            )
            VaultFormatSupport.FileKind.DOCUMENT -> emptyList()
        }

        // Opening is an explicit tap on this file. It launches the appropriate
        // external viewer directly; only Share and Export retain confirmation.
        // The original bytes are decrypted into the restricted, temporary
        // FileProvider cache and are never copied into public/shared storage here.
        openExternalVaultFile(
            file = file,
            preferredSamsungPackages = samsungTargets,
            mimeTypeOverride = targetMimeType
        )
    }

    fun shareVaultFile(file: VaultFile) {
        if (!unlocked || busy) {
            if (busy) toast("Another file operation is already running.")
            return
        }
        busy = true
        val operationToken = beginFileOperation("Preparing to share ${file.displayName}")
        val cancelRequested = AtomicBoolean(false)
        fileOperationCancel = { cancelRequested.set(true) }
        fileOperationProgress = VaultFileOperationProgress(
            phase = "Preparing share",
            completedBytes = 0L,
            totalBytes = file.sizeBytes,
            currentFileName = file.displayName,
            currentFileCompletedBytes = 0L,
            currentFileTotalBytes = file.sizeBytes
        )

        scope.launch {
            var prepared: PreparedVaultFile? = null
            var handedOff = false
            try {
                prepared = withContext(Dispatchers.IO) {
                    repository.prepareDecryptedFile(file.id) { progress ->
                        if (cancelRequested.get()) {
                            throw CancellationException("Sharing cancelled.")
                        }
                        publishFileOperationProgress(
                            operationToken,
                            progress.copy(phase = "Preparing share")
                        )
                    }
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch

                val uri = FileProvider.getUriForFile(
                    context, "com.privatespace.android.fileprovider", prepared.file
                )
                val sendIntent = Intent(Intent.ACTION_SEND)
                    .setType(prepared.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                sendIntent.clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
                withContext(Dispatchers.IO) {
                    repository.markOutgoingHandedOff(listOf(checkNotNull(prepared)))
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch
                context.startActivity(Intent.createChooser(sendIntent, "Share file"))
                handedOff = true
            } catch (cancelled: CancellationException) {
                if (unlocked) toast("Sharing cancelled. No share sheet was opened.")
            } catch (failure: Exception) {
                toast(failure.message ?: "The file could not be prepared for sharing.")
            } finally {
                val staged = prepared
                if (!handedOff && staged != null) {
                    val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            repository.discardPreparedOutgoing(listOf(staged))
                        }.exceptionOrNull()
                    }
                    if (cleanupFailure != null) {
                        toast("A temporary decrypted copy could not be removed from private cache.")
                    }
                }
                finishFileOperation(operationToken)
                busy = false
            }
        }
    }

    fun shareWholeTopic(topicId: String) {
        if (!unlocked || busy) {
            if (busy) toast("Another file operation is already running.")
            return
        }
        val topicName = topics.firstOrNull { it.id == topicId }?.name ?: "topic"
        busy = true
        val operationToken = beginFileOperation("Preparing to share $topicName")
        val cancelRequested = AtomicBoolean(false)
        fileOperationCancel = { cancelRequested.set(true) }
        fileOperationProgress = VaultFileOperationProgress(
            phase = "Preparing share",
            completedBytes = 0L,
            totalBytes = 0L,
            totalFiles = 0,
            currentFileName = topicName
        )

        scope.launch {
            var preparedFiles: List<PreparedVaultFile> = emptyList()
            var handedOff = false
            try {
                preparedFiles = withContext(Dispatchers.IO) {
                    repository.prepareTopicShare(topicId) { progress ->
                        if (cancelRequested.get()) {
                            throw CancellationException("Sharing cancelled.")
                        }
                        publishFileOperationProgress(operationToken, progress)
                    }
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch

                val uris = preparedFiles.map { prepared ->
                    FileProvider.getUriForFile(
                        context, "com.privatespace.android.fileprovider", prepared.file
                    )
                }
                val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = ClipData.newUri(context.contentResolver, "Private Space files", uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                sendIntent.clipData = clip
                withContext(Dispatchers.IO) {
                    repository.markOutgoingHandedOff(preparedFiles)
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch
                context.startActivity(Intent.createChooser(sendIntent, "Share topic"))
                handedOff = true
            } catch (cancelled: CancellationException) {
                if (unlocked) toast("Sharing cancelled. No share sheet was opened.")
            } catch (failure: Exception) {
                toast(failure.message ?: "The topic could not be prepared for sharing.")
            } finally {
                if (!handedOff && preparedFiles.isNotEmpty()) {
                    val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            repository.discardPreparedOutgoing(preparedFiles)
                        }.exceptionOrNull()
                    }
                    if (cleanupFailure != null) {
                        toast("Temporary decrypted copies could not be removed from private cache.")
                    }
                }
                finishFileOperation(operationToken)
                busy = false
            }
        }
    }

    fun shareVaultFiles(files: List<VaultFile>) {
        if (files.isEmpty()) return
        if (!unlocked || busy) {
            if (busy) toast("Another file operation is already running.")
            return
        }
        val totalBytes = runCatching {
            files.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
        }.getOrNull() ?: 0L
        busy = true
        val operationToken = beginFileOperation("Preparing to share ${files.size} selected files")
        val cancelRequested = AtomicBoolean(false)
        fileOperationCancel = { cancelRequested.set(true) }
        fileOperationProgress = VaultFileOperationProgress(
            phase = "Preparing share",
            completedBytes = 0L,
            totalBytes = totalBytes,
            totalFiles = files.size,
            currentFileName = files.first().displayName,
            currentFileTotalBytes = files.first().sizeBytes
        )

        scope.launch {
            val preparedFiles = mutableListOf<PreparedVaultFile>()
            var handedOff = false
            var completedBytes = 0L
            try {
                withContext(Dispatchers.IO) {
                    files.forEachIndexed { index, file ->
                        if (cancelRequested.get()) {
                            throw CancellationException("Sharing cancelled.")
                        }
                        val prepared = repository.prepareDecryptedFile(file.id) { progress ->
                            if (cancelRequested.get()) {
                                throw CancellationException("Sharing cancelled.")
                            }
                            val aggregateBytes = runCatching {
                                Math.addExact(completedBytes, progress.completedBytes)
                            }.getOrDefault(Long.MAX_VALUE)
                            publishFileOperationProgress(
                                operationToken,
                                progress.copy(
                                    phase = "Preparing share",
                                    completedBytes = aggregateBytes,
                                    totalBytes = totalBytes,
                                    completedFiles = index,
                                    totalFiles = files.size
                                )
                            )
                        }
                        preparedFiles += prepared
                        completedBytes = runCatching {
                            Math.addExact(completedBytes, file.sizeBytes)
                        }.getOrDefault(Long.MAX_VALUE)
                        publishFileOperationProgress(
                            operationToken,
                            VaultFileOperationProgress(
                                phase = "File prepared for sharing",
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                completedFiles = index + 1,
                                totalFiles = files.size,
                                currentFileName = file.displayName,
                                currentFileCompletedBytes = file.sizeBytes,
                                currentFileTotalBytes = file.sizeBytes
                            )
                        )
                    }
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch

                val uris = preparedFiles.map { prepared ->
                    FileProvider.getUriForFile(
                        context, "com.privatespace.android.fileprovider", prepared.file
                    )
                }
                val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = ClipData.newUri(
                    context.contentResolver,
                    "Private Space selected files",
                    uris.first()
                )
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                sendIntent.clipData = clip
                withContext(Dispatchers.IO) {
                    repository.markOutgoingHandedOff(preparedFiles)
                }
                if (cancelRequested.get()) throw CancellationException("Sharing cancelled.")
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) return@launch
                context.startActivity(Intent.createChooser(sendIntent, "Share selected files"))
                handedOff = true
            } catch (cancelled: CancellationException) {
                if (unlocked) toast("Sharing cancelled. No share sheet was opened.")
            } catch (failure: Exception) {
                toast(failure.message ?: "The selected files could not be prepared for sharing.")
            } finally {
                if (!handedOff && preparedFiles.isNotEmpty()) {
                    val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            repository.discardPreparedOutgoing(preparedFiles)
                        }.exceptionOrNull()
                    }
                    if (cleanupFailure != null) {
                        toast("Temporary decrypted copies could not be removed from private cache.")
                    }
                }
                finishFileOperation(operationToken)
                busy = false
            }
        }
    }

    fun requestFileReleaseConfirmation(file: VaultFile, action: VaultReleaseAction) {
        pendingReleaseConfirmation = PendingVaultRelease(
            action = action,
            displayName = file.displayName,
            fileIds = listOf(file.id)
        )
    }

    fun requestFileSelectionConfirmation(files: List<VaultFile>, action: VaultReleaseAction) {
        if (files.isEmpty()) {
            toast("Select at least one file.")
            return
        }
        pendingReleaseConfirmation = PendingVaultRelease(
            action = action,
            displayName = "${files.size} selected files",
            fileIds = files.map { it.id }
        )
    }

    fun requestTopicReleaseConfirmation(topicId: String, action: VaultReleaseAction) {
        val topic = topics.firstOrNull { it.id == topicId } ?: run {
            toast("This topic is no longer available.")
            return
        }
        pendingReleaseConfirmation = PendingVaultRelease(
            action = action,
            displayName = "topic “${topic.name}”",
            topicId = topic.id
        )
    }

    fun launchSingleFileExport(file: VaultFile) {
        requestFileReleaseConfirmation(file, VaultReleaseAction.EXPORT)
    }

    fun launchTopicExport(topicId: String) {
        requestTopicReleaseConfirmation(topicId, VaultReleaseAction.EXPORT)
    }

    fun launchAllExport() {
        if (topics.isEmpty()) {
            toast("There are no topics to export.")
            return
        }
        pendingReleaseConfirmation = PendingVaultRelease(
            action = VaultReleaseAction.EXPORT,
            displayName = "all topics",
            allTopics = true
        )
    }

    fun confirmPendingRelease() {
        val pending = pendingReleaseConfirmation ?: return
        pendingReleaseConfirmation = null
        when {
            pending.allTopics -> openAllExportPicker()
            pending.topicId != null -> {
                if (pending.action == VaultReleaseAction.SHARE) shareWholeTopic(pending.topicId)
                else openTopicExportPicker(pending.topicId)
            }
            else -> {
                val selected = pending.fileIds.mapNotNull { id ->
                    vaultFiles.firstOrNull { it.id == id }
                }
                if (selected.size != pending.fileIds.size || selected.isEmpty()) {
                    toast("One or more selected files are no longer available.")
                    return
                }
                when (pending.action) {
                    VaultReleaseAction.SHARE -> {
                        if (selected.size == 1) shareVaultFile(selected.single())
                        else shareVaultFiles(selected)
                    }
                    VaultReleaseAction.EXPORT -> {
                        if (selected.size == 1) openSingleFileExportPicker(selected.single())
                        else openSelectedFilesExportPicker(selected)
                    }
                }
            }
        }
    }

    fun confirmRecoverySetupPassword(passwordInput: String) {
        if (busy || !unlocked) return
        recoveryPasswordError = ""
        val secret = passwordInput.toCharArray()
        val authenticationGeneration = activity?.foregroundGeneration
        val operationCancellation = AtomicBoolean(false)
        activeLifecycleCancellation.set(operationCancellation)
        busy = true

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                            error("Too many attempts. Wait for the lockout to end.")
                        }
                        val valid = PasswordManager.verify(context, secret.copyOf())
                        if (!valid) {
                            PasswordManager.recordFailure(context)
                            error("Current password is incorrect.")
                        }
                        PasswordManager.clearFailures(context)
                    }
                }
                if (operationCancellation.get()) return@launch

                result.fold(
                    onSuccess = {
                        if (!VaultSessionPolicy.mayCompleteUnlock(
                                authenticationGeneration,
                                activity?.foregroundGeneration,
                                activity?.isResumedForVault == true
                            )
                        ) {
                            recoveryPasswordError = "Private Space was interrupted. Unlock it again before setting up recovery."
                            return@fold
                        }
                        pendingRecoveryPassword?.fill('\u0000')
                        pendingRecoveryPassword = secret.copyOf()
                        lockUntil = 0L
                        recoveryPasswordError = ""
                        recoverySetupError = ""
                        showRecoveryPasswordPrompt = false
                        showRecoverySetup = true
                    },
                    onFailure = {
                        lockUntil = PasswordManager.lockedUntil(context)
                        recoveryPasswordError = it.message ?: "Could not verify your vault password."
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                secret.fill('\u0000')
                activeLifecycleCancellation.compareAndSet(operationCancellation, null)
                busy = false
            }
        }
    }

    val recoveryConnected = repository.hasActivePersistentStorage()
    val hasSavedRecoveryLocation = repository.persistentStorageUri() != null
    val recoveryNeedsReconnect = hasSavedRecoveryLocation && !recoveryConnected

    val filteredTopics = remember(topics, topicQuery) {
        val query = topicQuery.trim()
        if (query.isEmpty()) topics else topics.filter { it.name.contains(query, ignoreCase = true) }
    }

    if (!unlocked) {
        AuthScreen(
            setup = setup,
            password = password,
            confirmation = confirmation,
            error = authError,
            busy = busy || vaultLockPending,
            lockUntil = lockUntil,
            now = clockNow,
            recoveryFolderSelected = recoveryTreeUri != null,
            restoreProgress = restoreProgress,
            recoveryAvailable = setup || recoveryTreeUri != null,
            onPassword = {
                password = it.take(PasswordManager.MAX_PASSWORD_LENGTH)
                if (authError.isNotBlank()) authError = ""
            },
            onConfirmation = {
                confirmation = it.take(PasswordManager.MAX_PASSWORD_LENGTH)
                if (authError.isNotBlank()) authError = ""
            },
            onRestore = {
                pendingRecoveryPassword?.fill('\u0000')
                pendingRecoveryPassword = null
                showRecoverySetup = false
                recoverySetupError = ""
                restoreProgress = null
                choosingExistingRecovery = true
                runCatching { recoveryFolderLauncher.launch(null) }.onFailure {
                    choosingExistingRecovery = false
                    authError = "Could not open the device folder picker."
                }
            }
        ) {
            if (busy || vaultLockPending || lockUntil > System.currentTimeMillis()) return@AuthScreen
            busy = true
            restoreProgress = null
            authError = ""
            val entered = password
            val confirm = confirmation
            val wasSetup = setup
            val restoreUri = recoveryTreeUri
            val authGeneration = activity?.foregroundGeneration
            val operationCancellation = AtomicBoolean(false)
            val recoveryConflictDetected = AtomicBoolean(false)
            activeLifecycleCancellation.set(operationCancellation)
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            fun ensureAuthenticationActive() {
                                if (operationCancellation.get()) {
                                    throw CancellationException("Authentication cancelled because the vault was locked.")
                                }
                            }
                            ensureAuthenticationActive()
                            when {
                                restoreUri != null -> {
                                    if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                        error("Too many attempts. Wait for the lockout to end.")
                                    }
                                    try {
                                        repository.restorePersistentVault(restoreUri, entered.toCharArray()) { progress ->
                                            ensureAuthenticationActive()
                                            mainHandler.post {
                                                if (!operationCancellation.get()) restoreProgress = progress
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: RecoveryKeyAuthenticationException) {
                                        PasswordManager.recordFailure(context)
                                        throw failure
                                    }
                                    ensureAuthenticationActive()
                                    try {
                                        PasswordManager.set(context, entered.toCharArray())
                                        PasswordManager.clearFailures(context)
                                    } catch (failure: Throwable) {
                                        repository.lockPersistentStorage()
                                        throw failure
                                    }
                                    ensureAuthenticationActive()
                                    true to null
                                }
                                wasSetup -> {
                                    require(entered.length in PasswordManager.MIN_PASSWORD_LENGTH..PasswordManager.MAX_PASSWORD_LENGTH) {
                                        "Use at least " + PasswordManager.MIN_PASSWORD_LENGTH + " characters."
                                    }
                                    require(entered == confirm) { "Passwords do not match." }
                                    PasswordManager.set(context, entered.toCharArray())
                                    ensureAuthenticationActive()
                                    true to entered.toCharArray()
                                }
                                else -> {
                                    if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                        error("Too many attempts. Wait for the lockout to end.")
                                    }
                                    val valid = PasswordManager.verify(context, entered.toCharArray())
                                    ensureAuthenticationActive()
                                    if (!valid) {
                                        PasswordManager.recordFailure(context)
                                        false to null
                                    } else {
                                        PasswordManager.clearFailures(context)
                                        ensureAuthenticationActive()
                                        val savedTree = repository.persistentStorageUri()
                                        if (savedTree == null) {
                                            true to entered.toCharArray()
                                        } else {
                                            try {
                                                repository.openPersistentStorage(savedTree, entered.toCharArray()) { progress ->
                                                    ensureAuthenticationActive()
                                                    mainHandler.post {
                                                        if (!operationCancellation.get()) restoreProgress = progress
                                                    }
                                                }
                                                ensureAuthenticationActive()
                                                true to null
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: VaultRecoveryConflictException) {
                                                ensureAuthenticationActive()
                                                recoveryConflictDetected.set(true)
                                                true to entered.toCharArray()
                                            } catch (_: Exception) {
                                                ensureAuthenticationActive()
                                                true to entered.toCharArray()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (operationCancellation.get()) {
                        result.getOrNull()?.second?.fill('\u0000')
                        return@launch
                    }
                    if (recoveryConflictDetected.get()) recoveryConflict = true
                    busy = false
                    result.fold(
                        onSuccess = { outcome ->
                            val valid = outcome.first
                            val recoveryPassword = outcome.second
                            if (valid) {
                                setup = false
                                recoveryTreeUri = null
                                password = ""
                                confirmation = ""
                                pendingRecoveryPassword?.fill(0.toChar())
                                pendingRecoveryPassword = null
                                val shouldOfferRecoverySetup = VaultSessionPolicy.shouldOfferRecoverySetup(
                                    hasPendingRecoveryPassword = recoveryPassword != null,
                                    restoringExistingRecovery = restoreUri != null,
                                    recoveryStorageAlreadyActive = repository.hasActivePersistentStorage(),
                                    promptDismissed = recoveryPromptPreferences.getBoolean(
                                        RECOVERY_SETUP_PROMPT_DISMISSED,
                                        false
                                    ),
                                    hasSavedRecoveryLocation = repository.persistentStorageUri() != null
                                )
                                if (shouldOfferRecoverySetup) {
                                    pendingRecoveryPassword = recoveryPassword
                                } else {
                                    recoveryPassword?.fill('\u0000')
                                    pendingRecoveryPassword = null
                                }
                                showRecoverySetup = shouldOfferRecoverySetup
                                recoverySetupError = ""
                                choosingExistingRecovery = false
                                if (VaultSessionPolicy.mayCompleteUnlock(
                                        authGeneration,
                                        activity?.foregroundGeneration,
                                        activity?.isResumedForVault == true
                                    )
                                ) {
                                    unlocked = true
                                    authError = ""
                                } else {
                                    repository.lockPersistentStorage()
                                    pendingRecoveryPassword?.fill(0.toChar())
                                    pendingRecoveryPassword = null
                                    showRecoverySetup = false
                                    authError = "Private Space was interrupted. Enter your password again to unlock it."
                                }
                            } else {
                                lockUntil = PasswordManager.lockedUntil(context)
                                password = ""
                                authError = "Incorrect password."
                            }
                        },
                        onFailure = {
                            if (restoreUri != null) {
                                repository.lockPersistentStorage()
                                choosingExistingRecovery = false
                            }
                            lockUntil = PasswordManager.lockedUntil(context)
                            authError = it.message ?: "Authentication failed."
                            password = ""
                            confirmation = ""
                        }
                    )
                } catch (_: CancellationException) {
                    // Expected when the device is locked or the Activity is torn down.
                } finally {
                    activeLifecycleCancellation.compareAndSet(operationCancellation, null)
                    busy = false
                    restoreProgress = null
                }
            }
        }
        return
    }

    // Index files by topic once per vault snapshot; repeated filtering in
    // topic cards and the active-topic view becomes expensive for large vaults.
    val filesByTopic = remember(vaultFiles) {
        vaultFiles.groupBy { it.topicId }
    }
    val activeTopic = remember(topics, activeTopicId) {
        topics.firstOrNull { it.id == activeTopicId }
    }
    // File lists are derived from the authenticated snapshot. Cache sorting and
    // media classification until that snapshot, the active topic, or sort order
    // actually changes; progress-dialog and unrelated UI state should not rescan.
    val activeTopicFiles = remember(filesByTopic, activeTopic?.id, topicFilesNewestFirst) {
        activeTopic?.let { topic ->
            val topicFiles = filesByTopic[topic.id].orEmpty()
            if (topicFilesNewestFirst) VaultFileOrdering.newestFirst(topicFiles)
            else VaultFileOrdering.oldestFirst(topicFiles)
        } ?: emptyList()
    }
    val topicImageFiles = remember(filesByTopic, activeTopic?.id) {
        activeTopic?.let { topic ->
            filesByTopic[topic.id].orEmpty().filter { file ->
                VaultFormatSupport.kindForFile(file.displayName, file.mimeType) ==
                    VaultFormatSupport.FileKind.PHOTO
            }
        } ?: emptyList()
    }
    val activeMedia = remember(topicImageFiles, activeMediaId) {
        activeMediaId?.let { mediaId ->
            topicImageFiles.firstOrNull { it.id == mediaId }
        }
    }

    LaunchedEffect(activeTopicId, topics) {
        if (activeTopicId != null && topics.none { it.id == activeTopicId }) {
            closeMediaViewer()
            activeTopicId = null
        }
    }

    BackHandler(enabled = activeMediaId != null) {
        closeMediaViewer()
    }
    BackHandler(enabled = showTrash) {
        if (!busy) showTrash = false
    }
    BackHandler(enabled = activeTopicId != null && activeMediaId == null) {
        activeTopicId = null
    }

    if (activeMediaId != null) {
        if (activeMedia == null) {
            LaunchedEffect(activeMediaId) {
                closeMediaViewer()
            }
        } else {
            val mediaPosition = topicImageFiles.indexOfFirst { it.id == activeMedia.id }
            VaultMediaViewer(
                file = activeMedia,
                preparedFile = activeMediaFile,
                loading = mediaViewerLoading,
                position = mediaPosition + 1,
                total = topicImageFiles.size,
                canGoPrevious = mediaPosition > 0,
                canGoNext = mediaPosition >= 0 && mediaPosition < topicImageFiles.lastIndex,
                onBack = ::closeMediaViewer,
                onPrevious = {
                    VaultMediaNavigation.adjacent(topicImageFiles, activeMedia.id, -1)?.let(::openVaultFile)
                },
                onNext = {
                    VaultMediaNavigation.adjacent(topicImageFiles, activeMedia.id, 1)?.let(::openVaultFile)
                },
            )
        }
    } else if (showTrash) {
        TrashScreen(
            entries = trashEntries,
            repository = repository,
            trashStorageBytes = storageUsage.trashBytes,
            busy = busy,
            onBack = { if (!busy) showTrash = false },
            onLock = ::lockVault,
            onRestore = { entry ->
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.restoreTrashFile(entry.file.id) }
                    }
                    busy = false
                    result.onSuccess {
                        refreshVault()
                        toast("Restored to “${entry.originalTopic.name}”.")
                    }.onFailure {
                        toast(it.message ?: "Could not restore this file.")
                    }
                }
            },
            onDeletePermanently = { trashEntryToPermanentlyDelete = it }
        )
    } else if (activeTopic != null) {
        TopicScreen(
            topic = activeTopic,
            files = activeTopicFiles,
            newestFirst = topicFilesNewestFirst,
            onNewestFirstChange = { topicFilesNewestFirst = it },
            repository = repository,
            storageBytes = storageUsage.topicBytes[activeTopic.id] ?: 0L,
            busy = busy,
            onBack = { activeTopicId = null },
            onAdd = { launchImport(activeTopic.id) },
            onOpen = ::openVaultFile,
            onShare = { requestFileReleaseConfirmation(it, VaultReleaseAction.SHARE) },
            onExport = ::launchSingleFileExport,
            onDelete = { fileToDelete = it },
            onShareSelected = { requestFileSelectionConfirmation(it, VaultReleaseAction.SHARE) },
            onExportSelected = { requestFileSelectionConfirmation(it, VaultReleaseAction.EXPORT) },
            onShareTopic = { requestTopicReleaseConfirmation(activeTopic.id, VaultReleaseAction.SHARE) },
            onExportTopic = { requestTopicReleaseConfirmation(activeTopic.id, VaultReleaseAction.EXPORT) },
            onDeleteTopic = { topicToDelete = activeTopic },
            imports = emptyList(),
            onPauseImport = ::pauseImport,
            onResumeImport = ::resumeImport,
            onStopImport = ::stopImport,
            onRestartImport = ::restartImport,
            onDismissImport = ::dismissImport
        )
    } else {
        Scaffold(
            containerColor = Ink,
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
                    color = Ink,
                    tonalElevation = 0.dp
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(15.dp),
                                color = Blue.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Lock, contentDescription = "Private Space", tint = Cyan, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "Private Space",
                                    fontWeight = FontWeight.Bold,
                                    color = White,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Green, modifier = Modifier.size(12.dp))
                                    Text("Encrypted", fontSize = 11.sp, color = Green, fontWeight = FontWeight.Medium)
                                }
                            }
                            IconButton(
                                onClick = { showTrash = true },
                                enabled = !busy
                            ) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = if (trashEntries.isEmpty()) "Recycle Bin, empty" else "Recycle Bin, ${trashEntries.size} deleted files",
                                    tint = if (trashEntries.isNotEmpty()) Cyan else Muted
                                )
                            }
                            Box {
                                IconButton(onClick = { topMenuExpanded = true }, enabled = !busy) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Muted)
                                }
                                DropdownMenu(
                                    expanded = topMenuExpanded,
                                    onDismissRequest = { topMenuExpanded = false },
                                    containerColor = Panel
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Vault details") },
                                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, tint = Cyan) },
                                        onClick = {
                                            topMenuExpanded = false
                                            showVaultDetails = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Help & how-to") },
                                        leadingIcon = { Icon(Icons.Default.HelpOutline, contentDescription = null, tint = Cyan) },
                                        onClick = {
                                            topMenuExpanded = false
                                            showHelp = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Security information") },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = {
                                            topMenuExpanded = false
                                            showSecurityInfo = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Change password") },
                                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                        onClick = {
                                            topMenuExpanded = false
                                            showChangePassword = true
                                        }
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = Blue.copy(alpha = 0.24f),
                                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
                            ) {
                                IconButton(onClick = { lockVault() }, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.Lock, contentDescription = "Lock vault", tint = Cyan)
                                }
                            }
                        }
                        HorizontalDivider(color = Line.copy(alpha = 0.55f), thickness = 0.7.dp)
                    }
                }
            },
            floatingActionButton = {
                if (topics.isNotEmpty()) {
                    Button(
                        onClick = { showCreateTopic = true },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Blue,
                            contentColor = White,
                            disabledContainerColor = Panel2,
                            disabledContentColor = Muted
                        )
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("New topic", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0E1728), Ink, Color(0xFF070B13))))
                    .padding(padding),
                contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Your private vault", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${topics.size} ${if (topics.size == 1) "topic" else "topics"} · ${vaultFiles.size} ${if (vaultFiles.size == 1) "file" else "files"}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            color = Green.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(30.dp),
                            border = BorderStroke(1.dp, Green.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Green, modifier = Modifier.size(13.dp))
                                Text("On-device encryption", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Panel,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(38.dp),
                                color = Blue.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Encrypted storage", color = Muted, fontSize = 11.sp)
                                Text(formatBytes(storageUsage.totalBytes), color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { showVaultDetails = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Details")
                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
                item {
                    RecoveryStatusCard(
                        connected = recoveryConnected,
                        needsReconnect = recoveryNeedsReconnect,
                        conflict = recoveryConflict,
                        busy = busy,
                        onSetUp = {
                            recoveryPasswordError = ""
                            showRecoveryPasswordPrompt = true
                        },
                        onReconnect = { showRecoveryReconnectConfirmation = true }
                    )
                }
                if (topics.isNotEmpty()) {
                    item {
                        OutlinedTextField(
                            value = topicQuery,
                            onValueChange = { topicQuery = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            placeholder = { Text("Search topics", color = Muted) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Muted) },
                            trailingIcon = {
                                if (topicQuery.isNotEmpty()) {
                                    IconButton(onClick = { topicQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Muted)
                                    }
                                }
                            }
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Topics", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (topicQuery.isNotBlank()) {
                            Text(
                                "${filteredTopics.size} of ${topics.size} shown",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        } else if (topics.isNotEmpty()) {
                            Surface(color = Panel2, shape = RoundedCornerShape(30.dp)) {
                                Text(
                                    topics.size.toString(),
                                    color = Cyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
                when {
                    topics.isEmpty() -> {
                        item {
                            EmptyTopicsState(onCreate = { showCreateTopic = true })
                        }
                    }
                    filteredTopics.isEmpty() -> {
                        item {
                            EmptySearchState(onClear = { topicQuery = "" })
                        }
                    }
                    else -> {
                        items(filteredTopics, key = { it.id }) { topic ->
                            val topicFiles = filesByTopic[topic.id].orEmpty()
                            val summary = TopicSummary(
                                topic,
                                topicFiles.size,
                                storageUsage.topicBytes[topic.id] ?: 0L
                            )
                            TopicCard(
                                summary = summary,
                                onOpen = { activeTopicId = topic.id },
                                onExport = { launchTopicExport(topic.id) },
                                onDelete = { topicToDelete = topic },
                                busy = busy
                            )
                        }
                    }
                }
            }
        }
    }


    if (showVaultDetails) {
        VaultDetailsDialog(
            topicCount = topics.size,
            fileCount = vaultFiles.size,
            trashFileCount = trashEntries.size,
            trashStorageBytes = storageUsage.trashBytes,
            storageBytes = storageUsage.totalBytes,
            exportEnabled = topics.isNotEmpty() && !busy,
            onExportAll = {
                showVaultDetails = false
                launchAllExport()
            },
            onDismiss = { showVaultDetails = false }
        )
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }

    if (showSecurityInfo) {
        SecurityInfoDialog(onDismiss = { showSecurityInfo = false })
    }



    pendingReleaseConfirmation?.let { pending ->
        val isShare = pending.action == VaultReleaseAction.SHARE
        val itemDescription = when {
            pending.allTopics -> "all topics and their files"
            pending.topicId != null -> "${pending.displayName} and all its files"
            pending.fileIds.size == 1 -> "“${pending.displayName}”"
            else -> pending.displayName
        }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingReleaseConfirmation = null },
            title = {
                Text(
                    if (isShare) "Share outside Private Space?" else "Export outside Private Space?",
                    color = White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isShare) {
                            "You are about to share $itemDescription. The selected content will be made available to the app or recipient you choose."
                        } else {
                            "You are about to export $itemDescription. The exported copies will be saved outside Private Space protection."
                        },
                        color = Muted
                    )
                    Surface(
                        color = Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Icon(
                                if (isShare) Icons.Default.Share else Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = Cyan
                            )
                            Text(
                                "Nothing is shared or exported until you continue and complete the Android share or folder selection.",
                                color = White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = ::confirmPendingRelease,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isShare) "Continue to share" else "Continue to export")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingReleaseConfirmation = null }
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    fileOperationProgress?.let { progress ->
        VaultFileOperationProgressDialog(
            title = fileOperationTitle,
            progress = progress,
            onCancel = { fileOperationCancel?.invoke() }
        )
    }

    if (showRecoveryReconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { showRecoveryReconnectConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Gold) },
            title = { Text("Restore from recovery folder?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This is more than reconnecting a folder. Private Space will lock and ask you to select the recovery folder and enter its password. Restoring replaces the vault currently on this device with the selected recovery copy; newer on-device files are not merged. Continue only if this is the copy you intend to restore.",
                    color = Muted,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecoveryReconnectConfirmation = false
                        lockVault()
                        choosingExistingRecovery = true
                        runCatching { recoveryFolderLauncher.launch(null) }.onFailure {
                            choosingExistingRecovery = false
                            authError = "Could not open the device folder picker."
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Choose recovery folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryReconnectConfirmation = false }) {
                    Text("Keep this vault")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    if (showRecoveryPasswordPrompt) {
        RecoveryPasswordDialog(
            busy = busy,
            error = recoveryPasswordError,
            lockUntil = lockUntil,
            now = clockNow,
            onDismiss = {
                if (!busy) {
                    showRecoveryPasswordPrompt = false
                    recoveryPasswordError = ""
                }
            },
            onSubmit = ::confirmRecoverySetupPassword
        )
    }

    if (showRecoverySetup) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text("Protect your vault across reinstallations", color = White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your vault is encrypted on this device. Choose a folder to create a separate encrypted recovery copy. Without one, uninstalling Private Space or clearing its app data can permanently remove your files. You can set this up later from the Recovery card on your dashboard.",
                        color = Muted
                    )
                    if (recoverySetupError.isNotBlank()) {
                        Surface(color = Red.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                            Text(
                                recoverySetupError,
                                color = Red,
                                modifier = Modifier.padding(11.dp),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && pendingRecoveryPassword != null,
                    onClick = {
                        runCatching { recoveryFolderLauncher.launch(null) }.onFailure {
                            recoverySetupError = "Could not open Android's device folder picker."
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (busy) "Preparing recovery…" else "Choose device folder")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        recoveryPromptPreferences.edit()
                            .putBoolean(RECOVERY_SETUP_PROMPT_DISMISSED, true)
                            .apply()
                        pendingRecoveryPassword?.fill('\u0000')
                        pendingRecoveryPassword = null
                        showRecoverySetup = false
                        recoverySetupError = ""
                        toast("Recovery setup deferred. You can start it later from the dashboard.")
                    }
                ) {
                    Text("Not now", color = Muted)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    if (showCreateTopic) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCreateTopic = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Create a topic", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = topicName,
                        onValueChange = { topicName = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Topic name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = topicName.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching { repository.createTopic(topicName.trim()) }
                            }
                            busy = false
                            result.onSuccess {
                                topicName = ""
                                showCreateTopic = false
                                refreshVault()
                            }.onFailure { toast(it.message ?: "Could not create topic.") }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (busy) "Creating…" else "Create topic")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showCreateTopic = false }
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    topicToDelete?.let { topic ->
        val fileCount = filesByTopic[topic.id].orEmpty().size
        ConfirmDialog(
            title = "Delete topic?",
            message = if (fileCount == 0) {
                "“${topic.name}” is empty and will be removed from your topics."
            } else {
                "“${topic.name}” will be removed from your topics. Its ${fileCount} file(s) will be moved to the Recycle Bin, where you can restore them or permanently delete them."
            },
            confirmLabel = "Delete topic",
            isIrreversible = fileCount == 0,
            onCancel = { topicToDelete = null },
            onConfirm = {
                topicToDelete = null
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.deleteTopic(topic.id) }
                    }
                    busy = false
                    result.onSuccess {
                        if (activeTopicId == topic.id) activeTopicId = null
                        refreshVault()
                        if (fileCount > 0) toast("Topic deleted. Its files are in the Recycle Bin.")
                    }.onFailure { toast(it.message ?: "Could not delete topic.") }
                }
            }
        )
    }

    fileToDelete?.let { file ->
        ConfirmDialog(
            title = "Move file to the Recycle Bin?",
            message = "“${file.displayName}” will be moved to the Recycle Bin. You can restore it later or permanently delete it there. The original file outside Private Space is not affected.",
            confirmLabel = "Move to Recycle Bin",
            isIrreversible = false,
            onCancel = { fileToDelete = null },
            onConfirm = {
                fileToDelete = null
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.deleteFile(file.id) }
                    }
                    busy = false
                    result.onSuccess {
                        refreshVault()
                        toast("Moved to the Recycle Bin. You can restore this file later.")
                    }.onFailure { toast(it.message ?: "Could not move file to the Recycle Bin.") }
                }
            }
        )
    }

    trashEntryToPermanentlyDelete?.let { entry ->
        ConfirmDialog(
            title = "Permanently delete file?",
            message = "The encrypted vault copy of “${entry.file.displayName}” will be removed and cannot be restored through Private Space. Original files and copies shared or exported earlier are unaffected. This is not a guarantee that every physical trace is erased from the device's storage.",
            confirmLabel = "Delete forever",
            onCancel = { trashEntryToPermanentlyDelete = null },
            onConfirm = {
                trashEntryToPermanentlyDelete = null
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.permanentlyDeleteTrashFile(entry.file.id) }
                    }
                    busy = false
                    result.onSuccess {
                        refreshVault()
                        toast("File permanently deleted.")
                    }.onFailure {
                        toast(it.message ?: "Could not permanently delete this file.")
                    }
                }
            }
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            busy = busy,
            onDismiss = { if (!busy) showChangePassword = false }
        ) { oldPassword, newPassword ->
            if (!busy) {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                error("Too many attempts. Wait for the lockout to end.")
                            }
                            val oldSecret = oldPassword.toCharArray()
                            val newSecret = newPassword.toCharArray()
                            try {
                                val valid = PasswordManager.verify(context, oldSecret.copyOf())
                                if (!valid) {
                                    PasswordManager.recordFailure(context)
                                    error("Current password is incorrect.")
                                }
                                PasswordManager.clearFailures(context)
                                repository.rotatePersistentPassword(newSecret.copyOf())
                                try {
                                    PasswordManager.set(context, newSecret.copyOf())
                                } catch (passwordSaveFailure: Throwable) {
                                    // Keep the recovery copy protected by the old
                                    // password if the local password commit fails.
                                    try {
                                        repository.rotatePersistentPassword(oldSecret.copyOf())
                                        repository.finishPersistentPasswordRotation()
                                    } catch (rollbackFailure: Throwable) {
                                        passwordSaveFailure.addSuppressed(rollbackFailure)
                                    }
                                    throw passwordSaveFailure
                                }
                                repository.finishPersistentPasswordRotation()
                            } finally {
                                oldSecret.fill(Char.MIN_VALUE)
                                newSecret.fill(Char.MIN_VALUE)
                            }
                        }
                    }
                    busy = false
                    result.onSuccess {
                        showChangePassword = false
                        toast("Password changed.")
                    }.onFailure {
                        lockUntil = PasswordManager.lockedUntil(context)
                        toast(it.message ?: "Password could not be changed.")
                        if (lockUntil > System.currentTimeMillis()) lockVault()
                    }
                }
            }
        }
    }

    pendingFilePickerTargets?.let { targets ->
        FilePickerChoiceDialog(
            targets = targets,
            onDismiss = {
                pendingFilePickerTargets = null
                pendingImportTopic = null
            },
            onChoice = { target, rememberChoice ->
                pendingFilePickerTargets = null
                if (!launchFilePickerTarget(target, rememberChoice)) {
                    toast("Could not open ${target.label}. Opening Android's document picker instead.")
                    launchSystemDocumentPicker()
                }
            }
        )
    }

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
}

@Composable
private fun FilePickerChoiceDialog(
    targets: List<FilePickerTarget>,
    onDismiss: () -> Unit,
    onChoice: (FilePickerTarget, Boolean) -> Unit
) {
    var selectedIndex by remember(targets) { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Select files with",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    targets.forEachIndexed { index, target ->
                        val selected = selectedIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Blue.copy(alpha = 0.16f) else Panel2)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { selectedIndex = index }
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (selected) Cyan else Muted
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    target.label,
                                    color = White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = Cyan
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onChoice(targets[selectedIndex], false) },
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use once", color = White)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = Muted)
                }
                TextButton(onClick = { onChoice(targets[selectedIndex], true) }) {
                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remember", color = Cyan)
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = Panel,
        titleContentColor = White,
        textContentColor = Muted,
        tonalElevation = 0.dp
    )
}

@Composable
private fun AuthScreen(
    setup: Boolean,
    password: String,
    confirmation: String,
    error: String,
    busy: Boolean,
    lockUntil: Long,
    now: Long,
    recoveryFolderSelected: Boolean,
    restoreProgress: VaultRestoreProgress?,
    recoveryAvailable: Boolean,
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
    onRestore: () -> Unit,
    onSubmit: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmationVisible by remember { mutableStateOf(false) }
    val locked = lockUntil > now
    val secondsRemaining = ((lockUntil - now).coerceAtLeast(0L) + 999L) / 1_000L

    LaunchedEffect(busy) {
        if (busy) {
            passwordVisible = false
            confirmationVisible = false
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF101D32), Ink, Color(0xFF070B13))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(68.dp),
                shape = RoundedCornerShape(22.dp),
                color = Blue.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Cyan, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Private Space", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, Line.copy(alpha = 0.9f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Text(
                        if (setup) "Create your vault password" else "Welcome back",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPassword,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(if (setup) "Create password" else "Password") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                enabled = !busy
                            ) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = Muted
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (setup) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = onConfirmation,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            label = { Text("Confirm password") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { confirmationVisible = !confirmationVisible },
                                    enabled = !busy
                                ) {
                                    Icon(
                                        if (confirmationVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (confirmationVisible) "Hide confirmation" else "Show confirmation",
                                        tint = Muted
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        Text(
                            "At least " + PasswordManager.MIN_PASSWORD_LENGTH + " characters. A longer passphrase is stronger.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        if (confirmation.isNotEmpty() && confirmation != password) {
                            Text(
                                "Passwords do not match.",
                                color = Red,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (error.isNotBlank()) {
                        Surface(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                            color = Red.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Red)
                                Spacer(Modifier.width(8.dp))
                                Text(error, color = Red, fontSize = 13.sp)
                            }
                        }
                    }
                    if (locked) {
                        Surface(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = Gold.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Gold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Too many attempts. Try again in " + secondsRemaining + " seconds.",
                                    color = Gold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Blue.copy(alpha = 0.09f),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Line.copy(alpha = 0.75f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Your on-device vault is encrypted with Android Keystore. This password unlocks the app and protects an optional recovery copy. There is no password reset.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !busy && !locked && password.isNotEmpty() &&
                            (!setup || (
                                password.length >= PasswordManager.MIN_PASSWORD_LENGTH &&
                                    password == confirmation
                            )),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Securing your vault…")
                        } else {
                            Icon(
                                if (setup) Icons.Default.Shield else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    setup -> "Create secure vault"
                                    recoveryFolderSelected -> "Restore encrypted vault"
                                    else -> "Unlock vault"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (restoreProgress != null) {
                        val progress = restoreProgress
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Blue.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(13.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(17.dp),
                                        strokeWidth = 2.dp,
                                        color = Cyan
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Text("Restoring encrypted vault", color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                                if (progress.totalBytes > 0L) {
                                    LinearProgressIndicator(
                                        progress = progress.fraction,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Cyan,
                                        trackColor = Line
                                    )
                                    Text(
                                        "${(progress.fraction * 100f).toInt()}%  ·  ${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}",
                                        color = Muted,
                                        fontSize = 11.sp
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Cyan,
                                        trackColor = Line
                                    )
                                }
                                Text(
                                    "Files: ${progress.completedFiles} / ${progress.totalFiles}",
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    progress.currentFileName,
                                    color = Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (recoveryAvailable) {
                        TextButton(
                            onClick = onRestore,
                            enabled = !busy && !locked,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (recoveryFolderSelected) "Choose a different recovery folder"
                                else "Restore an existing encrypted vault"
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Developed by: Amir Malik (2026)",
                color = Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VaultDetailsDialog(
    topicCount: Int,
    fileCount: Int,
    trashFileCount: Int,
    trashStorageBytes: Long,
    storageBytes: Long,
    exportEnabled: Boolean,
    onExportAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            shape = RoundedCornerShape(26.dp),
            color = Panel,
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = Green.copy(alpha = 0.13f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Green, modifier = Modifier.size(23.dp))
                        }
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Vault details", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("On-device encrypted storage", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close vault details", tint = Muted)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewMetric(Modifier.weight(1f), "Topics", topicCount.toString(), Icons.Default.Folder)
                    OverviewMetric(Modifier.weight(1f), "Active files", fileCount.toString(), Icons.Default.Description)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Panel2,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Cyan, modifier = Modifier.size(19.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (trashFileCount == 1) "Recycle Bin · 1 deleted file" else "Recycle Bin · " + trashFileCount + " deleted files",
                                color = White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (trashFileCount > 0) {
                                    formatBytes(trashStorageBytes) + " remains encrypted and is included in storage used until permanently deleted."
                                } else {
                                    "No deleted files. Storage used reflects files in your active topics."
                                },
                                color = Muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF223652).copy(alpha = 0.72f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, White.copy(alpha = 0.05f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(38.dp), color = Cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Storage used", color = Muted, fontSize = 12.sp)
                            Text(formatBytes(storageBytes), color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                FilledTonalButton(
                    onClick = onExportAll,
                    enabled = exportEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Export all")
                }
            }
        }
    }
}

@Composable
private fun OverviewMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF223652).copy(alpha = 0.72f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, White.copy(alpha = 0.05f))
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                color = Cyan.copy(alpha = 0.12f),
                shape = RoundedCornerShape(11.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecoveryStatusCard(
    connected: Boolean,
    needsReconnect: Boolean,
    conflict: Boolean,
    busy: Boolean,
    onSetUp: () -> Unit,
    onReconnect: () -> Unit
) {
    val accent = if (connected) Green else Gold
    val title = when {
        connected -> "Encrypted recovery copy connected"
        conflict -> "Recovery copy differs from this device"
        needsReconnect -> "Recovery folder needs attention"
        else -> "No recovery copy configured"
    }
    val description = when {
        connected ->
            "A separate encrypted copy is connected to your selected device-storage folder. Keep that folder available and remember the password used to protect it. Recovery is not cloud sync."
        conflict ->
            "The on-device vault and recovery copy contain different changes. Automatic restore was stopped to protect your local files. Your on-device vault remains unchanged. Restoring the recovery copy replaces it, so do that only when you have confirmed it is the version you want."
        needsReconnect ->
            "The saved recovery location cannot currently be opened. Your on-device vault is still available. Restoring from that folder replaces the current on-device vault; newer local files are not merged."
        else ->
            "Your vault is encrypted on this device, but no reinstall recovery copy is set up. Uninstalling the app or clearing its data can permanently remove these files."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (connected) Icons.Default.VerifiedUser else Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        title,
                        color = White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Text(
                        description,
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            if (!connected) {
                Button(
                    onClick = if (needsReconnect) onReconnect else onSetUp,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (needsReconnect) Icons.Default.FolderOpen else Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(if (needsReconnect) "Restore from recovery folder" else "Set up encrypted recovery")
                }
            }
        }
    }
}

@Composable
private fun RecoveryPasswordDialog(
    busy: Boolean,
    error: String,
    lockUntil: Long,
    now: Long,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val locked = lockUntil > now
    val secondsRemaining = ((lockUntil - now).coerceAtLeast(0L) + 999L) / 1_000L

    LaunchedEffect(busy) {
        if (busy) passwordVisible = false
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = Cyan) },
        title = { Text("Confirm your vault password", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Confirm the password you use to unlock Private Space. It will protect the recovery key; the password itself is not written into the recovery folder.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !locked,
                    label = { Text("Current vault password") },
                    singleLine = true,
                    isError = error.isNotBlank(),
                    shape = RoundedCornerShape(13.dp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy && !locked) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = Red,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                if (locked) {
                    Text(
                        "Too many attempts. Try again in $secondsRemaining seconds.",
                        color = Gold,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && !locked && password.isNotEmpty(),
                onClick = {
                    val submitted = password
                    password = ""
                    onSubmit(submitted)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(7.dp))
                    Text("Verifying…")
                } else {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Continue")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel
    )
}

@Composable
private fun EmptyTopicsState(onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(68.dp),
                shape = RoundedCornerShape(22.dp),
                color = Blue.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Cyan, modifier = Modifier.size(32.dp))
                }
            }
            Text("Start with a topic", color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Button(onClick = onCreate, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Create topic")
            }
        }
    }
}

@Composable
private fun EmptySearchState(onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(Icons.Default.SearchOff, contentDescription = null, tint = Muted, modifier = Modifier.size(36.dp))
        Text("No matching topics", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        TextButton(onClick = onClear) {
            Icon(Icons.Default.Backspace, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Clear search")
        }
    }
}


@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filteredArticles = remember(query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            privateSpaceHelpArticles
        } else {
            privateSpaceHelpArticles.filter { article ->
                article.title.contains(normalizedQuery, ignoreCase = true) ||
                    article.body.contains(normalizedQuery, ignoreCase = true) ||
                    article.keywords.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(26.dp),
            color = Panel,
            border = BorderStroke(1.dp, Line)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = Blue.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text("Help & how-to", color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Guides for using your vault", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close help", tint = Muted)
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Search recovery, files, sharing…", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Muted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear help search", tint = Muted)
                            }
                        }
                    }
                )
                Text(
                    if (query.isBlank()) "${filteredArticles.size} help topics"
                    else "${filteredArticles.size} matching topics",
                    color = Muted,
                    fontSize = 11.sp
                )
                if (filteredArticles.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SearchOff, contentDescription = null, tint = Muted, modifier = Modifier.size(34.dp))
                            Text("No matching help topics", color = White, fontWeight = FontWeight.SemiBold)
                            Text("Try a different word, such as recovery or export.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TextButton(onClick = { query = "" }) { Text("Show all help topics") }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredArticles, key = { it.id }) { article ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Panel2,
                                shape = RoundedCornerShape(15.dp),
                                border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(13.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(article.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(article.body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = Green) },
        title = { Text("About your vault", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SecurityInfoRow(
                    icon = Icons.Default.Lock,
                    title = "Encrypted on this device",
                    body = "Your files and vault index are encrypted. The encryption key is protected by Android Keystore."
                )
                SecurityInfoRow(
                    icon = Icons.Default.Share,
                    title = "Opening, sharing and exporting",
                    body = "Opening a file, sharing it or exporting it makes a temporary decrypted copy available to another app or destination. Only sharing and exporting require an extra confirmation. An external app may retain or copy a file it receives; those copies are no longer protected by this vault."
                )
                SecurityInfoRow(
                    icon = Icons.Default.Warning,
                    title = "Password and recovery",
                    body = "Your password unlocks the app and protects the optional encrypted recovery key. It is not the Android Keystore key used for files on this device. To restore after reinstalling, keep the recovery folder and the password used to protect it. There is no password reset or cloud backup."
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Got it")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel
    )
}

@Composable
private fun SecurityInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.padding(top = 2.dp).size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun TopicCard(
    summary: TopicSummary,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean
) {
    var menuExpanded by remember(summary.topic.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121F33)),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.72f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(17.dp),
                color = Blue.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = Cyan, modifier = Modifier.size(27.dp))
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    summary.topic.name,
                    color = White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        summary.fileCount.toString() + if (summary.fileCount == 1) " file" else " files",
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("·", color = Muted, fontSize = 12.sp)
                    Text(formatBytes(summary.totalBytes), color = Muted, fontSize = 12.sp)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for " + summary.topic.name, tint = Muted)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Panel
                ) {
                    DropdownMenuItem(
                        text = { Text("Open topic") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Export topic") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Delete topic", color = Red) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Red) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicScreen(
    topic: VaultTopic,
    files: List<VaultFile>,
    newestFirst: Boolean,
    onNewestFirstChange: (Boolean) -> Unit,
    repository: VaultRepository,
    storageBytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (VaultFile) -> Unit,
    onShare: (VaultFile) -> Unit,
    onExport: (VaultFile) -> Unit,
    onDelete: (VaultFile) -> Unit,
    onShareSelected: (List<VaultFile>) -> Unit,
    onExportSelected: (List<VaultFile>) -> Unit,
    onShareTopic: () -> Unit,
    onExportTopic: () -> Unit,
    onDeleteTopic: () -> Unit,
    imports: List<VaultImportQueueItem> = emptyList(),
    onPauseImport: (String) -> Unit = {},
    onResumeImport: (String) -> Unit = {},
    onStopImport: (String) -> Unit = {},
    onRestartImport: (String) -> Unit = {},
    onDismissImport: (String) -> Unit = {}
) {
    var selectionMode by remember(topic.id) { mutableStateOf(false) }
    var selectedFileIds by remember(topic.id) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFilter by remember(topic.id) { mutableStateOf(VaultFormatSupport.TopicFilter.ALL) }
    var topicMenuExpanded by remember(topic.id) { mutableStateOf(false) }
    var showTopicDetails by remember(topic.id) { mutableStateOf(false) }
    var sortMenuExpanded by remember(topic.id) { mutableStateOf(false) }
    var fileQuery by remember(topic.id) { mutableStateOf("") }
    val selectedFiles = remember(files, selectedFileIds) {
        files.filter { it.id in selectedFileIds }
    }
    val filteredFiles = remember(files, selectedFilter, fileQuery) {
        val query = fileQuery.trim()
        files.filter(selectedFilter::matches).filter { file ->
            query.isEmpty() ||
                file.displayName.contains(query, ignoreCase = true) ||
                file.mimeType.contains(query, ignoreCase = true)
        }
    }
    val visibleFileIds = remember(filteredFiles) {
        filteredFiles.mapTo(mutableSetOf()) { it.id }
    }
    val allVisibleFilesSelected = remember(visibleFileIds, selectedFileIds) {
        visibleFileIds.isNotEmpty() && visibleFileIds.all { it in selectedFileIds }
    }
    val selectedFilesHiddenByCurrentFilter = remember(visibleFileIds, selectedFileIds) {
        selectedFileIds.count { it !in visibleFileIds }
    }

    LaunchedEffect(files) {
        val currentIds = files.map { it.id }.toSet()
        selectedFileIds = selectedFileIds.intersect(currentIds)
        if (selectedFileIds.isEmpty()) selectionMode = false
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
                color = Ink
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to topics", tint = White)
                        }
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Blue.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = Cyan, modifier = Modifier.size(23.dp))
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                topic.name,
                                color = White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "PRIVATE TOPIC",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                maxLines = 1
                            )
                        }
                        Box {
                            IconButton(onClick = { topicMenuExpanded = true }, enabled = !busy) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Topic actions", tint = Muted)
                            }
                            DropdownMenu(
                                expanded = topicMenuExpanded,
                                onDismissRequest = { topicMenuExpanded = false },
                                containerColor = Panel
                            ) {
                                DropdownMenuItem(
                                    enabled = files.isNotEmpty() && !busy,
                                    text = { Text("Share topic") },
                                    leadingIcon = { Icon(Icons.Default.FolderShared, contentDescription = null, tint = Color(0xFF80C8FF)) },
                                    onClick = { topicMenuExpanded = false; onShareTopic() }
                                )
                                DropdownMenuItem(
                                    enabled = files.isNotEmpty() && !busy,
                                    text = { Text("Export topic") },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFFFFC078)) },
                                    onClick = { topicMenuExpanded = false; onExportTopic() }
                                )
                                DropdownMenuItem(
                                    text = { Text("View details") },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFBCA7FF)) },
                                    onClick = { topicMenuExpanded = false; showTopicDetails = true }
                                )
                                HorizontalDivider(color = Line)
                                DropdownMenuItem(
                                    enabled = !busy,
                                    text = { Text("Delete topic", color = Red) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Red) },
                                    onClick = { topicMenuExpanded = false; onDeleteTopic() }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Line.copy(alpha = 0.65f), thickness = 0.7.dp)
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Button(
                    onClick = onAdd,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Blue,
                        contentColor = White,
                        disabledContainerColor = Panel2,
                        disabledContentColor = Muted
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add files", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF101C30), Ink, Color(0xFF070B13))))
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your files", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        if (selectionMode) "${selectedFiles.size} selected" else "${files.size} ${if (files.size == 1) "file" else "files"} · ${formatBytes(storageBytes)} encrypted",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
                TextButton(
                    enabled = !busy && files.isNotEmpty(),
                    onClick = {
                        selectionMode = !selectionMode
                        selectedFileIds = emptySet()
                    }
                ) {
                    Icon(
                        if (selectionMode) Icons.Default.Close else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (selectionMode) "Cancel" else "Select")
                }
            }

            if (files.isNotEmpty()) {
                OutlinedTextField(
                    value = fileQuery,
                    onValueChange = { fileQuery = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Search files by name or type", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Muted) },
                    trailingIcon = {
                        if (fileQuery.isNotEmpty()) {
                            IconButton(onClick = { fileQuery = "" }, enabled = !busy) {
                                Icon(Icons.Default.Close, contentDescription = "Clear file search", tint = Muted)
                            }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VaultFormatSupport.TopicFilter.values().forEach { filter ->
                    val selected = selectedFilter == filter
                    val filterIcon = when (filter) {
                        VaultFormatSupport.TopicFilter.ALL -> Icons.Default.Folder
                        VaultFormatSupport.TopicFilter.DOCUMENTS -> Icons.Default.Description
                        VaultFormatSupport.TopicFilter.PHOTOS -> Icons.Default.Image
                        VaultFormatSupport.TopicFilter.VIDEOS -> Icons.Default.Movie
                    }
                    val filterIconTint = when (filter) {
                        VaultFormatSupport.TopicFilter.ALL -> Color(0xFF9DABFF)
                        VaultFormatSupport.TopicFilter.DOCUMENTS -> Color(0xFF78B7FF)
                        VaultFormatSupport.TopicFilter.PHOTOS -> Color(0xFFFF8EB5)
                        VaultFormatSupport.TopicFilter.VIDEOS -> Color(0xFFFFB86B)
                    }
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { selectedFilter = filter }
                            ),
                        color = if (selected) Blue.copy(alpha = 0.22f) else Panel,
                        shape = RoundedCornerShape(50.dp),
                        border = BorderStroke(
                            if (selected) 1.4.dp else 1.dp,
                            if (selected) Cyan.copy(alpha = 0.8f) else Line
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(filterIcon, contentDescription = null, tint = filterIconTint, modifier = Modifier.size(16.dp))
                            Text(
                                filter.title,
                                color = if (selected) White else Muted,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Showing ${filteredFiles.size} of ${files.size} files",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { sortMenuExpanded = true }, enabled = filteredFiles.isNotEmpty()) {
                        Icon(Icons.Default.Sort, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (newestFirst) "Newest first" else "Oldest first", color = Cyan, fontSize = 12.sp)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Muted)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        containerColor = Panel
                    ) {
                        DropdownMenuItem(
                            text = { Text("Newest first", color = White) },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Cyan) },
                            trailingIcon = { if (newestFirst) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Cyan) },
                            onClick = { onNewestFirstChange(true); sortMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Oldest first", color = White) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Cyan) },
                            trailingIcon = { if (!newestFirst) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Cyan) },
                            onClick = { onNewestFirstChange(false); sortMenuExpanded = false }
                        )
                    }
                }
            }

            if (selectionMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Panel,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${selectedFiles.size} of ${files.size} selected",
                                color = White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                enabled = !busy && filteredFiles.isNotEmpty(),
                                onClick = {
                                    selectedFileIds = if (allVisibleFilesSelected) {
                                        selectedFileIds - visibleFileIds
                                    } else {
                                        selectedFileIds + visibleFileIds
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (allVisibleFilesSelected) "Clear shown" else "Select shown")
                            }
                        }
                        if (selectedFilesHiddenByCurrentFilter > 0) {
                            Text(
                                selectedFilesHiddenByCurrentFilter.toString() + " selected " +
                                    (if (selectedFilesHiddenByCurrentFilter == 1) "file is" else "files are") +
                                    " hidden by the current search or filter. Share and Export still include all selected files.",
                                color = Gold,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = selectedFiles.isNotEmpty() && !busy,
                                onClick = { onShareSelected(selectedFiles) },
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share (${selectedFiles.size})", maxLines = 1, softWrap = false, fontSize = 12.sp)
                            }
                            Button(
                                enabled = selectedFiles.isNotEmpty() && !busy,
                                onClick = { onExportSelected(selectedFiles) },
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export (${selectedFiles.size})", maxLines = 1, softWrap = false, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Line.copy(alpha = 0.8f))
            if (imports.isEmpty() && filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(70.dp),
                            color = Panel2,
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (fileQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = Cyan,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        Text(
                            when {
                                files.isEmpty() -> "Nothing here yet"
                                fileQuery.isNotBlank() -> "No matching files"
                                else -> "No ${selectedFilter.title.lowercase()}"
                            },
                            color = White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            when {
                                files.isEmpty() -> "Add documents, photos or videos to this topic. Your originals stay where they are."
                                fileQuery.isNotBlank() -> "Try another file name or MIME type, or clear the search."
                                else -> "This topic has no files in the selected filter. Choose All to see every file."
                            },
                            color = Muted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        if (fileQuery.isNotBlank() || selectedFilter != VaultFormatSupport.TopicFilter.ALL) {
                            TextButton(
                                onClick = {
                                    fileQuery = ""
                                    selectedFilter = VaultFormatSupport.TopicFilter.ALL
                                },
                                enabled = !busy
                            ) {
                                Icon(Icons.Default.FilterAltOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Clear search and filters")
                            }
                        } else if (files.isEmpty()) {
                            FilledTonalButton(onClick = onAdd, enabled = !busy) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add files")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(
                        imports,
                        key = { "import-${it.id}" },
                        contentType = { "import" }
                    ) { item ->
                        VaultImportQueueRow(
                            item = item,
                            onPause = { onPauseImport(item.id) },
                            onResume = { onResumeImport(item.id) },
                            onStop = { onStopImport(item.id) },
                            onRestart = { onRestartImport(item.id) },
                            onDismiss = { onDismissImport(item.id) }
                        )
                    }
                    items(
                        filteredFiles,
                        key = { it.id },
                        contentType = { "vault-file" }
                    ) { file ->
                        VaultFileRow(
                            file = file,
                            repository = repository,
                            onOpen = { onOpen(file) },
                            onShare = { onShare(file) },
                            onExport = { onExport(file) },
                            onDelete = { onDelete(file) },
                            busy = busy,
                            selectionMode = selectionMode,
                            selected = file.id in selectedFileIds,
                            onToggleSelection = {
                                selectedFileIds = if (file.id in selectedFileIds) {
                                    selectedFileIds - file.id
                                } else {
                                    selectedFileIds + file.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    if (showTopicDetails) {
        TopicDetailsDialog(
            fileCount = files.size,
            storageBytes = storageBytes,
            updatedAt = topic.updatedAt,
            onDismiss = { showTopicDetails = false }
        )
    }

}

@Composable
private fun VaultFileOperationProgressDialog(
    title: String,
    progress: VaultFileOperationProgress,
    onCancel: () -> Unit
) {
    val hasDeterminateProgress = progress.totalBytes > 0L
    val percent = if (hasDeterminateProgress) (progress.fraction * 100f).toInt() else null
    val isImportFinalizing = progress.phase.equals("Finalizing encrypted file", ignoreCase = true)
    val hasFilesAfterCurrent = progress.totalFiles > 1 &&
        progress.completedFiles < progress.totalFiles - 1
    val securityGuidance = when {
        title.startsWith("Exporting", ignoreCase = true) ->
            "Exported copies are ordinary files outside Private Space protection. Anyone with access to the destination may be able to read them."
        title.startsWith("Preparing to share", ignoreCase = true) ->
            "The receiving app or person may retain or copy shared files. Those copies are outside Private Space protection."
        title.startsWith("Opening", ignoreCase = true) ->
            "A temporary decrypted copy is prepared for the viewer you choose. The vault copy remains encrypted, but another app may retain its copy."
        title.startsWith("Preparing selected file", ignoreCase = true) ||
            title.startsWith("Preparing ", ignoreCase = true) ->
            "Files are copied into this topic and encrypted before they are saved. Originals are not moved or modified. Cancel stops remaining files; an item already being safely finalized may still finish."
        else -> null
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = BorderStroke(1.dp, Line)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Cyan
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            progress.phase,
                            color = Cyan,
                            fontSize = 12.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                securityGuidance?.let { guidance ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(11.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                guidance,
                                color = White,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                if (hasDeterminateProgress) {
                    LinearProgressIndicator(
                        progress = progress.fraction,
                        modifier = Modifier.fillMaxWidth(),
                        color = Cyan,
                        trackColor = Panel2
                    )
                    Text(
                        "${percent}%  ·  ${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}${if (title.startsWith("Exporting")) " work" else ""}",
                        color = White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Cyan,
                        trackColor = Panel2
                    )
                    Text("Preparing operation…", color = Muted, fontSize = 12.sp)
                }
                if (progress.totalFiles > 1) {
                    Text(
                        "Files: ${progress.completedFiles} / ${progress.totalFiles}",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
                if (progress.currentFileName.isNotBlank()) {
                    Text(
                        progress.currentFileName,
                        color = White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (progress.currentFileTotalBytes > 0L) {
                        val fileFraction = (
                            progress.currentFileCompletedBytes.toDouble() /
                                progress.currentFileTotalBytes.toDouble()
                            ).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = fileFraction,
                            modifier = Modifier.fillMaxWidth(),
                            color = Green,
                            trackColor = Panel2
                        )
                        Text(
                            "${formatBytes(progress.currentFileCompletedBytes)} / ${formatBytes(progress.currentFileTotalBytes)}",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when {
                        isImportFinalizing && hasFilesAfterCurrent -> {
                            TextButton(onClick = onCancel) {
                                Text("Stop after this file", color = Red, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        isImportFinalizing -> {
                            Text(
                                "Finishing securely — this file can no longer be cancelled",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        else -> {
                            TextButton(onClick = onCancel) {
                                Text("Cancel operation", color = Red, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultImportQueueRow(
    item: VaultImportQueueItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    var menuExpanded by remember(item.id) { mutableStateOf(false) }
    val fraction = item.totalBytes?.takeIf { it > 0L }?.let {
        (item.completedBytes.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    val statusLabel = when (item.status) {
        VaultImportStatus.QUEUED -> "Waiting"
        VaultImportStatus.RUNNING -> "Encrypting"
        VaultImportStatus.PAUSED -> "Paused"
        VaultImportStatus.FINALIZING -> "Finalizing encrypted file"
        VaultImportStatus.COMPLETED -> "Added securely"
        VaultImportStatus.FAILED -> "Failed"
        VaultImportStatus.STOPPED -> "Stopped"
    }
    val statusColor = when (item.status) {
        VaultImportStatus.COMPLETED -> Green
        VaultImportStatus.FAILED, VaultImportStatus.STOPPED -> Red
        VaultImportStatus.PAUSED -> Gold
        else -> Cyan
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (item.status) {
                        VaultImportStatus.COMPLETED -> Icons.Default.CheckCircle
                        VaultImportStatus.FAILED, VaultImportStatus.STOPPED -> Icons.Default.ErrorOutline
                        VaultImportStatus.PAUSED -> Icons.Default.PauseCircleOutline
                        else -> Icons.Default.Lock
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.displayName,
                        color = White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusLabel,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = item.status != VaultImportStatus.FINALIZING) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Import actions for ${item.displayName}", tint = Muted)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = Panel
                    ) {
                        when (item.status) {
                            VaultImportStatus.QUEUED, VaultImportStatus.RUNNING -> {
                                DropdownMenuItem(
                                    text = { Text("Pause") },
                                    leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                                    onClick = { menuExpanded = false; onPause() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Stop", color = Red) },
                                    leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, tint = Red) },
                                    onClick = { menuExpanded = false; onStop() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Restart from beginning") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Cyan) },
                                    onClick = { menuExpanded = false; onRestart() }
                                )
                            }
                            VaultImportStatus.PAUSED -> {
                                DropdownMenuItem(
                                    text = { Text("Resume") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Cyan) },
                                    onClick = { menuExpanded = false; onResume() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Stop", color = Red) },
                                    leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, tint = Red) },
                                    onClick = { menuExpanded = false; onStop() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Restart from beginning") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Cyan) },
                                    onClick = { menuExpanded = false; onRestart() }
                                )
                            }
                            VaultImportStatus.FAILED, VaultImportStatus.STOPPED -> {
                                DropdownMenuItem(
                                    text = { Text("Restart") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Cyan) },
                                    onClick = { menuExpanded = false; onRestart() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Dismiss") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = { menuExpanded = false; onDismiss() }
                                )
                            }
                            VaultImportStatus.FINALIZING -> Unit
                            VaultImportStatus.COMPLETED -> {
                                DropdownMenuItem(
                                    text = { Text("Dismiss") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = { menuExpanded = false; onDismiss() }
                                )
                            }
                        }
                    }
                }
            }
            if (item.status != VaultImportStatus.QUEUED &&
                item.status != VaultImportStatus.STOPPED &&
                item.status != VaultImportStatus.FAILED
            ) {
                if (fraction == null && item.status != VaultImportStatus.COMPLETED) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan)
                } else {
                    LinearProgressIndicator(
                        progress = if (item.status == VaultImportStatus.COMPLETED || item.status == VaultImportStatus.FINALIZING) 1f else fraction ?: 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = statusColor,
                        trackColor = Panel2
                    )
                }
            }
            if (item.totalBytes != null && item.totalBytes > 0L) {
                Text(
                    "${fraction?.let { "${(it * 100f).toInt()}%  ·  " } ?: ""}${formatBytes(item.completedBytes)} of ${formatBytes(item.totalBytes)}",
                    color = Muted,
                    fontSize = 12.sp
                )
            } else if (item.completedBytes > 0L) {
                Text("${formatBytes(item.completedBytes)} processed", color = Muted, fontSize = 12.sp)
            }
            item.error?.let { message ->
                Text(message, color = Red, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TopicDetailsDialog(
    fileCount: Int,
    storageBytes: Long,
    updatedAt: Long,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Cyan, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text("Topic details", color = White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(
                    Triple(Icons.Default.Description, "Files", fileCount.toString()),
                    Triple(Icons.Default.Storage, "Encrypted storage", formatBytes(storageBytes)),
                    Triple(Icons.Default.Schedule, "Last updated", formatDate(updatedAt))
                ).forEachIndexed { index, detail ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Panel2,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                detail.first,
                                contentDescription = null,
                                tint = when (index) {
                                    0 -> Color(0xFF78B7FF)
                                    1 -> Cyan
                                    else -> Color(0xFFBCA7FF)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(detail.second, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(detail.third, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Cyan) } },
        shape = RoundedCornerShape(22.dp),
        containerColor = Panel
    )
}


private data class ViewerImageLoadState(
    val loading: Boolean = true,
    val bitmap: Bitmap? = null
)

@Composable
private fun VaultMediaViewer(
    file: VaultFile,
    preparedFile: PreparedVaultFile?,
    loading: Boolean,
    position: Int,
    total: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val isPhoto = VaultFormatSupport.kindForFile(file.displayName, file.mimeType) ==
        VaultFormatSupport.FileKind.PHOTO
    val privateMediaFile = preparedFile?.file?.takeIf { it.isFile }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetSizePx = with(density) {
        maxOf(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx())
            .coerceIn(1024, 4096)
    }
    val swipeThresholdPx = with(density) { 72.dp.toPx() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to topic", tint = White)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.displayName, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (isPhoto) "PHOTO" else "VIDEO"} · ${position.coerceAtLeast(1)} OF ${total.coerceAtLeast(1)}",
                    color = Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp
                )
            }
            IconButton(onClick = onPrevious, enabled = canGoPrevious) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous media", tint = if (canGoPrevious) White else Muted.copy(alpha = 0.35f))
            }
            IconButton(onClick = onNext, enabled = canGoNext) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next media", tint = if (canGoNext) White else Muted.copy(alpha = 0.35f))
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .pointerInput(file.id, canGoPrevious, canGoNext) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                        onDragEnd = {
                            when {
                                totalDrag <= -swipeThresholdPx && canGoNext -> onNext()
                                totalDrag >= swipeThresholdPx && canGoPrevious -> onPrevious()
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator(color = Cyan)
                privateMediaFile == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Muted, modifier = Modifier.size(38.dp))
                        Text("This media item could not be loaded.", color = Muted, fontSize = 13.sp)
                    }
                }
                isPhoto -> {
                    // A bitmap is a native allocation. If the viewer is closed or
                    // switched to another item while decoding is in progress,
                    // withContext may discard the result after the decoder returns.
                    // Keep temporary ownership here until the Compose state accepts it.
                    val pendingViewerBitmap = remember(privateMediaFile.absolutePath, targetSizePx) {
                        AtomicReference<Bitmap?>(null)
                    }
                    DisposableEffect(pendingViewerBitmap) {
                        onDispose {
                            pendingViewerBitmap.getAndSet(null)?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                    val imageState by produceState(
                        initialValue = ViewerImageLoadState(),
                        privateMediaFile.absolutePath,
                        targetSizePx
                    ) {
                        value = try {
                            val decoded = withContext(Dispatchers.IO) {
                                decodeViewerImage(privateMediaFile, targetSizePx)
                                    .also(pendingViewerBitmap::set)
                            }
                            // withContext can throw cancellation after its IO block
                            // completes. Check before transferring bitmap ownership
                            // to Compose so late results are always reclaimed.
                            currentCoroutineContext().ensureActive()
                            if (decoded != null) pendingViewerBitmap.compareAndSet(decoded, null)
                            ViewerImageLoadState(loading = false, bitmap = decoded)
                        } catch (cancelled: CancellationException) {
                            pendingViewerBitmap.getAndSet(null)?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                            throw cancelled
                        } catch (_: OutOfMemoryError) {
                            pendingViewerBitmap.getAndSet(null)?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                            ViewerImageLoadState(loading = false)
                        } catch (_: Exception) {
                            pendingViewerBitmap.getAndSet(null)?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                            ViewerImageLoadState(loading = false)
                        }
                    }
                    DisposableEffect(imageState.bitmap) {
                        val bitmap = imageState.bitmap
                        onDispose {
                            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                    when {
                        imageState.loading -> CircularProgressIndicator(color = Cyan)
                        imageState.bitmap == null -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = null,
                                    tint = Muted,
                                    modifier = Modifier.size(38.dp)
                                )
                                Text(
                                    "This photo format could not be displayed on this device.",
                                    color = Muted,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                TextButton(onClick = onBack) {
                                    Text("Back to topic")
                                }
                            }
                        }
                        else -> {
                            val bitmap = imageState.bitmap
                            if (bitmap != null && !bitmap.isRecycled) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = file.displayName,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text("This photo is no longer available in the viewer.", color = Muted)
                            }
                        }
                    }
                }
                else -> Text("Unsupported media type.", color = Muted)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF101722),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Photo", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(file.sizeBytes), color = Muted, fontSize = 11.sp)
                }
                Text(
                    when {
                        !canGoPrevious && !canGoNext -> "1 item"
                        else -> "Swipe to browse"
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun decodeViewerImage(source: File, targetMaxDimension: Int): Bitmap? =
    VaultThumbnailPolicy.decodeSampledBitmap(
        source = source,
        targetSizePx = targetMaxDimension.coerceIn(1024, 2048)
    )

@Composable
private fun rememberVaultMediaPreview(
    file: VaultFile,
    repository: VaultRepository,
    enabled: Boolean = true
): VaultMediaPreview? {
    val targetSizePx = with(LocalDensity.current) { 128.dp.roundToPx().coerceAtLeast(64) }
    val isPreviewable =
        VaultFormatSupport.kindForFile(file.displayName, file.mimeType) != VaultFormatSupport.FileKind.DOCUMENT
    val previewState = remember(file.id, file.mimeType, targetSizePx, repository) {
        mutableStateOf<VaultMediaPreview?>(null)
    }
    // Busy state should defer a new preview, not cancel one already decoding.
    // Otherwise ordinary exports/shares repeatedly decrypt and decode the same
    // visible thumbnails each time the operation flag toggles.
    val latestEnabled = rememberUpdatedState(enabled)
    // A decoder may finish after the owning LazyColumn row is cancelled. Keep a
    // thread-safe handoff so a bitmap returned to a cancelled effect is recycled
    // instead of becoming an unowned native allocation.
    val pendingPreview = remember(file.id, file.mimeType, targetSizePx, repository) {
        AtomicReference<VaultMediaPreview?>(null)
    }
    val preview = previewState.value
    LaunchedEffect(file.id, file.mimeType, targetSizePx, repository) {
        if (isPreviewable) {
            snapshotFlow { latestEnabled.value }.first { it }
            if (previewState.value == null) {
                try {
                    val loadedPreview = withContext(Dispatchers.IO) {
                        repository.loadMediaPreview(file, targetSizePx).also(pendingPreview::set)
                    }
                    previewState.value = loadedPreview
                    // Ownership now belongs to previewState/DisposableEffect.
                    pendingPreview.set(null)
                } catch (cancelled: CancellationException) {
                    pendingPreview.getAndSet(null)?.bitmap?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    throw cancelled
                } catch (_: Exception) {
                    pendingPreview.getAndSet(null)?.bitmap?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        }
    }
    DisposableEffect(preview?.bitmap) {
        val bitmap = preview?.bitmap
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    return preview
}

@Composable
private fun VaultThumbnail(
    file: VaultFile,
    preview: VaultMediaPreview?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector,
    isImage: Boolean,
    isVideo: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (isImage) Green.copy(alpha = 0.13f) else Blue.copy(alpha = 0.15f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = preview?.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Preview of ${file.displayName}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    fallbackIcon,
                    contentDescription = when {
                        isVideo -> "Video"
                        isImage -> "Photo"
                        else -> "Document"
                    },
                    tint = if (isImage) Green else Cyan,
                    modifier = Modifier.size(23.dp)
                )
            }
            if (isVideo) {
                Box(
                    modifier = Modifier.align(Alignment.Center)
                        .size(25.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(50.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultFileRow(
    file: VaultFile,
    repository: VaultRepository,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    var menuExpanded by remember(file.id) { mutableStateOf(false) }
    val fileKind = VaultFormatSupport.kindForFile(file.displayName, file.mimeType)
    val isPhoto = fileKind == VaultFormatSupport.FileKind.PHOTO
    val isVideo = fileKind == VaultFormatSupport.FileKind.VIDEO
    val mediaPreview = rememberVaultMediaPreview(file, repository, enabled = !busy)
    val extension = file.displayName.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
    val fileIcon = when {
        isPhoto -> Icons.Default.Image
        isVideo -> Icons.Default.Movie
        extension == "PDF" -> Icons.Default.PictureAsPdf
        extension in setOf("DOC", "DOCX", "ODT", "RTF") -> Icons.Default.Article
        extension in setOf("XLS", "XLSX", "ODS", "CSV") -> Icons.Default.TableChart
        extension in setOf("PPT", "PPTX", "ODP") -> Icons.Default.Slideshow
        extension in setOf("ZIP", "7Z", "RAR", "TAR", "GZ") -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(
            enabled = !busy,
            role = if (selectionMode) Role.Checkbox else Role.Button
        ) {
            if (selectionMode) onToggleSelection() else onOpen()
        },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Panel2 else Color(0xFF192840)
        ),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) Cyan.copy(alpha = 0.85f) else White.copy(alpha = 0.035f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    enabled = !busy,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Cyan,
                        checkmarkColor = Ink,
                        uncheckedColor = Muted
                    )
                )
            }
            VaultThumbnail(
                file = file,
                preview = mediaPreview,
                modifier = Modifier.size(46.dp),
                fallbackIcon = fileIcon,
                isImage = isPhoto,
                isVideo = isVideo
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.displayName, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(extension)
                    add(formatBytes(file.sizeBytes))
                    if (isVideo) mediaPreview?.durationMs?.let { add(formatDuration(it)) }
                }.joinToString("  ·  ")
                Text(details, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selectionMode) Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for " + file.displayName, tint = Muted)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Panel
                ) {
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Open file") },
                        leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(
                        enabled = !busy,
                        text = { Text("Move to Recycle Bin", color = Red) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Red) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}


@Composable
private fun TrashScreen(
    entries: List<VaultTrashEntry>,
    repository: VaultRepository,
    trashStorageBytes: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onRestore: (VaultTrashEntry) -> Unit,
    onDeletePermanently: (VaultTrashEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.file.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    entry.originalTopic.name.contains(normalizedQuery, ignoreCase = true) ||
                    entry.file.mimeType.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF101C30), Ink, Color(0xFF070B13))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Ink.copy(alpha = 0.96f),
            tonalElevation = 0.dp
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to vault", tint = White)
                    }
                    Surface(
                        modifier = Modifier.size(43.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Blue.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Cyan, modifier = Modifier.size(23.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Recycle Bin",
                            color = White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            "Review and recover deleted files",
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = Blue.copy(alpha = 0.24f),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.18f))
                    ) {
                        IconButton(onClick = onLock, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock vault", tint = Cyan)
                        }
                    }
                }
                HorizontalDivider(color = Line.copy(alpha = 0.65f), thickness = 0.7.dp)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Line.copy(alpha = 0.9f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "DELETED FILES",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    entries.size.toString(),
                                    color = White,
                                    fontSize = 30.sp,
                                    lineHeight = 34.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (entries.size == 1) "item" else "items",
                                    color = Muted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                        Surface(
                            color = Cyan.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.22f))
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = Cyan, modifier = Modifier.size(14.dp))
                                    Text("STORAGE HELD", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                }
                                Text(
                                    formatBytes(trashStorageBytes),
                                    color = White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Line.copy(alpha = 0.7f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Green, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                        Text(
                            "Deleted files remain encrypted and continue using vault storage until permanently removed. Restore returns a file to its original topic.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel2,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Gold, modifier = Modifier.padding(top = 1.dp).size(17.dp))
                    Text(
                        "Delete forever removes only the Private Space copy. Original files and copies shared or exported earlier are unaffected. It is not a forensic secure-erase guarantee.",
                        color = White,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            if (entries.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Search by file name, topic or type", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Muted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, enabled = !busy) {
                                Icon(Icons.Default.Close, contentDescription = "Clear Recycle Bin search", tint = Muted)
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (query.isBlank()) {
                            "Showing all " + entries.size + " deleted files"
                        } else {
                            filteredEntries.size.toString() + " of " + entries.size + " deleted files match"
                        },
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = Cyan)
                    }
                }
            } else if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan, trackColor = Panel2)
            }

            when {
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.padding(horizontal = 18.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(70.dp),
                                shape = RoundedCornerShape(22.dp),
                                color = Panel2
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Muted, modifier = Modifier.size(34.dp))
                                }
                            }
                            Text("Your Recycle Bin is clear", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "Files you move to the Recycle Bin will appear here. You can restore them or permanently remove the vault copy.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                filteredEntries.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SearchOff, contentDescription = null, tint = Muted, modifier = Modifier.size(38.dp))
                            Text("No matching deleted files", color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                "Try a different file name, topic name or file type.",
                                color = Muted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = { query = "" }, enabled = !busy) {
                                Text("Clear search")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredEntries, key = { it.file.id }, contentType = { "recycle-bin-file" }) { entry ->
                            TrashFileRow(
                                entry = entry,
                                repository = repository,
                                enabled = !busy,
                                onRestore = { onRestore(entry) },
                                onDeletePermanently = { onDeletePermanently(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashFileRow(
    entry: VaultTrashEntry,
    repository: VaultRepository,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    val file = entry.file
    val fileKind = VaultFormatSupport.kindForFile(file.displayName, file.mimeType)
    val isPhoto = fileKind == VaultFormatSupport.FileKind.PHOTO
    val isVideo = fileKind == VaultFormatSupport.FileKind.VIDEO
    val mediaPreview = rememberVaultMediaPreview(file, repository, enabled = enabled)
    val extension = file.displayName.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF192840)),
        border = BorderStroke(1.dp, White.copy(alpha = 0.035f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VaultThumbnail(
                    file = file,
                    preview = mediaPreview,
                    modifier = Modifier.size(50.dp),
                    fallbackIcon = when {
                        isPhoto -> Icons.Default.Image
                        isVideo -> Icons.Default.Movie
                        else -> Icons.Default.InsertDriveFile
                    },
                    isImage = isPhoto,
                    isVideo = isVideo
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(file.displayName, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val details = buildList {
                        add(extension)
                        add(formatBytes(file.sizeBytes))
                        if (isVideo) mediaPreview?.durationMs?.let { add(formatDuration(it)) }
                    }.joinToString("  ·  ")
                    Text(details, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("From ${entry.originalTopic.name}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Deleted ${formatDate(entry.deletedAt)}", color = Muted, fontSize = 10.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRestore, enabled = enabled) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(17.dp), tint = Cyan)
                    Spacer(Modifier.width(5.dp))
                    Text("Restore", color = Cyan)
                }
                TextButton(onClick = onDeletePermanently, enabled = enabled) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(17.dp), tint = Red)
                    Spacer(Modifier.width(5.dp))
                    Text("Delete forever", color = Red)
                }
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (String, String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var oldVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }
    var confirmationVisible by remember { mutableStateOf(false) }

    LaunchedEffect(busy) {
        if (busy) {
            oldVisible = false
            newVisible = false
            confirmationVisible = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Change password", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Current password") },
                    visualTransformation = if (oldVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { oldVisible = !oldVisible }, enabled = !busy) {
                            Icon(
                                if (oldVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (oldVisible) "Hide current password" else "Show current password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp)
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("New password") },
                    visualTransformation = if (newVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { newVisible = !newVisible }, enabled = !busy) {
                            Icon(
                                if (newVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (newVisible) "Hide new password" else "Show new password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp)
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Confirm new password") },
                    visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmationVisible = !confirmationVisible }, enabled = !busy) {
                            Icon(
                                if (confirmationVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (confirmationVisible) "Hide confirmation" else "Show confirmation"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    isError = confirmation.isNotEmpty() && newPassword != confirmation
                )
                if (confirmation.isNotEmpty() && newPassword != confirmation) {
                    Text("Passwords do not match.", color = Red, fontSize = 12.sp)
                } else if (newPassword.isNotEmpty() && newPassword.length < PasswordManager.MIN_PASSWORD_LENGTH) {
                    Text("Use at least " + PasswordManager.MIN_PASSWORD_LENGTH + " characters.", color = Muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy &&
                    oldPassword.isNotEmpty() &&
                    newPassword.length >= PasswordManager.MIN_PASSWORD_LENGTH &&
                    newPassword == confirmation,
                onClick = { onChange(oldPassword, newPassword) },
                shape = RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(if (busy) "Saving…" else "Save password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete permanently",
    isIrreversible: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var confirmationSubmitted by remember(title, message, confirmLabel) {
        mutableStateOf(false)
    }

    AlertDialog(
        onDismissRequest = { if (!confirmationSubmitted) onCancel() },
        icon = {
            Icon(
                if (isIrreversible) Icons.Default.Warning else Icons.Default.DeleteOutline,
                contentDescription = null,
                tint = if (isIrreversible) Red else Gold
            )
        },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                message,
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                color = Muted,
                lineHeight = 19.sp
            )
        },
        confirmButton = {
            Button(
                enabled = !confirmationSubmitted,
                onClick = {
                    if (!confirmationSubmitted) {
                        confirmationSubmitted = true
                        onConfirm()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isIrreversible) Red else Gold,
                    contentColor = Ink
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    if (isIrreversible) Icons.Default.DeleteForever else Icons.Default.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !confirmationSubmitted,
                onClick = { if (!confirmationSubmitted) onCancel() }
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel
    )
}

private fun formatDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1_048_576 -> String.format("%.1f KB", value / 1024.0)
    value < 1_073_741_824 -> String.format("%.1f MB", value / 1_048_576.0)
    else -> String.format("%.2f GB", value / 1_073_741_824.0)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
