package com.privatespace.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.data.*
import com.privatespace.android.security.PasswordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    override fun onPause() {
        super.onPause()
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            onVaultShouldLock?.invoke()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !suppressAutoLockForPicker) {
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

@Composable
private fun PrivateSpaceRoot() {
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val repository = remember { VaultRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!PasswordManager.configured(context)) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lockUntil by remember { mutableLongStateOf(PasswordManager.lockedUntil(context)) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var topics by remember { mutableStateOf<List<VaultTopic>>(emptyList()) }
    var vaultFiles by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var storageUsage by remember { mutableStateOf(VaultStorageUsage()) }
    var activeTopicId by remember { mutableStateOf<String?>(null) }
    var topicQuery by remember { mutableStateOf("") }
    var showSecurityInfo by remember { mutableStateOf(false) }
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

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun lockVault() {
        unlocked = false
        password = ""
        confirmation = ""
        authError = ""
        activeTopicId = null
        topicQuery = ""
        showSecurityInfo = false
        topics = emptyList()
        vaultFiles = emptyList()
        storageUsage = VaultStorageUsage()
        topicName = ""
        showCreateTopic = false
        showChangePassword = false
        topicToDelete = null
        fileToDelete = null
        pendingImportTopic = null
        pendingFilePickerTargets = null
        activity?.suppressAutoLockForPicker = false
    }

    DisposableEffect(activity) {
        activity?.onVaultShouldLock = { lockVault() }
        onDispose { activity?.onVaultShouldLock = null }
    }

    suspend fun refreshVault() {
        val snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
        topics = snapshot.topics
        vaultFiles = snapshot.files
        storageUsage = snapshot.storageUsage
    }

    LaunchedEffect(unlocked) {
        if (unlocked) {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.cleanupExpiredOutgoing()
                    repository.snapshot()
                }
            }.onSuccess { snapshot ->
                topics = snapshot.topics
                vaultFiles = snapshot.files
                storageUsage = snapshot.storageUsage
            }.onFailure {
                authError = it.message ?: "The vault could not be opened safely."
                toast(authError)
                lockVault()
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
                runCatching { refreshVault() }
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
        pendingExportTopic = null
        pendingExportAll = false
        if (destination != null && unlocked) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = DocumentFile.fromTreeUri(context, destination)
                            ?: error("The selected folder is unavailable.")
                        if (allTopics) repository.exportAllToFolder(folder)
                        else repository.exportTopicToFolder(
                            topicId ?: error("No topic was selected for export."),
                            folder
                        )
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

    fun launchSingleFileExport(file: VaultFile) {
        pendingExportFile = file.id
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFileLauncher.launch(file.displayName) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingExportFile = null
            toast("Could not open the export picker.")
        }
    }

    fun launchTopicExport(topicId: String) {
        pendingExportTopic = topicId
        pendingExportAll = false
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingExportTopic = null
            toast("Could not open the folder picker.")
        }
    }

    fun launchAllExport() {
        if (topics.isEmpty()) {
            toast("There are no topics to export.")
            return
        }
        pendingExportTopic = null
        pendingExportAll = true
        activity?.suppressAutoLockForPicker = true
        runCatching { exportFolderLauncher.launch(null) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingExportAll = false
            toast("Could not open the folder picker.")
        }
    }

    fun openVaultFile(file: VaultFile) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.prepareDecryptedFile(file.id) }
            }
            busy = false
            result.onSuccess { prepared ->
                val uri = FileProvider.getUriForFile(
                    context, "com.privatespace.android.fileprovider", prepared.file
                )
                val viewIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, prepared.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewIntent.clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
                runCatching {
                    context.startActivity(Intent.createChooser(viewIntent, "Open with"))
                }.onFailure {
                    prepared.file.parentFile?.deleteRecursively()
                    toast("No compatible app could open this file.")
                }
            }.onFailure {
                toast(it.message ?: "The file could not be decrypted or opened.")
            }
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
                val uri = FileProvider.getUriForFile(
                    context, "com.privatespace.android.fileprovider", prepared.file
                )
                val sendIntent = Intent(Intent.ACTION_SEND)
                    .setType(prepared.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                sendIntent.clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
                runCatching {
                    context.startActivity(Intent.createChooser(sendIntent, "Share file"))
                }.onFailure {
                    prepared.file.parentFile?.deleteRecursively()
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
            onPassword = { password = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
            onConfirmation = { confirmation = it.take(PasswordManager.MAX_PASSWORD_LENGTH) }
        ) {
            if (busy || lockUntil > System.currentTimeMillis()) return@AuthScreen
            busy = true
            authError = ""
            val entered = password
            val confirm = confirmation
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (setup) {
                            require(entered.length in PasswordManager.MIN_PASSWORD_LENGTH..PasswordManager.MAX_PASSWORD_LENGTH) {
                                "Use at least ${PasswordManager.MIN_PASSWORD_LENGTH} characters."
                            }
                            require(entered == confirm) { "Passwords do not match." }
                            PasswordManager.set(context, entered.toCharArray())
                            true
                        } else {
                            if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) {
                                error("Too many attempts. Wait for the lockout to end.")
                            }
                            val valid = PasswordManager.verify(context, entered.toCharArray())
                            if (valid) PasswordManager.clearFailures(context)
                            else lockUntil = PasswordManager.recordFailure(context)
                            valid
                        }
                    }
                }
                busy = false
                result.fold(
                    onSuccess = { valid ->
                        if (valid) {
                            setup = false
                            unlocked = true
                            password = ""
                            confirmation = ""
                            authError = ""
                        } else {
                            password = ""
                            authError = "Incorrect password."
                        }
                    },
                    onFailure = {
                        authError = it.message ?: "Authentication failed."
                        password = ""
                        confirmation = ""
                    }
                )
            }
        }
        return
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            Surface(color = Ink, tonalElevation = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = Blue.copy(alpha = 0.16f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lock, contentDescription = "Private Space", tint = Cyan, modifier = Modifier.size(23.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Private Space", fontWeight = FontWeight.Bold, color = White, fontSize = 19.sp)
                        Text("YOUR PERSONAL VAULT", fontSize = 10.sp, color = Muted, letterSpacing = 1.1.sp)
                    }
                    IconButton(onClick = { showSecurityInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Vault security information", tint = Muted)
                    }
                    IconButton(onClick = { showChangePassword = true }) {
                        Icon(Icons.Default.Key, contentDescription = "Change password", tint = Muted)
                    }
                    IconButton(onClick = { lockVault() }) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock vault now", tint = Cyan)
                    }
                }
            }
        },
        floatingActionButton = {
            if (topics.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateTopic = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New topic", fontWeight = FontWeight.SemiBold) },
                    containerColor = Blue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    Column(Modifier.weight(1f)) {
                        Text("Your topics", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (topics.isEmpty()) "Your files will be organized here"
                            else topics.size.toString() + if (topics.size == 1) " private collection" else " private collections",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
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
                        placeholder = { Text("Search your topics", color = Muted) },
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
                        EmptySearchState(
                            query = topicQuery,
                            onClear = { topicQuery = "" }
                        )
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


    val activeTopic = topics.firstOrNull { it.id == activeTopicId }
    if (showSecurityInfo) {
        SecurityInfoDialog(onDismiss = { showSecurityInfo = false })
    }

    if (activeTopic != null) {
        TopicDialog(
            topic = activeTopic,
            files = vaultFiles.filter { it.topicId == activeTopic.id },
            storageBytes = storageUsage.topicBytes[activeTopic.id] ?: 0L,
            onDismiss = { activeTopicId = null },
            onAdd = { launchImport(activeTopic.id) },
            onOpen = ::openVaultFile,
            onShare = ::shareVaultFile,
            onExport = ::launchSingleFileExport,
            onDelete = { fileToDelete = it },
            onShareTopic = { shareWholeTopic(activeTopic.id) }
        )
    }

    if (showCreateTopic) {
        AlertDialog(
            onDismissRequest = { showCreateTopic = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Create a topic", fontWeight = FontWeight.Bold)
                    Text("Give this collection a name you will recognize.", color = Muted, fontSize = 12.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = topicName,
                        onValueChange = { topicName = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Topic name") },
                        placeholder = { Text("e.g. Personal documents") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        supportingText = {
                            Text(topicName.length.toString() + " / 64", color = Muted)
                        }
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
                    Text("Create topic")
                }
            },
            dismissButton = { TextButton(onClick = { showCreateTopic = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(24.dp),
            containerColor = Panel
        )
    }

    topicToDelete?.let { topic ->
        ConfirmDialog(
            title = "Delete topic and files?",
            message = "“" + topic.name + "” and all " +
                vaultFiles.count { it.topicId == topic.id } +
                " stored file(s) will be permanently deleted from this device. This cannot be undone.",
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
                    }.onFailure { toast(it.message ?: "Could not delete topic.") }
                }
            }
        )
    }

    fileToDelete?.let { file ->
        ConfirmDialog(
            title = "Delete file?",
            message = "“" + file.displayName + "” will be permanently removed from Private Space. This does not delete the original file outside the vault.",
            onCancel = { fileToDelete = null },
            onConfirm = {
                fileToDelete = null
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.deleteFile(file.id) }
                    }
                    busy = false
                    result.onSuccess { refreshVault() }
                        .onFailure { toast(it.message ?: "Could not delete file.") }
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
                            lockUntil = PasswordManager.recordFailure(context)
                            error("Current password is incorrect.")
                        }
                        PasswordManager.clearFailures(context)
                        PasswordManager.set(context, newPassword.toCharArray())
                    }
                }
                result.onSuccess {
                    showChangePassword = false
                    toast("Password changed.")
                }.onFailure {
                    toast(it.message ?: "Password could not be changed.")
                    if (PasswordManager.lockedUntil(context) > System.currentTimeMillis()) lockVault()
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
                Text(
                    "Choose an app to browse and select documents or photos.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
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
                                Text(
                                    if (selected) "Selected file picker" else "Tap to choose this app",
                                    color = Muted,
                                    fontSize = 11.sp,
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
                Text("Always", color = White)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Muted)
                }
                TextButton(onClick = { onChoice(targets[selectedIndex], false) }) {
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
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmationVisible by remember { mutableStateOf(false) }
    val locked = lockUntil > now
    val secondsRemaining = ((lockUntil - now).coerceAtLeast(0L) + 999L) / 1_000L

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF101D32), Ink, Color(0xFF070B13)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(24.dp),
                color = Blue.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Cyan, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Private Space", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Your documents. Your private place.", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, Line.copy(alpha = 0.9f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (setup) "Create your vault password" else "Welcome back",
                            color = White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp
                        )
                        Text(
                            if (setup) "Set a password to protect this vault on this device."
                            else "Enter your password to unlock your private files.",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }
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
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Securing your vault…")
                        } else {
                            Text(if (setup) "Create secure vault" else "Unlock vault", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.widthIn(max = 420.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Your files and vault index are encrypted on this device.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
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
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.8f))
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Panel2, Panel)))) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(color = Green.copy(alpha = 0.13f), shape = RoundedCornerShape(30.dp)) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Green, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("ON-DEVICE ENCRYPTION", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Your private library", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Everything you store here stays inside your encrypted vault.", color = Muted, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverviewMetric(
                        modifier = Modifier.weight(1f),
                        label = "TOPICS",
                        value = topicCount.toString(),
                        icon = Icons.Default.Folder
                    )
                    OverviewMetric(
                        modifier = Modifier.weight(1f),
                        label = "FILES",
                        value = fileCount.toString(),
                        icon = Icons.Default.Description
                    )
                }
                HorizontalDivider(color = Line.copy(alpha = 0.8f))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Encrypted storage used", color = Muted, fontSize = 12.sp)
                        Text(formatBytes(storageBytes), color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = onExportAll,
                        enabled = exportEnabled,
                        shape = RoundedCornerShape(13.dp)
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
        color = Panel2.copy(alpha = 0.85f),
        shape = RoundedCornerShape(17.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
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
            Text(
                "Create a collection for your documents or photos. You can add files, open them, share them, or export them later.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Button(onClick = onCreate, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Create your first topic")
            }
        }
    }
}

@Composable
private fun EmptySearchState(query: String, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(Icons.Default.SearchOff, contentDescription = null, tint = Muted, modifier = Modifier.size(36.dp))
        Text("No matching topics", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text("Nothing matches \"" + query + "\".", color = Muted, fontSize = 13.sp)
        TextButton(onClick = onClear) { Text("Clear search") }
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
                    title = "No password recovery",
                    body = "There is no cloud backup or password reset. Keep a separate secure copy of important documents."
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Got it") } },
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(21.dp),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.75f))
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
                Text(
                    (summary.fileCount.toString() + if (summary.fileCount == 1) " file" else " files") +
                        " · " + formatBytes(summary.totalBytes),
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Updated " + formatDate(summary.topic.updatedAt), color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Export " + summary.topic.name, tint = Muted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete " + summary.topic.name, tint = Red)
                }
            }
        }
    }
}

@Composable
private fun TopicDialog(
    topic: VaultTopic,
    files: List<VaultFile>,
    storageBytes: Long,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (VaultFile) -> Unit,
    onShare: (VaultFile) -> Unit,
    onExport: (VaultFile) -> Unit,
    onDelete: (VaultFile) -> Unit,
    onShareTopic: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 10.dp),
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
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Cyan, modifier = Modifier.size(25.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(topic.name, color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(files.size.toString() + if (files.size == 1) " file" else " files", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close topic", tint = Muted)
                    }
                }
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = Panel2,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("ENCRYPTED STORAGE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(formatBytes(storageBytes), color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = Panel2,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("LAST UPDATED", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(formatDate(topic.updatedAt), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add files")
                    }
                    OutlinedButton(
                        onClick = onShareTopic,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share topic")
                    }
                }
                Text(
                    "Files stay encrypted while stored here. Opening or sharing prepares a temporary copy.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 9.dp, bottom = 5.dp)
                )
                HorizontalDivider(color = Line)
                if (files.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = Muted, modifier = Modifier.size(38.dp))
                            Text("No files in this topic yet", color = White, fontWeight = FontWeight.SemiBold)
                            Text("Add documents or photos to see them here.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files, key = { it.id }) { file ->
                            VaultFileRow(
                                file = file,
                                onOpen = { onOpen(file) },
                                onShare = { onShare(file) },
                                onExport = { onExport(file) },
                                onDelete = { onDelete(file) }
                            )
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Created " + formatDate(topic.createdAt), color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun VaultFileRow(
    file: VaultFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(file.id) { mutableStateOf(false) }
    val isPhoto = file.mimeType.startsWith("image/")
    val extension = file.displayName.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel2)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (isPhoto) Green.copy(alpha = 0.13f) else Blue.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPhoto) Icons.Default.Image else Icons.Default.Description,
                        contentDescription = if (isPhoto) "Photo" else "Document",
                        tint = if (isPhoto) Green else Cyan
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.displayName, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(extension + " · " + formatBytes(file.sizeBytes) + " original size", color = Muted, fontSize = 11.sp, maxLines = 1)
            }
            Box {
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
                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
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
                Text("Choose a new password for this device.", color = Muted, fontSize = 12.sp)
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
                Text(
                    "Use at least " + PasswordManager.MIN_PASSWORD_LENGTH +
                        " characters. The new password must match its confirmation.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            Button(
                enabled = oldPassword.isNotEmpty() &&
                    newPassword.length >= PasswordManager.MIN_PASSWORD_LENGTH &&
                    newPassword == confirmation,
                onClick = { onChange(oldPassword, newPassword) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Save password") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = Panel
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
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
            ) { Text("Delete permanently", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
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

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
