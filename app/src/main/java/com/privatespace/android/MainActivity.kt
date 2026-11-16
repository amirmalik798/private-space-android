package com.privatespace.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.data.*
import com.privatespace.android.security.PasswordManager
import com.privatespace.android.security.VaultSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

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

class MainActivity : ComponentActivity() {
    var suppressAutoLockForPicker: Boolean = false
    var onVaultShouldLock: (() -> Unit)? = null
    var isResumedForVault: Boolean = false
        private set
    var foregroundGeneration: Long = 0L
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        isResumedForVault = true
    }

    override fun onPause() {
        isResumedForVault = false
        foregroundGeneration = Math.addExact(foregroundGeneration, 1L)
        super.onPause()
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            onVaultShouldLock?.invoke()
        }
    }

    override fun onStop() {
        isResumedForVault = false
        super.onStop()
        if (VaultSessionPolicy.shouldLockOnStop(
                isFinishing = isFinishing,
                isChangingConfigurations = isChangingConfigurations,
                suppressAutoLockForPicker = suppressAutoLockForPicker
            )
        ) {
            onVaultShouldLock?.invoke()
        }
    }
}

private data class FilePickerTarget(
    val label: String,
    val component: ComponentName,
    val intent: Intent
)

private const val FILE_PICKER_PREFERENCES = "private_space_ui"
private const val FILE_PICKER_PREFERENCE = "preferred_import_file_picker_component"

private enum class VaultReleaseAction { SHARE, EXPORT }

private data class PendingVaultRelease(
    val action: VaultReleaseAction,
    val displayName: String,
    val fileIds: List<String> = emptyList(),
    val topicId: String? = null,
    val allTopics: Boolean = false
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
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!PasswordManager.configured(context)) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var recoveryTreeUri by remember { mutableStateOf<Uri?>(null) }
    var choosingExistingRecovery by remember { mutableStateOf(false) }
    var pendingRecoveryPassword by remember { mutableStateOf<CharArray?>(null) }
    var showRecoverySetup by remember { mutableStateOf(false) }
    var recoverySetupError by remember { mutableStateOf("") }
    var lockUntil by remember { mutableLongStateOf(PasswordManager.lockedUntil(context)) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var topics by remember { mutableStateOf<List<VaultTopic>>(emptyList()) }
    var vaultFiles by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var trashEntries by remember { mutableStateOf<List<VaultTrashEntry>>(emptyList()) }
    var storageUsage by remember { mutableStateOf(VaultStorageUsage()) }
    var activeTopicId by remember { mutableStateOf<String?>(null) }
    var activeMediaId by remember { mutableStateOf<String?>(null) }
    var activeMediaFile by remember { mutableStateOf<PreparedVaultFile?>(null) }
    var mediaViewerLoading by remember { mutableStateOf(false) }
    var mediaRequestToken by remember { mutableLongStateOf(0L) }
    var pendingOpenFile by remember { mutableStateOf<VaultFile?>(null) }
    var topicQuery by remember { mutableStateOf("")}
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
    var pendingFilePickerTargets by remember { mutableStateOf<List<FilePickerTarget>?>(null) }
    var pendingExportFile by remember { mutableStateOf<String?>(null) }
    var pendingExportTopic by remember { mutableStateOf<String?>(null) }
    var pendingExportAll by remember { mutableStateOf(false) }
    var pendingExportSelection by remember { mutableStateOf<List<String>?>(null) }
    var pendingReleaseConfirmation by remember { mutableStateOf<PendingVaultRelease?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
        repository.lockPersistentStorage()
        pendingRecoveryPassword?.fill('\u0000')
        pendingRecoveryPassword = null
        recoveryTreeUri = null
        choosingExistingRecovery = false
        showRecoverySetup = false
        recoverySetupError = ""
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
        pendingOpenFile = null
        topicQuery = ""
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
        pendingImportTopic = null
        pendingFilePickerTargets = null
        pendingExportFile = null
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = null
        pendingReleaseConfirmation = null
        activity?.suppressAutoLockForPicker = false
    }

    DisposableEffect(activity) {
        activity?.onVaultShouldLock = { lockVault() }
        onDispose { activity?.onVaultShouldLock = null }
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

    fun handleImportUris(selectedUris: List<Uri>) {
        activity?.suppressAutoLockForPicker = false
        pendingFilePickerTargets = null
        val topicId = pendingImportTopic
        pendingImportTopic = null
        if (topicId != null && selectedUris.isNotEmpty() && unlocked) {
            scope.launch {
                busy = true
                val results = withContext(Dispatchers.IO) {
                    selectedUris.map { uri -> runCatching { repository.addFile(topicId, uri) } }
                }
                refreshVault()
                busy = false
                val succeeded = results.count { it.isSuccess }
                val failed = results.size - succeeded
                toast(
                    when {
                        failed == 0 -> "$succeeded file(s) added to the encrypted vault."
                        succeeded == 0 -> "No files were added. Check file access and available storage."
                        else -> "$succeeded file(s) added; $failed could not be imported."
                    }
                )
            }
        } else if (topicId != null && selectedUris.isNotEmpty()) {
            toast("The vault was locked. Unlock it before importing files.")
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
        ActivityResultContracts.OpenDocumentTree()
    ) { selectedTreeUri ->
        activity?.suppressAutoLockForPicker = false
        if (selectedTreeUri == null) {
            if (choosingExistingRecovery) {
                choosingExistingRecovery = false
                authError = "Choose the folder containing your existing Private Space Vault."
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
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { repository.enablePersistentStorage(selectedTreeUri, secret) }
                        }
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
                            toast("Encrypted recovery storage is ready. Your vault can now be restored after reinstalling Private Space.")
                        }.onFailure {
                            recoverySetupError = it.message ?: "Could not prepare encrypted recovery storage."
                            toast(recoverySetupError)
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
        activity?.suppressAutoLockForPicker = false
        val fileId = pendingExportFile
        pendingExportFile = null
        if (destination != null && fileId != null && unlocked) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.exportToUri(fileId, destination) }
                }
                busy = false
                result.onSuccess { toast("File exported. The exported copy is outside Private Space protection.") }
                    .onFailure { toast(it.message ?: "Export failed.") }
            }
        } else if (destination != null && fileId != null) {
            runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
            toast("The vault was locked. Unlock it and start the export again.")
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { destination ->
        activity?.suppressAutoLockForPicker = false
        val topicId = pendingExportTopic
        val allTopics = pendingExportAll
        val selectedFileIds = pendingExportSelection
        pendingExportTopic = null
        pendingExportAll = false
        pendingExportSelection = null
        if (destination != null && unlocked) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = DocumentFile.fromTreeUri(context, destination)
                            ?: error("The selected folder is unavailable.")
                        when {
                            allTopics -> repository.exportAllToFolder(folder)
                            selectedFileIds != null -> repository.exportFilesToFolder(selectedFileIds, folder)
                            else -> repository.exportTopicToFolder(
                                topicId ?: error("No topic was selected for export."),
                                folder
                            )
                        }
                    }
                }
                busy = false
                result.onSuccess {
                    toast("Export complete. Exported files are outside Private Space protection.")
                }.onFailure {
                    toast(it.message ?: "Export failed. Check the selected folder.")
                }
            }
        } else if (destination != null) {
            toast("The vault was locked. Unlock it and start the export again.")
        }
    }

    fun launchSystemDocumentPicker() {
        activity?.suppressAutoLockForPicker = true
        runCatching { importLauncher.launch(arrayOf("*/*")) }.onFailure {
            activity?.suppressAutoLockForPicker = false
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

        activity?.suppressAutoLockForPicker = true
        return runCatching {
            filePickerLauncher.launch(Intent(target.intent))
            true
        }.getOrElse {
            filePickerPreferences.edit().remove(FILE_PICKER_PREFERENCE).apply()
            activity?.suppressAutoLockForPicker = false
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
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFileLauncher.launch(file.displayName) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingExportFile = null
            toast("Could not open the export picker.")
        }
    }

    fun openTopicExportPicker(topicId: String) {
        pendingExportFile = null
        pendingExportSelection = null
        pendingExportTopic = topicId
        pendingExportAll = false
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            activity?.suppressAutoLockForPicker = false
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
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            activity?.suppressAutoLockForPicker = false
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
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingExportAll = false
            toast("Could not open the folder picker.")
        }
    }

    fun openExternalVaultFile(file: VaultFile) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.prepareDecryptedFile(file.id) }
            }
            busy = false
            result.onSuccess { prepared ->
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) {
                    runCatching { prepared.file.parentFile?.deleteRecursively() }
                    return@onSuccess
                }
                try {
                    val uri = FileProvider.getUriForFile(
                        context, "com.privatespace.android.fileprovider", prepared.file
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, prepared.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    viewIntent.clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
                    context.startActivity(viewIntent)
                } catch (failure: Exception) {
                    runCatching { prepared.file.parentFile?.deleteRecursively() }
                    toast("No compatible app could open this file.")
                }
            }.onFailure {
                toast(it.message ?: "The file could not be decrypted or opened.")
            }
        }
    }


    fun openVaultFile(file: VaultFile) {
        val isMedia = VaultFormatSupport.isImageMimeType(file.mimeType) ||
            VaultFormatSupport.isVideoMimeType(file.mimeType)
        if (!isMedia) {
            // Documents need a separate, explicit consent step before plaintext
            // is handed to another app through FileProvider.
            pendingOpenFile = file
            return
        }

        if (activeTopicId != file.topicId || vaultFiles.none { it.id == file.id }) {
            toast("This media item is no longer available in the open topic.")
            return
        }

        mediaRequestToken += 1L
        val requestToken = mediaRequestToken
        discardViewerFile(activeMediaFile)
        activeMediaFile = null
        activeMediaId = file.id
        mediaViewerLoading = true

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.prepareMediaForViewing(file.id) }
            }

            if (requestToken != mediaRequestToken) {
                result.getOrNull()?.let(::discardViewerFile)
                return@launch
            }

            mediaViewerLoading = false
            result.fold(
                onSuccess = { prepared ->
                    val stillAvailable = unlocked &&
                        activeTopicId == file.topicId &&
                        vaultFiles.any { it.id == file.id } &&
                        VaultSessionPolicy.mayReleasePlaintext(
                            unlocked,
                            activity?.isResumedForVault == true
                        )
                    if (stillAvailable) {
                        activeMediaFile = prepared
                    } else {
                        discardViewerFile(prepared)
                        activeMediaId = null
                    }
                },
                onFailure = {
                    activeMediaId = null
                    toast(it.message ?: "The media could not be decrypted for viewing.")
                }
            )
        }
    }
    fun shareVaultFile(file: VaultFile) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.prepareDecryptedFile(file.id) }
            }
            busy = false
            result.onSuccess { prepared ->
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) {
                    runCatching { prepared.file.parentFile?.deleteRecursively() }
                    return@onSuccess
                }
                try {
                    val uri = FileProvider.getUriForFile(
                        context, "com.privatespace.android.fileprovider", prepared.file
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND)
                        .setType(prepared.mimeType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    sendIntent.clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
                    context.startActivity(Intent.createChooser(sendIntent, "Share file"))
                } catch (failure: Exception) {
                    runCatching { prepared.file.parentFile?.deleteRecursively() }
                    toast("Could not open the Android share sheet.")
                }
            }.onFailure {
                toast(it.message ?: "The file could not be prepared for sharing.")
            }
        }
    }

    fun shareWholeTopic(topicId: String) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.prepareTopicShare(topicId) }
            }
            busy = false
            result.onSuccess { preparedFiles ->
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) {
                    runCatching { preparedFiles.firstOrNull()?.file?.parentFile?.deleteRecursively() }
                    return@onSuccess
                }
                try {
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
                    context.startActivity(Intent.createChooser(sendIntent, "Share topic"))
                } catch (_: Exception) {
                    preparedFiles.firstOrNull()?.file?.parentFile?.deleteRecursively()
                    toast("Could not prepare the topic for sharing.")
                }
            }.onFailure {
                toast(it.message ?: "The topic could not be prepared for sharing.")
            }
        }
    }

    fun shareVaultFiles(files: List<VaultFile>) {
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prepared = mutableListOf<PreparedVaultFile>()
                    try {
                        files.forEach { file -> prepared += repository.prepareDecryptedFile(file.id) }
                        prepared
                    } catch (failure: Throwable) {
                        prepared.forEach { item ->
                            runCatching { item.file.parentFile?.deleteRecursively() }
                        }
                        throw failure
                    }
                }
            }
            busy = false
            result.onSuccess { preparedFiles ->
                if (!VaultSessionPolicy.mayReleasePlaintext(
                        unlocked,
                        activity?.isResumedForVault == true
                    )
                ) {
                    preparedFiles.forEach { item ->
                        runCatching { item.file.parentFile?.deleteRecursively() }
                    }
                    return@onSuccess
                }
                try {
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
                    context.startActivity(Intent.createChooser(sendIntent, "Share selected files"))
                } catch (_: Exception) {
                    preparedFiles.forEach { item ->
                        runCatching { item.file.parentFile?.deleteRecursively() }
                    }
                    toast("Could not prepare the selected files for sharing.")
                }
            }.onFailure {
                toast(it.message ?: "The selected files could not be prepared for sharing.")
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
            busy = busy,
            lockUntil = lockUntil,
            now = clockNow,
            recoveryFolderSelected = recoveryTreeUri != null,
            recoveryAvailable = setup || recoveryTreeUri != null,
            onPassword = { password = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
            onConfirmation = { confirmation = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
            onRestore = {
                choosingExistingRecovery = true
                activity?.suppressAutoLockForPicker = true
                runCatching { recoveryFolderLauncher.launch(null) }.onFailure {
                    choosingExistingRecovery = false
                    activity?.suppressAutoLockForPicker = false
                    authError = "Could not open the device folder picker."
                }
            }
        ) {
            if (busy || lockUntil > System.currentTimeMillis()) return@AuthScreen
            busy = true
            authError = ""
            val entered = password
            val confirm = confirmation
            val wasSetup = setup
            val restoreUri = recoveryTreeUri
            val authGeneration = activity?.foregroundGeneration
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            restoreUri != null -> {
                                if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                    error("Too many attempts. Wait for the lockout to end.")
                                }
                                try {
                                    repository.restorePersistentVault(restoreUri, entered.toCharArray())
                                } catch (failure: Throwable) {
                                    PasswordManager.recordFailure(context)
                                    throw failure
                                }
                                PasswordManager.set(context, entered.toCharArray())
                                PasswordManager.clearFailures(context)
                                true to null
                            }
                            wasSetup -> {
                                require(entered.length in PasswordManager.MIN_PASSWORD_LENGTH..PasswordManager.MAX_PASSWORD_LENGTH) {
                                    "Use at least " + PasswordManager.MIN_PASSWORD_LENGTH + " characters."
                                }
                                require(entered == confirm) { "Passwords do not match." }
                                PasswordManager.set(context, entered.toCharArray())
                                true to entered.toCharArray()
                            }
                            else -> {
                                if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                    error("Too many attempts. Wait for the lockout to end.")
                                }
                                val valid = PasswordManager.verify(context, entered.toCharArray())
                                if (!valid) {
                                    PasswordManager.recordFailure(context)
                                    false to null
                                } else {
                                    PasswordManager.clearFailures(context)
                                    val savedTree = repository.persistentStorageUri()
                                    if (savedTree == null) {
                                        true to entered.toCharArray()
                                    } else {
                                        try {
                                            repository.openPersistentStorage(savedTree, entered.toCharArray())
                                            true to null
                                        } catch (_: Exception) {
                                            // Local encrypted records remain intact. Let the
                                            // user select a new recovery folder and migrate them.
                                            true to entered.toCharArray()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                            pendingRecoveryPassword = recoveryPassword
                            showRecoverySetup = recoveryPassword != null
                            recoverySetupError = ""
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
                        lockUntil = PasswordManager.lockedUntil(context)
                        authError = it.message ?: "Authentication failed."
                        password = ""
                        confirmation = ""
                    }
                )
            }
        }
        return
    }


    val activeTopic = topics.firstOrNull { it.id == activeTopicId }
    val activeTopicFiles = activeTopic?.let { topic ->
        vaultFiles.filter { it.topicId == topic.id }
    } ?: emptyList()
    val topicMediaFiles = activeTopic?.let { topic ->
        VaultMediaNavigation.mediaFilesInTopic(vaultFiles, topic.id)
    } ?: emptyList()
    val activeMedia = activeMediaId?.let { mediaId ->
        topicMediaFiles.firstOrNull { it.id == mediaId }
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
    BackHandler(enabled = activeTopicId != null && activeMediaId == null) {
        activeTopicId = null
    }

    if (activeMediaId != null) {
        if (activeMedia == null) {
            LaunchedEffect(activeMediaId) {
                closeMediaViewer()
            }
        } else {
            val mediaPosition = topicMediaFiles.indexOfFirst { it.id == activeMedia.id }
            VaultMediaViewer(
                file = activeMedia,
                preparedFile = activeMediaFile,
                loading = mediaViewerLoading,
                position = mediaPosition + 1,
                total = topicMediaFiles.size,
                canGoPrevious = mediaPosition > 0,
                canGoNext = mediaPosition >= 0 && mediaPosition < topicMediaFiles.lastIndex,
                onBack = ::closeMediaViewer,
                onPrevious = {
                    VaultMediaNavigation.adjacent(topicMediaFiles, activeMedia.id, -1)?.let(::openVaultFile)
                },
                onNext = {
                    VaultMediaNavigation.adjacent(topicMediaFiles, activeMedia.id, 1)?.let(::openVaultFile)
                },
                onPlaybackError = { toast("This video format or codec is not supported by this device.") }
            )
        }
    } else if (activeTopic != null) {
        TopicScreen(
            topic = activeTopic,
            files = activeTopicFiles,
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
            onDeleteTopic = { topicToDelete = activeTopic }
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
                        IconButton(onClick = { showTrash = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Recycle Bin",
                                tint = if (trashEntries.isNotEmpty()) Cyan else Muted
                            )
                        }
                        Box {
                            IconButton(onClick = { topMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Muted)
                            }
                            DropdownMenu(
                                expanded = topMenuExpanded,
                                onDismissRequest = { topMenuExpanded = false },
                                containerColor = Panel
                            ) {
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
                ExtendedFloatingActionButton(
                    onClick = { showCreateTopic = true },
                    icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    text = { Text("New topic", fontWeight = FontWeight.SemiBold) },
                    containerColor = Blue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0E1728), Ink, Color(0xFF070B13))))
                .padding(padding),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp)
        ) {
            item {
                VaultOverviewCard(
                    topicCount = topics.size,
                    fileCount = vaultFiles.size,
                    storageBytes = storageUsage.totalBytes,
                    onExportAll = { launchAllExport() },
                    exportEnabled = topics.isNotEmpty() && !busy
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Topics", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (topics.isNotEmpty()) {
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
            if (topics.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = topicQuery,
                        onValueChange = { topicQuery = it },
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
                        val topicFiles = vaultFiles.filter { it.topicId == topic.id }
                        val summary = TopicSummary(
                            topic,
                            topicFiles.size,
                            storageUsage.topicBytes[topic.id] ?: 0L
                        )
                        TopicCard(
                            summary = summary,
                            onOpen = { activeTopicId = topic.id },
                            onExport = { launchTopicExport(topic.id) },
                            onDelete = { topicToDelete = topic }
                        )
                    }
                }
            }
        }
    }
    }


    if (showTrash) {
        TrashDialog(
            entries = trashEntries,
            repository = repository,
            busy = busy,
            onDismiss = { showTrash = false },
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
    }

    if (showSecurityInfo) {
        SecurityInfoDialog(onDismiss = { showSecurityInfo = false })
    }



    pendingOpenFile?.let { requestedFile ->
        AlertDialog(
            onDismissRequest = { pendingOpenFile = null },
            title = {
                Text("Open document outside Private Space?", color = White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Private Space does not render this document internally. To open it, the app must create a temporary decrypted copy and grant the app you choose access to that copy. That app may retain or copy the document. Continue only when you trust the receiving app.",
                    color = Muted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingOpenFile = null
                        val currentFile = vaultFiles.firstOrNull { it.id == requestedFile.id }
                        if (currentFile == null || !unlocked) {
                            toast("This document is no longer available in the vault.")
                        } else {
                            openExternalVaultFile(currentFile)
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Continue to document app")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpenFile = null }) {
                    Text("Keep private")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
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

    if (showRecoverySetup) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text("Protect your vault across reinstallations", color = White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Choose a folder on this device. Private Space will save an encrypted recovery copy there. Keep the folder and your vault password; both are needed to restore your data after reinstalling.",
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
                        activity?.suppressAutoLockForPicker = true
                        runCatching { recoveryFolderLauncher.launch(null) }.onFailure {
                            activity?.suppressAutoLockForPicker = false
                            recoverySetupError = "Could not open Android's device folder picker."
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (busy) "Preparing recovery…" else "Choose device folder")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    if (showCreateTopic) {
        AlertDialog(
            onDismissRequest = { showCreateTopic = false },
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
                    Text("Create topic")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTopic = false }) {
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
        val fileCount = vaultFiles.count { it.topicId == topic.id }
        ConfirmDialog(
            title = "Delete topic?",
            message = if (fileCount == 0) {
                "“${topic.name}” is empty and will be removed from your topics."
            } else {
                "“${topic.name}” will be removed from your topics. Its ${fileCount} file(s) will be moved to the Recycle Bin, where you can restore them or permanently delete them."
            },
            confirmLabel = "Delete topic",
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
            title = "Move file to Trash?",
            message = "“${file.displayName}” will be moved to the Recycle Bin. You can restore it later or permanently delete it there. The original file outside Private Space is not affected.",
            confirmLabel = "Move to Trash",
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
                    }.onFailure { toast(it.message ?: "Could not move file to Trash.") }
                }
            }
        )
    }

    trashEntryToPermanentlyDelete?.let { entry ->
        ConfirmDialog(
            title = "Permanently delete file?",
            message = "“${entry.file.displayName}” will be removed from this device and cannot be restored. This does not affect any original file or previously exported/shared copy.",
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
        ChangePasswordDialog(onDismiss = { showChangePassword = false }) { oldPassword, newPassword ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                            error("Too many attempts. Wait for the lockout to end.")
                        }
                        val valid = PasswordManager.verify(context, oldPassword.toCharArray())
                        if (!valid) {
                            PasswordManager.recordFailure(context)
                            error("Current password is incorrect.")
                        }
                        PasswordManager.clearFailures(context)
                        repository.rotatePersistentPassword(newPassword.toCharArray())
                        PasswordManager.set(context, newPassword.toCharArray())
                        repository.finishPersistentPasswordRotation()
                    }
                }
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
                                .clickable { selectedIndex = index }
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
                onClick = { onChoice(targets[selectedIndex], true) },
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) {
                Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Always", color = White)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = Muted)
                }
                TextButton(onClick = { onChoice(targets[selectedIndex], false) }) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Just once", color = Cyan)
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
                        label = { Text(if (setup) "Create password" else "Password") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
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
                            label = { Text("Confirm password") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { confirmationVisible = !confirmationVisible }) {
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
                            fontSize = 12.sp
                        )
                    }
                    if (error.isNotBlank()) {
                        Surface(color = Red.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
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
                        Surface(color = Gold.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
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
                    Button(
                        onClick = onSubmit,
                        enabled = !busy && !locked && password.isNotEmpty() &&
                            (!setup || confirmation.isNotEmpty()),
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
private fun VaultOverviewCard(
    topicCount: Int,
    fileCount: Int,
    storageBytes: Long,
    onExportAll: () -> Unit,
    exportEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.16f))
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(Color(0xFF1B3454), Color(0xFF14243B), Color(0xFF101A2B))
                )
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Surface(
                        color = Green.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(30.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Green, modifier = Modifier.size(14.dp))
                            Text("ON-DEVICE ENCRYPTION", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = Cyan.copy(alpha = 0.75f), modifier = Modifier.size(27.dp))
                }
                Text("Your vault", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewMetric(
                        modifier = Modifier.weight(1f),
                        label = "Topics",
                        value = topicCount.toString(),
                        icon = Icons.Default.Folder
                    )
                    OverviewMetric(
                        modifier = Modifier.weight(1f),
                        label = "Files",
                        value = fileCount.toString(),
                        icon = Icons.Default.Description
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Storage used", color = Muted, fontSize = 12.sp)
                        Text(formatBytes(storageBytes), color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = onExportAll,
                        enabled = exportEnabled,
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
private fun SecurityInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = Green) },
        title = { Text("About your vault", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SecurityInfoRow(
                    icon = Icons.Default.Lock,
                    title = "Encrypted on this device",
                    body = "Your files and vault index are encrypted. The encryption key is protected by Android Keystore."
                )
                SecurityInfoRow(
                    icon = Icons.Default.Share,
                    title = "Sharing and exporting",
                    body = "A temporary decrypted copy is prepared for another app. Exported or shared copies are no longer protected by this vault."
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
    onDelete: () -> Unit
) {
    var menuExpanded by remember(summary.topic.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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
                IconButton(onClick = { menuExpanded = true }) {
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
                        text = { Text("Export topic") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(
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
    onDeleteTopic: () -> Unit
) {
    var selectionMode by remember(topic.id) { mutableStateOf(false) }
    var selectedFileIds by remember(topic.id) { mutableStateOf<Set<String>>(emptySet()) }
    var topicMenuExpanded by remember(topic.id) { mutableStateOf(false) }
    val selectedFiles = files.filter { it.id in selectedFileIds }

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
                                "${files.size} ${if (files.size == 1) "item" else "items"} · PRIVATE TOPIC",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                maxLines = 1
                            )
                        }
                        Box {
                            IconButton(onClick = { topicMenuExpanded = true }) {
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
                                    leadingIcon = { Icon(Icons.Default.FolderShared, contentDescription = null) },
                                    onClick = { topicMenuExpanded = false; onShareTopic() }
                                )
                                DropdownMenuItem(
                                    enabled = !busy,
                                    text = { Text("Export topic") },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                                    onClick = { topicMenuExpanded = false; onExportTopic() }
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
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF101C30), Ink, Color(0xFF070B13))))
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Line)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("FILES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(files.size.toString(), color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.width(1.dp).height(34.dp).background(Line))
                    Column(modifier = Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ENCRYPTED STORAGE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.35.sp)
                        Text(formatBytes(storageBytes), color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).height(34.dp).background(Line))
                    Column(modifier = Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("UPDATED", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(formatDate(topic.updatedAt), color = White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Button(
                onClick = onAdd,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(15.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add files", fontWeight = FontWeight.SemiBold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your files", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        if (selectionMode) "${selectedFiles.size} selected" else "Stored securely in this topic",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
                TextButton(
                    enabled = !busy,
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
                                enabled = !busy && files.isNotEmpty(),
                                onClick = {
                                    selectedFileIds = if (selectedFileIds.size == files.size) {
                                        emptySet()
                                    } else {
                                        files.map { it.id }.toSet()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (selectedFileIds.size == files.size && files.isNotEmpty()) "Clear all" else "Select all")
                            }
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
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(70.dp),
                            color = Panel2,
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Cyan, modifier = Modifier.size(34.dp))
                            }
                        }
                        Text("Nothing here yet", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Add documents, photos or videos to this topic.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(files, key = { it.id }) { file ->
                        VaultFileRow(
                            file = file,
                            repository = repository,
                            onOpen = { onOpen(file) },
                            onShare = { onShare(file) },
                            onExport = { onExport(file) },
                            onDelete = { onDelete(file) },
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
}

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
    onNext: () -> Unit,
    onPlaybackError: () -> Unit
) {
    val isPhoto = VaultFormatSupport.isImageMimeType(file.mimeType)
    val isVideo = VaultFormatSupport.isVideoMimeType(file.mimeType)
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
                    val imageBitmap by produceState<Bitmap?>(
                        initialValue = null,
                        privateMediaFile.absolutePath,
                        targetSizePx
                    ) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                decodeViewerImage(privateMediaFile, targetSizePx)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    DisposableEffect(imageBitmap) {
                        val bitmap = imageBitmap
                        onDispose {
                            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                    val displayedBitmap = imageBitmap
                    if (displayedBitmap == null) {
                        CircularProgressIndicator(color = Cyan)
                    } else {
                        Image(
                            bitmap = displayedBitmap.asImageBitmap(),
                            contentDescription = file.displayName,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                isVideo -> {
                    key(file.id) {
                        AndroidView(
                            factory = { playerContext ->
                                val player = VideoView(playerContext)
                                val controls = MediaController(playerContext)
                                controls.setAnchorView(player)
                                player.setMediaController(controls)
                                player.setVideoPath(privateMediaFile.absolutePath)
                                player.setOnPreparedListener { mediaPlayer ->
                                    mediaPlayer.isLooping = false
                                    player.start()
                                }
                                player.setOnErrorListener { _, _, _ ->
                                    onPlaybackError()
                                    true
                                }
                                player
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { player -> player.stopPlayback() }
                        )
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
                    Text(if (isPhoto) "Photo" else "Video", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

private fun decodeViewerImage(source: File, targetMaxDimension: Int): Bitmap? {
    if (!source.isFile || source.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (largestDimension / sampleSize > targetMaxDimension && sampleSize < (1 shl 29)) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(source.absolutePath, options)
}

@Composable
private fun rememberVaultMediaPreview(
    file: VaultFile,
    repository: VaultRepository
): VaultMediaPreview? {
    val targetSizePx = with(LocalDensity.current) { 128.dp.roundToPx().coerceAtLeast(64) }
    val isPreviewable = VaultFormatSupport.isImageMimeType(file.mimeType) ||
        VaultFormatSupport.isVideoMimeType(file.mimeType)
    val preview by produceState<VaultMediaPreview?>(null, file.id, file.mimeType, targetSizePx) {
        value = if (isPreviewable) {
            try {
                withContext(Dispatchers.IO) {
                    repository.loadMediaPreview(file.id, targetSizePx)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
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
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    var menuExpanded by remember(file.id) { mutableStateOf(false) }
    val isPhoto = VaultFormatSupport.isImageMimeType(file.mimeType)
    val isVideo = VaultFormatSupport.isVideoMimeType(file.mimeType)
    val mediaPreview = rememberVaultMediaPreview(file, repository)
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
        modifier = Modifier.fillMaxWidth().clickable {
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
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for " + file.displayName, tint = Muted)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Panel
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(
                        text = { Text("Delete", color = Red) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Red) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}


@Composable
private fun TrashDialog(
    entries: List<VaultTrashEntry>,
    repository: VaultRepository,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRestore: (VaultTrashEntry) -> Unit,
    onDeletePermanently: (VaultTrashEntry) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 10.dp),
            shape = RoundedCornerShape(27.dp),
            color = Panel,
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = Blue.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Cyan, modifier = Modifier.size(25.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recycle Bin", color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(
                            entries.size.toString() + if (entries.size == 1) " deleted file" else " deleted files",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Recycle Bin", tint = Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Line)
                if (entries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Muted, modifier = Modifier.size(42.dp))
                            Text("Recycle Bin is empty", color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Files you delete will appear here.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(entries, key = { it.file.id }) { entry ->
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
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(8.dp))
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
    val isPhoto = VaultFormatSupport.isImageMimeType(file.mimeType)
    val isVideo = VaultFormatSupport.isVideoMimeType(file.mimeType)
    val mediaPreview = rememberVaultMediaPreview(file, repository)
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
    onDismiss: () -> Unit,
    onChange: (String, String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var oldVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }
    var confirmationVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Change password", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current password") },
                    visualTransformation = if (oldVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { oldVisible = !oldVisible }) {
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
                    label = { Text("New password") },
                    visualTransformation = if (newVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { newVisible = !newVisible }) {
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
                    label = { Text("Confirm new password") },
                    visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmationVisible = !confirmationVisible }) {
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
                enabled = oldPassword.isNotEmpty() &&
                    newPassword.length >= PasswordManager.MIN_PASSWORD_LENGTH &&
                    newPassword == confirmation,
                onClick = { onChange(oldPassword, newPassword) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Save password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Red) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = Muted, lineHeight = 19.sp) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Ink),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Keep it")
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
