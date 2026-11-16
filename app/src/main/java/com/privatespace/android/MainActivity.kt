package com.privatespace.android

import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
private val White = Color(0xFFF2F6FF)
private val Muted = Color(0xFF96A7C1)
private val Blue = Color(0xFF4D8DFF)
private val Cyan = Color(0xFF60D9FF)
private val Red = Color(0xFFFF7185)

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
                    secondary = Cyan,
                    background = Ink,
                    surface = Panel,
                    surfaceVariant = Panel2,
                    onBackground = White,
                    onSurface = White,
                    onSurfaceVariant = Muted,
                    outline = Line
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
    var activeTopicId by remember { mutableStateOf<String?>(null) }
    var showCreateTopic by remember { mutableStateOf(false) }
    var topicName by remember { mutableStateOf("") }
    var showChangePassword by remember { mutableStateOf(false) }
    var topicToDelete by remember { mutableStateOf<VaultTopic?>(null) }
    var fileToDelete by remember { mutableStateOf<VaultFile?>(null) }

    var pendingImportTopic by remember { mutableStateOf<String?>(null) }
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
        topics = emptyList()
        vaultFiles = emptyList()
        topicName = ""
        showCreateTopic = false
        showChangePassword = false
        topicToDelete = null
        fileToDelete = null
    }

    DisposableEffect(activity) {
        activity?.onVaultShouldLock = { lockVault() }
        onDispose { activity?.onVaultShouldLock = null }
    }

    suspend fun refreshVault() {
        val snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
        topics = snapshot.topics
        vaultFiles = snapshot.files
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selectedUris ->
        activity?.suppressAutoLockForPicker = false
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

    fun launchImport(topicId: String) {
        pendingImportTopic = topicId
        activity?.suppressAutoLockForPicker = true
        runCatching { importLauncher.launch(arrayOf("*/*")) }.onFailure {
            activity?.suppressAutoLockForPicker = false
            pendingImportTopic = null
            toast("Could not open the document picker.")
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
            Row(
                Modifier.fillMaxWidth().background(Ink).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Cyan, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("PRIVATE SPACE", fontWeight = FontWeight.Black, color = White, letterSpacing = 1.2.sp)
                    Text("YOUR PERSONAL DOCUMENT VAULT", fontSize = 9.sp, color = Muted)
                }
                IconButton(onClick = { showChangePassword = true }) {
                    Icon(Icons.Default.Key, contentDescription = "Change password", tint = Muted)
                }
                IconButton(onClick = { lockVault() }) {
                    Icon(Icons.Default.Lock, contentDescription = "Lock vault", tint = Muted)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateTopic = true }, containerColor = Blue) {
                Icon(Icons.Default.Add, contentDescription = "Create topic", tint = Color.White)
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("YOUR VAULT", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Text(
                            "${topics.size} topics · ${vaultFiles.size} files",
                            color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = { launchAllExport() }) {
                            Icon(Icons.Default.FolderZip, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export all topics")
                        }
                    }
                }
            }
            if (topics.isEmpty()) {
                item { Text("Create a topic to start adding your documents and photos.", color = Muted) }
            } else {
                items(topics, key = { it.id }) { topic ->
                    val topicFiles = vaultFiles.filter { it.topicId == topic.id }
                    val summary = TopicSummary(topic, topicFiles.size, topicFiles.sumOf { it.sizeBytes })
                    TopicCard(
                        summary = summary,
                        onOpen = { activeTopicId = topic.id },
                        onExport = { launchTopicExport(topic.id) },
                        onDelete = { topicToDelete = topic }
                    )
                }
            }
            item { Spacer(Modifier.height(70.dp)) }
        }
    }

    val activeTopic = topics.firstOrNull { it.id == activeTopicId }
    if (activeTopic != null) {
        TopicDialog(
            topic = activeTopic,
            files = vaultFiles.filter { it.topicId == activeTopic.id },
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
            title = { Text("Create topic") },
            text = {
                OutlinedTextField(
                    value = topicName,
                    onValueChange = { topicName = it.take(64) },
                    label = { Text("Topic name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = topicName.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching { repository.createTopic(topicName) }
                            }
                            busy = false
                            result.onSuccess {
                                topicName = ""
                                showCreateTopic = false
                                refreshVault()
                            }.onFailure { toast(it.message ?: "Could not create topic.") }
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateTopic = false }) { Text("Cancel") } }
        )
    }

    topicToDelete?.let { topic ->
        ConfirmDialog(
            title = "Delete topic and files?",
            message = "This permanently deletes the topic and its encrypted files from this device.",
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
            message = "This permanently removes the encrypted copy from Private Space.",
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

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
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
    val locked = lockUntil > now
    val secondsRemaining = ((lockUntil - now).coerceAtLeast(0L) + 999L) / 1_000L
    Column(
        Modifier.fillMaxSize().background(Ink).padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Cyan, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text("PRIVATE SPACE", fontSize = 25.sp, fontWeight = FontWeight.Black, color = White, letterSpacing = 2.sp)
        Text(if (setup) "Set up a password to begin" else "Unlock your personal vault", color = Muted)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (setup) "Create password" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        if (setup) {
            OutlinedTextField(
                value = confirmation,
                onValueChange = onConfirmation,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Confirm password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Text(
                "Use at least 10 characters. A longer passphrase is better.",
                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (error.isNotBlank()) Text(error, color = Red, modifier = Modifier.padding(top = 8.dp))
        if (locked) Text("Too many attempts. Try again in $secondsRemaining seconds.", color = Red)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onSubmit,
            enabled = !busy && !locked && password.isNotEmpty() && (!setup || confirmation.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (busy) "Please wait…" else if (setup) "Create secure vault" else "Unlock")
        }
        Text(
            "Your files and vault index are encrypted on this device.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp)
        )
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
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    summary.topic.name, color = White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Export topic", tint = Muted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete topic", tint = Red)
                }
            }
            Text("${summary.fileCount} files · ${formatBytes(summary.totalBytes)}", color = Muted, fontSize = 11.sp)
            Text(
                "Created ${formatDate(summary.topic.createdAt)} · Updated ${formatDate(summary.topic.updatedAt)}",
                color = Muted, fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun TopicDialog(
    topic: VaultTopic,
    files: List<VaultFile>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (VaultFile) -> Unit,
    onShare: (VaultFile) -> Unit,
    onExport: (VaultFile) -> Unit,
    onDelete: (VaultFile) -> Unit,
    onShareTopic: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(topic.name)
                Text("${files.size} files · Updated ${formatDate(topic.updatedAt)}", color = Muted, fontSize = 10.sp)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 430.dp)) {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add documents / photos")
                }
                OutlinedButton(onClick = onShareTopic, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share entire topic")
                }
                if (files.isEmpty()) {
                    Text("No files in this topic yet.", color = Muted, modifier = Modifier.padding(vertical = 14.dp))
                } else {
                    LazyColumn {
                        items(files, key = { it.id }) { file ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (file.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description,
                                    contentDescription = null, tint = Cyan
                                )
                                Spacer(Modifier.width(5.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.displayName, color = White, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(formatBytes(file.sizeBytes), color = Muted, fontSize = 9.sp)
                                }
                                IconButton(onClick = { onOpen(file) }) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = "Open file")
                                }
                                IconButton(onClick = { onShare(file) }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share file")
                                }
                                IconButton(onClick = { onExport(file) }) {
                                    Icon(Icons.Default.FileDownload, contentDescription = "Export file")
                                }
                                IconButton(onClick = { onDelete(file) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete file", tint = Red)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onChange: (String, String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column {
                OutlinedTextField(
                    oldPassword,
                    { oldPassword = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    newPassword,
                    { newPassword = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                OutlinedTextField(
                    confirmation,
                    { confirmation = it.take(PasswordManager.MAX_PASSWORD_LENGTH) },
                    label = { Text("Confirm new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text("Use at least ${PasswordManager.MIN_PASSWORD_LENGTH} characters.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = oldPassword.isNotEmpty() &&
                    newPassword.length >= PasswordManager.MIN_PASSWORD_LENGTH &&
                    newPassword == confirmation,
                onClick = { onChange(oldPassword, newPassword) }
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Red) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
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
