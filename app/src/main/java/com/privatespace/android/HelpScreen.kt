package com.privatespace.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronDown
import androidx.compose.material.icons.filled.ChevronUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HelpInk = Color(0xFF080D18)
private val HelpPanel = Color(0xFF111B2D)
private val HelpPanelRaised = Color(0xFF18263C)
private val HelpLine = Color(0xFF293A54)
private val HelpWhite = Color(0xFFF4F7FC)
private val HelpMuted = Color(0xFF9BAEC5)
private val HelpCyan = Color(0xFF69E0D0)
private val HelpBlue = Color(0xFF6F9EFF)
private val HelpGreen = Color(0xFF70D6A6)
private val HelpGold = Color(0xFFF3C16D)

private data class HelpQuestion(
    val id: String,
    val question: String,
    val answer: String,
    val keywords: String
)

private data class HelpCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color,
    val questions: List<HelpQuestion>
)

private val HelpCategories = listOf(
    HelpCategory(
        id = "overview",
        title = "Getting to know Private Space",
        subtitle = "The purpose of the app and the way it fits into your phone",
        icon = Icons.Default.Info,
        accent = HelpBlue,
        questions = listOf(
            HelpQuestion(
                "what-is",
                "What is Private Space?",
                "Private Space is an Android app that gives you a password-protected, encrypted vault for selected documents, photos and videos. It stores separate copies inside the app's private storage instead of leaving those copies in an ordinary folder or gallery.\n\nIt is an app-level file vault. It is not a replacement for Android's device lock, a complete phone backup, or a hardware-isolated system container.",
                "what is about overview purpose definition app"
            ),
            HelpQuestion(
                "what-does",
                "What does this app do?",
                "It imports selected files into topics, encrypts their contents and vault metadata, lets you browse and open supported files, and provides Share, Export, Recycle Bin and optional encrypted recovery features. The original files you select are not moved or modified.",
                "does functions documents photos videos organize protect"
            ),
            HelpQuestion(
                "why-use",
                "Why use Private Space?",
                "Use it when you want a separate encrypted place for copies of sensitive files, rather than keeping those copies mixed with everyday downloads or gallery items. The on-device vault is protected by Android app storage isolation and an Android Keystore-managed encryption key. An optional encrypted recovery copy can help you restore your vault after reinstalling.\n\nIt reduces exposure; it cannot protect files that you leave elsewhere on the phone, copies you release to other apps, or a device that is already compromised.",
                "why benefit reason privacy separate confidential files"
            ),
            HelpQuestion(
                "how-works",
                "How does Private Space work?",
                "1. Create a vault password and unlock the app.\n2. Create a topic, such as Personal or Work.\n3. Select files in Android's file picker. Private Space reads each selected file and encrypts a copy into its app-private vault.\n4. Unlock the vault to browse topics, view previews where supported, or open a file in a compatible app.\n5. Use Share or Export only when you intend to release a decrypted copy outside the vault.\n6. Optionally configure a separate encrypted recovery folder on device storage.",
                "how works steps process workflow"
            )
        )
    ),
    HelpCategory(
        id = "features",
        title = "Features and everyday workflows",
        subtitle = "What you can do after unlocking your vault",
        icon = Icons.Default.FolderOpen,
        accent = HelpCyan,
        questions = listOf(
            HelpQuestion(
                "what-can-do",
                "What can I do in the app?",
                "You can create topics; add one or many documents, photos and videos; browse files and supported previews; open a file with an installed viewer; share selected files; export files or topics; move deleted items to the Recycle Bin; restore or permanently delete those items; change your vault password; lock the vault; and set up or restore an encrypted recovery copy.\n\nVideo playback and some file previews depend on the Android device's installed viewers and media decoders.",
                "features actions functions create topic import add view preview open share export recycle trash restore delete password lock backup"
            ),
            HelpQuestion(
                "major-workflows",
                "What are the major workflows in Private Space?",
                "• Set up: create a vault password, then optionally configure recovery.\n• Organize: create a topic and import copies of your files.\n• Access: unlock, browse and open a file in a compatible viewer.\n• Release: confirm Share or Export when you deliberately want a copy outside the vault.\n• Recover: restore an item from the Recycle Bin, or restore the complete vault from an encrypted recovery folder.\n• Protect: change your password when needed and use Lock whenever you leave the vault unattended.",
                "major workflows user journey setup organize access release recovery protect"
            ),
            HelpQuestion(
                "open-share-export",
                "What is the difference between Open, Share and Export?",
                "Open prepares a temporary decrypted copy for the viewer app you select when you tap a file. The selected file is handed to that viewer so it can display or play it.\n\nShare prepares the selected content for another app or recipient after a confirmation. Export writes decrypted copies to a destination you choose, also after confirmation. Once content reaches another app or an export destination, Private Space cannot control whether that app or destination retains or copies it.",
                "open view share send export external viewer plaintext temporary copy"
            ),
            HelpQuestion(
                "recycle-bin",
                "How does the Recycle Bin work?",
                "Deleting a file or topic moves its vault records into the Recycle Bin so you can restore them later. Restore returns a file to its original topic; if that topic no longer exists, the app can recreate it. Delete forever removes the vault copy and cannot be undone from the app.\n\nPermanent deletion is not a forensic secure-erase guarantee for flash storage, and it does not remove the original file or copies previously exported or shared.",
                "recycle bin trash delete restore permanently remove"
            ),
            HelpQuestion(
                "change-password",
                "What happens when I change my vault password?",
                "The app verifies your current password and saves the new password verifier. If encrypted recovery is enabled, it also re-wraps the existing random recovery data key with the new password and verifies the updated recovery-key envelope. It does not need to decrypt and re-encrypt every stored file.\n\nThere is no password reset service. Keep your current password available until the change has completed, and remember the new password for future recovery.",
                "change password rotate password reset recovery key envelope"
            )
        )
    ),
    HelpCategory(
        id = "protection",
        title = "Security, privacy and a stolen phone",
        subtitle = "What the app protects—and where its protection ends",
        icon = Icons.Default.Shield,
        accent = HelpGreen,
        questions = listOf(
            HelpQuestion(
                "data-security",
                "How does Private Space keep my data secure?",
                "The working vault and its metadata are stored in app-private internal storage and encrypted at rest. A 256-bit AES key managed by Android Keystore encrypts the on-device vault. The app also uses Android's app sandbox, restrictive file permissions, password verification with PBKDF2-HMAC-SHA-256, and persisted failed-attempt delays.\n\nAndroid backup and device-transfer backup are disabled for the app. The app does not request the Internet permission. Temporary decrypted files are kept in restricted app-private locations and are cleaned up by the app's lifecycle and cleanup routines. Opening, sharing and exporting are deliberate release points because another app or destination must receive readable content.",
                "secure security privacy encryption app sandbox private storage keystore password failed attempts backup internet temporary"
            ),
            HelpQuestion(
                "stolen-app",
                "If someone steals my phone, can they open Private Space?",
                "If the phone is locked with a strong Android screen lock, the thief must first get past that device protection. Private Space also requires its own vault password. When Android locks the screen, the app locks the vault and requires the password again.\n\nImportant: switching to another app does not by itself lock the vault. If the phone is stolen while it is already unlocked and Private Space is still open, someone holding the unlocked phone may be able to see the open vault until you lock it or Android locks the screen. Use the in-app Lock control before leaving the phone unattended, and enable a strong Android screen lock.",
                "stolen theft phone open app unlock passcode lock screen background"
            ),
            HelpQuestion(
                "stolen-files",
                "If my phone is stolen, can someone access the files in my vault?",
                "A copied app-private vault contains encrypted file data, not ordinary readable documents, photos or videos. The local encryption key is managed by Android Keystore and is not stored beside the encrypted files as a plain key. This is designed to prevent someone who merely copies app files from reading the vault.\n\nIt is not an absolute guarantee against a thief using an already-unlocked phone, learning your vault password, exploiting the operating system, gaining root-level access, or accessing original files and exported copies stored elsewhere. Those situations are outside the app's protection boundary.",
                "stolen phone access files data directory encrypted keystore root unlocked originals"
            ),
            HelpQuestion(
                "stolen-recovery",
                "If someone steals my recovery folder, can they decrypt it?",
                "The recovery folder contains encrypted payloads, an encrypted index and a password-protected envelope for a separate random 256-bit recovery key. It does not store your files as ordinary plaintext. The key envelope is protected using a key derived from your vault password with PBKDF2-HMAC-SHA-256 (600,000 iterations), then authenticated with AES-256-GCM.\n\nThe folder alone does not directly reveal the files. However, an attacker who obtains it can attempt offline password guesses. Use a long, unique password that is difficult to guess. If an attacker knows or correctly guesses that password, or compromises an unlocked device, the recovery copy may be exposed.",
                "recovery folder stolen decrypt password offline guess pbkdf2 key envelope"
            ),
            HelpQuestion(
                "stolen-protection",
                "How does Private Space protect my data if my phone is stolen?",
                "It uses several layers: Android's device lock; the app's separate vault-password gate; Android app-private storage; an Android Keystore-managed AES-256-GCM key for the on-device vault; authenticated encryption that detects tampering; and optional recovery files encrypted with a separate random key protected by your password.\n\nThese layers help protect a locked device and copied storage. They do not make an already-unlocked phone safe to hand to a thief, protect files outside the vault, or defeat root access and a compromised operating system. Lock the vault before putting the phone down and keep your Android screen lock enabled.",
                "stolen phone protection layers device lock screen lock vault key encryption threat model"
            ),
            HelpQuestion(
                "knox",
                "Is Private Space similar to Samsung Knox or Secure Folder?",
                "They share a broad goal—keeping selected information separate from everyday access—but they are not the same technology. Private Space is an app-level encrypted vault that uses Android app isolation and Android Keystore. It does not provide Samsung Knox's platform-level security architecture, hardware-backed workspace separation, enterprise management or system-wide controls.\n\nDo not treat Private Space as a replacement for Samsung Secure Folder, Knox security features, or your phone's built-in device protection.",
                "samsung knox secure folder comparison similar difference hardware platform enterprise"
            )
        )
    ),
    HelpCategory(
        id = "encryption",
        title = "Encryption, decryption and file integrity",
        subtitle = "The algorithms and what happens to your original file bytes",
        icon = Icons.Default.Lock,
        accent = HelpBlue,
        questions = listOf(
            HelpQuestion(
                "algorithms",
                "Which encryption algorithms does Private Space use, and why?",
                "The on-device working vault uses AES-256-GCM with a 256-bit key managed by Android Keystore. GCM is authenticated encryption: it protects confidentiality and detects changes to encrypted data. New files are processed in authenticated 64 KiB frames. Each frame has its own nonce and authentication tag, and the format authenticates the frame order, lengths and end of the file.\n\nThe recovery copy uses a separate random 256-bit AES key with AES-256-GCM. PBKDF2-HMAC-SHA-256 with 600,000 iterations derives a key from your vault password to wrap and protect that recovery key. PBKDF2-HMAC-SHA-256 is also used for the app's password verifier. Your password is not the Android Keystore key that encrypts the on-device working vault.\n\nAES-256-GCM is used because it provides widely reviewed, authenticated encryption. Chunking allows large files to be processed with bounded memory while detecting tampering, truncation and reordering.",
                "algorithm aes 256 gcm pbkdf2 sha256 iterations nonce tag encryption cipher security"
            ),
            HelpQuestion(
                "encryption-process",
                "How does the app encrypt and decrypt my files?",
                "When you add a file, Private Space reads its original bytes as a stream, encrypts them in 64 KiB authenticated chunks, and writes the encrypted container into app-private storage. It does not need to load the entire file into memory.\n\nWhen a file is opened, previewed, shared, exported or restored, the app authenticates and decrypts the required data through streaming operations. Opening creates a temporary decrypted copy for the external viewer. During recovery restore, the app decrypts each authenticated recovery record directly into a new locally encrypted record; it does not write a plaintext restore file to disk.",
                "encrypt decrypt encryption decryption stream chunks import open preview share export restore process"
            ),
            HelpQuestion(
                "file-quality",
                "Does encrypting and decrypting documents, photos or videos reduce their quality?",
                "No. The app encrypts and decrypts the original file bytes; it does not resize, recompress, transcode or convert the stored document, photo or video. The expected result is byte-for-byte equality with the source file, including the same file length and SHA-256 content digest.\n\nA thumbnail shown in a list may be scaled for display, but that is only a preview. It does not change the encrypted original. Playback or opening still depends on a compatible viewer or media decoder on your device.",
                "quality original intact lossless byte for byte sha256 image photo video document compression resolution resize transcoding"
            )
        )
    ),
    HelpCategory(
        id = "recovery",
        title = "Encrypted recovery and large restores",
        subtitle = "Keeping a separate recovery copy and understanding restore requirements",
        icon = Icons.Default.Storage,
        accent = HelpGold,
        questions = listOf(
            HelpQuestion(
                "recovery-basics",
                "What is the encrypted recovery backup?",
                "Recovery is optional. It stores an encrypted copy in a folder you select on this device's internal shared storage or an SD card. The folder contains encrypted file payloads, an encrypted vault index and password-protected recovery-key envelopes; it is not cloud storage or automatic sync.\n\nKeep the folder intact and remember the password that last protected its recovery key. A factory reset, damaged or removed storage, or deletion of the recovery folder can still destroy that copy. For irreplaceable files, keep another independent backup as well.",
                "backup recovery folder sd card internal storage optional cloud sync reinstall"
            ),
            HelpQuestion(
                "restore-steps",
                "How do I restore an encrypted recovery backup?",
                "After reinstalling, choose the parent folder that contains the “Private Space Vault” folder, then enter the password that last protected the recovery key. The app verifies the recovery index and key, authenticates the encrypted records, and rebuilds the app-private working vault.\n\nRestoring replaces the current on-device vault; it does not merge newer local files with the recovery copy. Keep the recovery folder unchanged until the restore completes and you have checked that your files are present. After restore, you can use Change password if you want a different app password.",
                "restore reinstall password existing backup folder parent verify replace merge"
            ),
            HelpQuestion(
                "one-tb",
                "Can a recovery backup of up to 1 TB be restored safely?",
                "Private Space has no app-defined 1 TB file-size ceiling. Its encryption and recovery paths use bounded-memory streaming and 64-bit byte counters, so the design is not limited by the amount of a file that can fit in RAM. Before restoring, the app calculates the encrypted working-copy requirement and checks available app-private device storage, including transaction overhead.\n\nThat does not guarantee a successful 1 TB restore on every phone. The device needs enough additional free internal storage for the new encrypted working copy and commit overhead while the existing vault is preserved. The selected storage provider, SD card or filesystem, Android version, device reliability and power conditions can also limit the operation. A full 1 TB end-to-end restore has not been validated on every target device. Keep the recovery folder untouched until you have confirmed the restored files.",
                "1tb terabyte huge large backup restore maximum size storage disk space memory successful safely device"
            )
        )
    )
)

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var expandedQuestions by remember { mutableStateOf(setOf("what-is", "stolen-app", "algorithms")) }

    val filteredCategories = remember(query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            HelpCategories
        } else {
            HelpCategories.mapNotNull { category ->
                val matchingQuestions = category.questions.filter { question ->
                    question.question.contains(normalized, ignoreCase = true) ||
                        question.answer.contains(normalized, ignoreCase = true) ||
                        question.keywords.contains(normalized, ignoreCase = true) ||
                        category.title.contains(normalized, ignoreCase = true)
                }
                if (matchingQuestions.isEmpty()) null else category.copy(questions = matchingQuestions)
            }
        }
    }
    val resultCount = filteredCategories.sumOf { it.questions.size }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF101C30), HelpInk, Color(0xFF070B13))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back to vault", tint = HelpWhite)
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Help & security guide", color = HelpWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Understand your vault and its protection", color = HelpMuted, fontSize = 11.sp)
                }
                Surface(
                    color = HelpCyan.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(30.dp),
                    border = BorderStroke(1.dp, HelpCyan.copy(alpha = 0.25f))
                ) {
                    Text(
                        "GUIDE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = HelpCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = HelpPanel,
                        shape = RoundedCornerShape(23.dp),
                        border = BorderStroke(1.dp, HelpCyan.copy(alpha = 0.20f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(54.dp),
                                color = HelpCyan.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(17.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = HelpCyan, modifier = Modifier.size(29.dp))
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Know what is protected.", color = HelpWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Straight answers about features, encryption, recovery and the limits of phone-level protection.",
                                    color = HelpMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(15.dp),
                            placeholder = { Text("Search questions or topics", color = HelpMuted) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = HelpMuted) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = HelpMuted)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = HelpWhite,
                                unfocusedTextColor = HelpWhite,
                                cursorColor = HelpCyan,
                                focusedBorderColor = HelpCyan.copy(alpha = 0.65f),
                                unfocusedBorderColor = HelpLine,
                                focusedContainerColor = HelpPanel,
                                unfocusedContainerColor = HelpPanel
                            )
                        )
                        Text(
                            if (query.isBlank()) HelpCategories.sumOf { it.questions.size }.toString() + " answers across " + HelpCategories.size + " topics"
                            else "$resultCount matching answers",
                            color = HelpMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                }

                if (filteredCategories.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = HelpPanel,
                            shape = RoundedCornerShape(19.dp),
                            border = BorderStroke(1.dp, HelpLine)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Icon(Icons.Default.SearchOff, contentDescription = null, tint = HelpMuted, modifier = Modifier.size(32.dp))
                                Text("No matching answers", color = HelpWhite, fontWeight = FontWeight.SemiBold)
                                Text("Try words such as password, recovery, AES, theft or video.", color = HelpMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                                TextButton(onClick = { query = "" }) { Text("Show all answers", color = HelpCyan) }
                            }
                        }
                    }
                } else {
                    items(filteredCategories, key = { it.id }) { category ->
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(38.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = category.accent.copy(alpha = 0.13f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(category.icon, contentDescription = null, tint = category.accent, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(category.title, color = HelpWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(category.subtitle, color = HelpMuted, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                                Surface(color = HelpPanelRaised, shape = RoundedCornerShape(30.dp)) {
                                    Text(
                                        category.questions.size.toString(),
                                        color = category.accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            category.questions.forEach { question ->
                                val expanded = question.id in expandedQuestions
                                Surface(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            expandedQuestions = if (expanded) {
                                                expandedQuestions - question.id
                                            } else {
                                                expandedQuestions + question.id
                                            }
                                        },
                                    color = if (expanded) HelpPanelRaised else HelpPanel,
                                    shape = RoundedCornerShape(17.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (expanded) category.accent.copy(alpha = 0.35f) else HelpLine.copy(alpha = 0.8f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                                        verticalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                question.question,
                                                modifier = Modifier.weight(1f),
                                                color = HelpWhite,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                lineHeight = 18.sp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                if (expanded) Icons.Default.ChevronUp else Icons.Default.ChevronDown,
                                                contentDescription = if (expanded) "Collapse answer" else "Expand answer",
                                                tint = if (expanded) category.accent else HelpMuted,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                        if (expanded) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = HelpInk.copy(alpha = 0.50f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text(
                                                    question.answer,
                                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                    color = HelpMuted,
                                                    fontSize = 12.sp,
                                                    lineHeight = 19.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = HelpGold.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, HelpGold.copy(alpha = 0.24f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HelpGold, modifier = Modifier.size(20.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("A practical security reminder", color = HelpWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Keep your phone protected with a strong screen lock, use a long unique vault password, lock Private Space before leaving your phone unattended, and keep your recovery folder in a safe place. Be deliberate when opening, sharing or exporting files.",
                                    color = HelpMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
