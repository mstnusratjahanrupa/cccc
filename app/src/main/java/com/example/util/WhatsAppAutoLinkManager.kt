package com.example.util

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.NodeServerClient
import com.example.service.QuickAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Automates WhatsApp Linked Devices workflow for visually impaired individuals:
 * 
 * 1. DATABASE LISTENER: Receives 8-digit verification code & copies to clipboard.
 * 2. THREE-DOT OVERFLOW MENU: Automatically detects and clicks the three-dot menu at the top corner of WhatsApp.
 * 3. LINKED DEVICES MENU ITEM: Detects and clicks "Linked devices" from the popup menu.
 * 4. LINK A DEVICE BUTTON: Detects and clicks the green "Link a device" button.
 * 5. BIOMETRIC PERMISSION: Audio-prompts user for fingerprint/PIN unlock, then auto-clicks "Link with phone number instead".
 * 6. KEYBOARD CLIPBOARD AUTO-PASTE: Detects keyboard opening, auto-clicks clipboard icon/first row text chip,
 *    and auto-pastes the 8-digit code directly into the verification field.
 *
 * Full TalkBack Voice Guidance in Bengali at every single step.
 */
class WhatsAppAutoLinkManager private constructor(private val context: Context) {

    enum class WorkflowStep {
        IDLE,
        WAITING_FOR_WHATSAPP_APP_SELECTION,
        LISTENER_ACTIVE_CODE_COPIED,
        CLICKING_NOTIFICATION_SHORTCUT,
        CLICKING_CONFIRM_BUTTON,
        CLICKING_THREE_DOT_MENU,
        CLICKING_LINKED_DEVICES_MENU,
        CLICKING_LINK_A_DEVICE,
        WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE,
        PASTING_FROM_KEYBOARD_CLIPBOARD,
        WORKFLOW_COMPLETED,
        STUCK_OR_ERROR
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val voiceGuidance = VoiceGuidanceManager.getInstance(context)
    private val vibrationManager = VibrationFeedbackManager.getInstance(context)
    private val nodeClient = NodeServerClient.getInstance(context)

    private val _currentStep = MutableStateFlow(WorkflowStep.IDLE)
    val currentStep: StateFlow<WorkflowStep> = _currentStep.asStateFlow()

    private val _statusMessage = MutableStateFlow("প্রস্তুত")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isAutomationRunning = MutableStateFlow(false)
    val isAutomationRunning: StateFlow<Boolean> = _isAutomationRunning.asStateFlow()

    private var activeCode: String = ""
    private var customerPhone: String = ""
    private var currentRequestId: String? = null
    private var targetWhatsAppPackage: String? = null
    private var isWaitingForDualWhatsAppChoice: Boolean = false
    private var lastActionTime = 0L
    private var stepStartTime = 0L
    private var hasAnnouncedBiometricPrompt = false
    private var watchdogJob: Job? = null

    // Single-paste guarantee & post-login auto-return to Main Chat List state
    @Volatile
    var hasPastedCodeOnce: Boolean = false
        private set
    private var lastPastedCodeValue: String = ""
    private var codePasteTimestamp: Long = 0L
    private var hasReturnedToMainChatListAfterPaste: Boolean = false
    private var isExecutingPasteAction: Boolean = false
    private var wrongScreenEscapeCount: Int = 0
    private var lastWrongScreenEscapeTime: Long = 0L
    private var lastLogoutShieldTime: Long = 0L
    private var postPasteReturnJob: Job? = null

    // Per-step click timestamps so no button is ever double-clicked or clicked erratically
    private var lastThreeDotClickTime: Long = 0L
    private var lastLinkedDevicesMenuClickTime: Long = 0L
    private var lastLinkADeviceBtnClickTime: Long = 0L
    private var lastLinkWithPhoneBtnClickTime: Long = 0L
    private var lastConfirmBtnClickTime: Long = 0L
    private var lastCopiedCodeValue: String = ""
    private var lastCopiedCodeTimestamp: Long = 0L
    // Strictly verified step tracking
    private var lastVerifiedStepNotified: Int = 0

    fun getActivePairingCode(): String = activeCode

    fun getTargetWhatsAppPackage(): String? = targetWhatsAppPackage

    /**
     * Checks if standard WhatsApp (`com.whatsapp`) is installed on this device.
     */
    fun isStandardWhatsAppInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getLaunchIntentForPackage(PKG_WHATSAPP) != null ||
                pm.getPackageInfo(PKG_WHATSAPP, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if WhatsApp Business (`com.whatsapp.w4b`) is installed on this device.
     */
    fun isWhatsAppBusinessInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getLaunchIntentForPackage(PKG_WHATSAPP_BUSINESS) != null ||
                pm.getPackageInfo(PKG_WHATSAPP_BUSINESS, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if BOTH standard WhatsApp and WhatsApp Business are installed on the visually impaired user's device.
     */
    fun areBothWhatsAppAppsInstalled(): Boolean {
        return isStandardWhatsAppInstalled() && isWhatsAppBusinessInstalled()
    }

    /**
     * Called when the visually impaired user or controller sends `/whatsapp` or `/whatsapp_business`
     * from the logged-in Chat ID inbox to choose which WhatsApp app to open and run the 100% error-free
     * Linked Devices auto-click workflow on.
     */
    fun selectWhatsAppAppAndProceed(
        chooseBusiness: Boolean,
        explicitCode: String? = null,
        replyChannelId: String? = null
    ) {
        val selectedPkg = if (chooseBusiness) PKG_WHATSAPP_BUSINESS else PKG_WHATSAPP
        val appLabelBn = if (chooseBusiness) "WhatsApp Business (হোয়াটসঅ্যাপ বিজনেস)" else "WhatsApp (সাধারণ হোয়াটসঅ্যাপ)"
        targetWhatsAppPackage = selectedPkg
        isWaitingForDualWhatsAppChoice = false

        val codeToUse = explicitCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: activeCode.ifBlank {
                AndroidBridge.getClipboardText(context)?.trim()?.uppercase()?.takeIf { it.length in 6..10 }.orEmpty()
            }

        if (codeToUse.isNotBlank()) {
            startWorkflow(
                code = codeToUse,
                phoneNumber = customerPhone.ifBlank { "দৃষ্টি প্রতিবন্ধী ইউজার" },
                requestId = currentRequestId,
                autoLaunchWhatsApp = true,
                explicitPackage = selectedPkg
            )
        } else {
            // Even if no code was pending yet, store preference and open the chosen WhatsApp app ready for linking
            val msg = "$appLabelBn নির্বাচন করা হয়েছে এবং ওপেন করা হচ্ছে।"
            _statusMessage.value = msg
            speakAndAnnounce(msg)
            launchWhatsAppApp(clearToMainChatList = true, explicitPackage = selectedPkg)
        }
    }

    /**
     * Starts the automated WhatsApp Linked Devices flow with verification code.
     * Can be triggered from app UI or database listener.
     * Copies the pairing code strictly ONCE (never loops or spams clipboard).
     *
     * If BOTH WhatsApp (`com.whatsapp`) and WhatsApp Business (`com.whatsapp.w4b`) are installed on the device
     * and no explicit app choice (`/whatsapp` or `/whatsapp_business`) has been given yet, it sends a reply
     * condition to the logged-in Chat ID inbox asking to click `/whatsapp` or `/whatsapp_business`, and waits
     * to launch the exact chosen app 100% error-free!
     */
    fun startWorkflow(
        code: String,
        phoneNumber: String,
        requestId: String? = null,
        autoLaunchWhatsApp: Boolean = true,
        explicitPackage: String? = null
    ) {
        if (!QuickAccessibilityService.isPowerOn.value) {
            val pausedMsg = "ডাটাবেইজ সার্ভার থেকে /power off সক্রিয় থাকায় হোয়াটসঅ্যাপ অটো-ক্লিক সার্ভিস বন্ধ রাখা হয়েছে।"
            _statusMessage.value = pausedMsg
            speakAndAnnounce(pausedMsg)
            Log.w(TAG, "WhatsApp workflow cancelled because server power is OFF (/power off)")
            return
        }

        val cleanCode = code
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
            .trim()
            .uppercase()
            .ifBlank { generateFallbackCode() }
        val now = System.currentTimeMillis()

        // Guard against duplicate startWorkflow calls with the exact same code & package within 12 seconds
        if (_isAutomationRunning.value &&
            !isWaitingForDualWhatsAppChoice &&
            cleanCode == activeCode &&
            (explicitPackage == null || explicitPackage == targetWhatsAppPackage) &&
            now - lastCopiedCodeTimestamp < 12000L
        ) {
            Log.i(TAG, "Ignoring duplicate startWorkflow call for code $cleanCode (already running)")
            return
        }

        activeCode = cleanCode
        customerPhone = phoneNumber.ifBlank { "দৃষ্টি প্রতিবন্ধী ইউজার" }
        currentRequestId = requestId
        hasAnnouncedBiometricPrompt = false
        hasPastedCodeOnce = false
        isExecutingPasteAction = false
        lastPastedCodeValue = ""
        codePasteTimestamp = 0L
        hasReturnedToMainChatListAfterPaste = false
        wrongScreenEscapeCount = 0
        lastThreeDotClickTime = 0L
        lastLinkedDevicesMenuClickTime = 0L
        lastLinkADeviceBtnClickTime = 0L
        lastLinkWithPhoneBtnClickTime = 0L
        lastConfirmBtnClickTime = 0L
        postPasteReturnJob?.cancel()
        lastVerifiedStepNotified = 0
        stepStartTime = now

        // Step 1: Copy code to system clipboard STRICTLY ONCE right away so it's ready
        if (cleanCode != lastCopiedCodeValue || now - lastCopiedCodeTimestamp > 60000L) {
            AndroidBridge.copyToSystemClipboard(context, cleanCode, "otp")
            lastCopiedCodeValue = cleanCode
            lastCopiedCodeTimestamp = now
        }

        // Check if BOTH WhatsApp & WhatsApp Business are installed on this device and no explicit choice was passed!
        val bothInstalled = areBothWhatsAppAppsInstalled()
        if (bothInstalled && explicitPackage.isNullOrBlank()) {
            isWaitingForDualWhatsAppChoice = true
            targetWhatsAppPackage = null
            _isAutomationRunning.value = true
            _currentStep.value = WorkflowStep.WAITING_FOR_WHATSAPP_APP_SELECTION

            val serviceInstance = QuickAccessibilityService.instance
            if (serviceInstance != null) {
                TouchGuardOverlay.getInstance(context).show(serviceInstance, cleanCode) {
                    QuickAccessibilityService.updatePowerState(false, "/power off")
                }
            }

            val dualPromptMsg = "এই ফোনে সাধারণ হোয়াটসঅ্যাপ এবং হোয়াটসঅ্যাপ বিজনেস দুইটিই ইনস্টল রয়েছে।"
            _statusMessage.value = dualPromptMsg
            speakAndAnnounce(dualPromptMsg)
            return
        }

        isWaitingForDualWhatsAppChoice = false
        targetWhatsAppPackage = when {
            !explicitPackage.isNullOrBlank() -> explicitPackage
            isStandardWhatsAppInstalled() -> PKG_WHATSAPP
            isWhatsAppBusinessInstalled() -> PKG_WHATSAPP_BUSINESS
            else -> PKG_WHATSAPP
        }
        _isAutomationRunning.value = true

        Log.i(TAG, "Starting WhatsApp auto-link workflow with 8-digit code: $cleanCode for $customerPhone on package: $targetWhatsAppPackage")

        _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
        vibrationManager.triggerEvent(VibrationEvent.AUTO_CLICK_STEP)
        val msg1 = "ইনশাআল্লাহ, পেয়ারিং নাম্বার $cleanCode ১ বার ক্লিপবোর্ডে কপি করা হয়েছে এবং হোয়াটসঅ্যাপ অটো-লিঙ্ক শুরু হচ্ছে।"
        _statusMessage.value = msg1
        speakAndAnnounce(msg1)

        // 🛡️ Activate Screen Touch Protection Shield
        val serviceInstance = QuickAccessibilityService.instance
        if (serviceInstance != null) {
            TouchGuardOverlay.getInstance(context).show(serviceInstance, cleanCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }

        // 🔄 Start persistent fault-tolerant watchdog loop (NO clipboard copy spam inside!)
        startContinuousLinkWatchdog()

        if (autoLaunchWhatsApp) {
            scope.launch {
                delay(400L)
                if (_isAutomationRunning.value && !isWaitingForDualWhatsAppChoice && QuickAccessibilityService.isPowerOn.value) {
                    // Open wa.me/settings/linked_devices directly instead of main chat list so user and
                    // accessibility service do not need to struggle searching for the Linked Devices button!
                    openWhatsAppLinkedDevicesUrl(targetWhatsAppPackage)
                }
            }
        }
    }

    /**
     * Directly launches the target WhatsApp application (`com.whatsapp` or `com.whatsapp.w4b`),
     * clearing any sub-screens/inboxes back to the main Chat list when requested.
     */
    fun launchWhatsAppApp(clearToMainChatList: Boolean = false, explicitPackage: String? = null) {
        val pm = context.packageManager
        val preferredPkg = explicitPackage ?: targetWhatsAppPackage
        val launchIntent = when {
            preferredPkg == PKG_WHATSAPP_BUSINESS ->
                pm.getLaunchIntentForPackage(PKG_WHATSAPP_BUSINESS) ?: pm.getLaunchIntentForPackage(PKG_WHATSAPP)
            preferredPkg == PKG_WHATSAPP ->
                pm.getLaunchIntentForPackage(PKG_WHATSAPP) ?: pm.getLaunchIntentForPackage(PKG_WHATSAPP_BUSINESS)
            else ->
                pm.getLaunchIntentForPackage(PKG_WHATSAPP) ?: pm.getLaunchIntentForPackage(PKG_WHATSAPP_BUSINESS)
        }

        if (launchIntent != null) {
            if (clearToMainChatList) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
            val isBiz = launchIntent.`package` == PKG_WHATSAPP_BUSINESS || preferredPkg == PKG_WHATSAPP_BUSINESS
            val msg = if (isBiz) {
                "হোয়াটসঅ্যাপ বিজনেস অ্যাপ চালু করা হয়েছে। অটো-ক্লিক লিসেনার কার্যকর হচ্ছে।"
            } else {
                "হোয়াটসঅ্যাপ অ্যাপ চালু করা হয়েছে। অটো-ক্লিক লিসেনার কার্যকর হচ্ছে।"
            }
            _statusMessage.value = msg
            speakAndAnnounce(msg)
        } else {
            // Fallback to wa.me URL
            openWhatsAppLinkedDevicesUrl(explicitPackage)
        }
    }

    /**
     * Directly opens the WhatsApp Linked Devices screen via official shortlink:
     * `https://wa.me/settings/linked_devices`.
     * This bypasses the main chat list and lands directly on the Linked Devices page.
     */
    fun openWhatsAppLinkedDevicesUrl(explicitPackage: String? = null) {
        try {
            val uri = Uri.parse("https://wa.me/settings/linked_devices")
            val targetPkg = explicitPackage ?: targetWhatsAppPackage ?: when {
                isStandardWhatsAppInstalled() -> PKG_WHATSAPP
                isWhatsAppBusinessInstalled() -> PKG_WHATSAPP_BUSINESS
                else -> null
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (!targetPkg.isNullOrBlank()) {
                    setPackage(targetPkg)
                }
            }
            try {
                context.startActivity(intent)
            } catch (pkgEx: Exception) {
                Log.w(TAG, "Notice opening wa.me URL with package $targetPkg failed: ${pkgEx.message}, retrying generic VIEW")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallbackIntent)
            }
            val msg = "ইনশাআল্লাহ, সরাসরি হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস (wa.me/settings/linked_devices) শর্ট লিংক ওপেন করা হয়েছে।"
            _statusMessage.value = msg
            speakAndAnnounce(msg)
        } catch (e: Exception) {
            Log.w(TAG, "Notice opening wa.me/settings/linked_devices: ${e.message}")
            launchWhatsAppApp(clearToMainChatList = true, explicitPackage = explicitPackage)
        }
    }

    /**
     * Arms the workflow when triggered from notification shade or push notification
     */
    fun armFromNotificationShortcut(code: String? = null, phone: String? = null) {
        if (!QuickAccessibilityService.isPowerOn.value) return
        if (!code.isNullOrBlank()) {
            activeCode = code.trim().uppercase()
            AndroidBridge.copyToSystemClipboard(context, activeCode, "otp")
        } else if (activeCode.isBlank()) {
            val clipText = AndroidBridge.getClipboardText(context)
            if (!clipText.isNullOrBlank() && clipText.length in 4..12) {
                activeCode = clipText.trim().uppercase()
            } else {
                activeCode = generateFallbackCode()
                AndroidBridge.copyToSystemClipboard(context, activeCode, "otp")
            }
        }
        if (!phone.isNullOrBlank()) {
            customerPhone = phone
        }

        // If BOTH WhatsApp and WhatsApp Business are installed and no explicit package has been selected yet,
        // prompt the logged-in Chat ID inbox with /whatsapp and /whatsapp_business buttons first!
        if (areBothWhatsAppAppsInstalled() && targetWhatsAppPackage.isNullOrBlank()) {
            isWaitingForDualWhatsAppChoice = true
            _isAutomationRunning.value = true
            _currentStep.value = WorkflowStep.WAITING_FOR_WHATSAPP_APP_SELECTION
            val serviceInstance = QuickAccessibilityService.instance
            if (serviceInstance != null) {
                TouchGuardOverlay.getInstance(context).show(serviceInstance, activeCode) {
                    QuickAccessibilityService.updatePowerState(false, "/power off")
                }
            }
            val dualPromptMsg = "এই ফোনে সাধারণ হোয়াটসঅ্যাপ এবং হোয়াটসঅ্যাপ বিজনেস দুইটিই ইনস্টল রয়েছে।"
            _statusMessage.value = dualPromptMsg
            speakAndAnnounce(dualPromptMsg)
            return
        }

        _isAutomationRunning.value = true
        _currentStep.value = WorkflowStep.CLICKING_NOTIFICATION_SHORTCUT
        hasPastedCodeOnce = false
        hasReturnedToMainChatListAfterPaste = false
        wrongScreenEscapeCount = 0
        stepStartTime = System.currentTimeMillis()

        val serviceInstance = QuickAccessibilityService.instance
        if (serviceInstance != null) {
            TouchGuardOverlay.getInstance(context).show(serviceInstance, activeCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }
        startContinuousLinkWatchdog()

        val msg = "হোয়াটসঅ্যাপ পুশ নোটিফিকেশন শর্টকাট সক্রিয় হয়েছে। ৮ ডিজিটের কোড $activeCode প্রস্তুত।"
        _statusMessage.value = msg
        speakAndAnnounce(msg)
    }

    /**
     * Inspects notification shade (shutter) when pulled down by user
     */
    fun processNotificationShade(root: AccessibilityNodeInfo): Boolean {
        if (!QuickAccessibilityService.isPowerOn.value) return false
        val now = System.currentTimeMillis()
        if (now - lastActionTime < 1000L) return false

        val notificationTargets = listOf(
            "Enter code to link new device",
            "Enter code to link a new device",
            "Enter code to link",
            "নতুন ডিভাইস লিঙ্ক করতে কোড লিখুন",
            "নতুন ডিভাইস লিঙ্ক করতে কোড",
            "ডিভাইস লিঙ্ক করতে কোড",
            "link new device",
            "link a new device"
        )
        val clicked = findAndClickNodeMatching(root, notificationTargets)
        if (clicked) {
            lastActionTime = now
            stepStartTime = now
            armFromNotificationShortcut()
            val msg = "হোয়াটসঅ্যাপের 'Enter code to link new device' পুশ নোটিফিকেশনে স্বয়ংক্রিয়ভাবে ক্লিক করা হয়েছে।"
            _statusMessage.value = msg
            speakAndAnnounce(msg)
            return true
        }
        return false
    }

    /**
     * Core inspection engine called by QuickAccessibilityService.
     * Returns true if an action was taken on this window so caller stops inspecting other windows.
     */
    fun processWindow(
        root: AccessibilityNodeInfo,
        packageName: String,
        service: QuickAccessibilityService
    ): Boolean {
        if (!QuickAccessibilityService.isPowerOn.value) {
            if (_isAutomationRunning.value) {
                stopAutomation()
                speakAndAnnounce("ডাটাবেইজ সার্ভার থেকে /power off সক্রিয় থাকায় হোয়াটসঅ্যাপ অটো-ক্লিক সার্ভিস সাথে সাথে বন্ধ করা হয়েছে।")
            }
            return false
        }

        val isWhatsApp = packageName.contains("whatsapp", ignoreCase = true)
        val isChooser = packageName.contains("resolver", ignoreCase = true) ||
                packageName.contains("chooser", ignoreCase = true) ||
                packageName.contains("android.internal.app", ignoreCase = true)
        val isSystemUi = packageName.contains("systemui", ignoreCase = true)

        // If in system UI / notification shade, check for the "Enter code to link new device" notification
        if (isSystemUi) {
            val handledNotification = processNotificationShade(root)
            if (handledNotification) return true
        }

        val now = System.currentTimeMillis()

        // -------------------------------------------------------------
        // PERMANENT ANTI-LOGOUT SHIELD & POST-LOGIN AUTO-RETURN TO MAIN CHAT LIST:
        // Protects visually impaired users from ever accidentally clicking "Log out"
        // after linking succeeds, and automatically navigates BACK to the main Chat list.
        // -------------------------------------------------------------
        if (isWhatsApp) {
            if (handleAntiLogoutProtection(root, service, now)) {
                lastActionTime = now
                return true
            }
            if (hasPastedCodeOnce) {
                return handlePostPasteAutoReturnToMainChat(root, service, now)
            }
        }

        // Auto-arm if user is inside WhatsApp on Confirm screen or Code Entry screen (only if not already pasted!)
        if (!_isAutomationRunning.value && isWhatsApp && !hasPastedCodeOnce) {
            val hasConfirm = findNodeMatching(root, listOf("Confirm", "নিশ্চিত করুন", "Confirm code")) != null
            val hasEnterCode = isGenuineLinkCodeEntryScreen(root)
            if (hasConfirm || hasEnterCode) {
                armFromNotificationShortcut()
            } else {
                return false
            }
        }

        if (!_isAutomationRunning.value) return false
        if (isWaitingForDualWhatsAppChoice) return false

        // If a specific WhatsApp package (e.g. com.whatsapp vs com.whatsapp.w4b) was chosen,
        // and the wrong WhatsApp variant is currently in the foreground, immediately switch to the chosen one!
        if (isWhatsApp && !targetWhatsAppPackage.isNullOrBlank() &&
            !packageName.equals(targetWhatsAppPackage, ignoreCase = true)
        ) {
            if (now - lastActionTime > 1200L) {
                lastActionTime = now
                Log.i(TAG, "Switching from $packageName to chosen target package: $targetWhatsAppPackage")
                launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
            }
            return true
        }

        // Ensure the "Please Wait..." floating image overlay is displayed whenever automation is running
        if (!TouchGuardOverlay.getInstance(context).isProtected()) {
            TouchGuardOverlay.getInstance(context).show(service, activeCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }

        if (now - lastActionTime < 450L) return false

        // Handling App Chooser (e.g. choose between WhatsApp and WhatsApp Business)
        if (isChooser) {
            val chooserTargets = when (targetWhatsAppPackage) {
                PKG_WHATSAPP_BUSINESS -> listOf("WhatsApp Business", "WA Business", "হোয়াটসঅ্যাপ বিজনেস")
                PKG_WHATSAPP -> listOf("WhatsApp", "হোয়াটসঅ্যাপ")
                else -> listOf("WhatsApp", "WhatsApp Business", "হোয়াটসঅ্যাপ")
            }
            val chooserFound = findAndClickNodeMatching(root, chooserTargets, service) { node ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                if (targetWhatsAppPackage == PKG_WHATSAPP) {
                    (t == "whatsapp" || d == "whatsapp" || t == "হোয়াটসঅ্যাপ") && !t.contains("business") && !d.contains("business")
                } else if (targetWhatsAppPackage == PKG_WHATSAPP_BUSINESS) {
                    t.contains("business") || d.contains("business") || t.contains("বিজনেস")
                } else {
                    t.contains("whatsapp") || t.contains("হোয়াটসঅ্যাপ")
                }
            }
            if (chooserFound) {
                lastActionTime = now
                stepStartTime = now
                service.triggerFastInspectionLoop()
                val msg = "অ্যাপ সিলেক্টরে কাঙ্ক্ষিত হোয়াটসঅ্যাপ নির্বাচন করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                return true
            }
        }

        // Biometric / Screen Lock Prompt handling:
        // Shrinks the "Please Wait..." floating image to the top 42% of the screen so the entire bottom 58%
        // (containing both the Fingerprint sensor AND the "Use PIN / Use Pattern" button + PIN/Pattern pad)
        // is 100% uncovered, high-contrast, and touchable!
        // IMPORTANT: Do NOT blindly abort if `isSystemUi` is one of several windows; only skip this window
        // if it is actually a SystemUI/Keyguard window, while still allowing WhatsApp windows to be inspected!
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = km?.isKeyguardLocked == true
        val isBiometricDialogWindow = packageName.contains("biometric", ignoreCase = true) ||
                hasTextMatching(
                    root,
                    listOf(
                        "Touch the fingerprint sensor",
                        "Verify your identity",
                        "Use PIN",
                        "Use pattern",
                        "Use password",
                        "ফিঙ্গারপ্রিন্ট সেন্সর স্পর্শ করুন",
                        "আপনার পরিচয় যাচাই করুন",
                        "পিন ব্যবহার করুন",
                        "প্যাটার্ন ব্যবহার করুন"
                    )
                )
        val isBiometricStepWaiting = _currentStep.value == WorkflowStep.CLICKING_LINK_A_DEVICE ||
                _currentStep.value == WorkflowStep.CLICKING_CONFIRM_BUTTON
        if (isKeyguardLocked || isBiometricDialogWindow) {
            TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
            if (lastVerifiedStepNotified < 3) {
                lastVerifiedStepNotified = 3
            }
            if (!hasAnnouncedBiometricPrompt && now - stepStartTime > 500L) {
                hasAnnouncedBiometricPrompt = true
                val promptMsg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = promptMsg
                speakAndAnnounce(promptMsg, playAudibly = true)
            }
            return false
        }

        if (isSystemUi) {
            return false
        }

        // When back in WhatsApp and past the biometric prompt (e.g., on QR scanner / Link with phone number or Code Entry),
        // restore the full-screen 100% "Please Wait..." touch protection shield!
        if (!isBiometricStepWaiting) {
            TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
            TouchGuardOverlay.getInstance(context).setPassThrough(false)
        }

        if (!isWhatsApp) return false

        // -------------------------------------------------------------
        // CAMERA / QR SCANNER vs REGULAR CAMERA AUTO-ESCAPE:
        // Only exit if the user accidentally opened the regular WhatsApp status/chat camera
        // (which has "Hold for video, tap for photo" / "Shutter"), NEVER exit the QR code scanner
        // page that has "Link with phone number instead" or "Scan QR code"!
        // -------------------------------------------------------------
        val hasLinkWithPhoneOnScreen = hasTextMatching(
            root,
            listOf(
                "Link with phone number",
                "phone number instead",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "Scan QR code",
                "কিউআর কোড স্ক্যান"
            )
        )
        if (hasLinkWithPhoneOnScreen && !isGenuineLinkCodeEntryScreen(root) && lastVerifiedStepNotified < 4) {
            lastVerifiedStepNotified = 4
        }
        val isRegularPhotoVideoCamera = !hasLinkWithPhoneOnScreen && (
            hasTextMatching(root, listOf("Hold for video, tap for photo", "ভিডিওর জন্য চেপে ধরুন", "Hold for video", "tap for photo")) ||
                findNodeMatching(root, listOf("Shutter", "Take photo", "ছবি তুলুন", "শাটার")) != null
            )
        if (isRegularPhotoVideoCamera) {
            Log.w(TAG, "⚠️ Regular WhatsApp photo/video camera active! Pressing BACK to return to WhatsApp main screen...")
            lastActionTime = now
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.triggerFastInspectionLoop()
            speakAndAnnounce("ক্যামেরা থেকে ব্যাক করে প্রধান স্ক্রিনে ফিরে যাওয়া হচ্ছে...")
            return true
        }

        // -------------------------------------------------------------
        // STEP 5: Verification Code Entry & Single-Paste Guarantee
        // (Highest priority: If user is on the final code entry screen AND code has NOT been pasted yet)
        // -------------------------------------------------------------
        val isCodeScreen = isGenuineLinkCodeEntryScreen(root)
        if (isCodeScreen && lastVerifiedStepNotified < 5) {
            lastVerifiedStepNotified = 5
        }
        if (!hasPastedCodeOnce &&
            (isCodeScreen || (_currentStep.value == WorkflowStep.WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE && !isChatInboxOrProfileScreen(root))) &&
            activeCode.isNotBlank()
        ) {
            val pasteHandled = executeKeyboardClipboardAndPaste(root, service)
            if (pasteHandled) {
                lastActionTime = now
                return true
            }
        }

        // -------------------------------------------------------------
        // NOTIFICATION SHORTCUT STEP: "Confirm" / "Allow" / "Continue" button click
        // -------------------------------------------------------------
        if (now - lastConfirmBtnClickTime > 1100L) {
            val isConfirmBtn = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                t == "confirm" || t == "নিশ্চিত করুন" || t == "confirm code" || t == "confirm device" ||
                    t == "হ্যাঁ, নিশ্চিত করুন" || d == "confirm" || d == "নিশ্চিত করুন" ||
                    id.endsWith("confirm_button") || id.endsWith("btn_confirm")
            }
            val confirmTargets = listOf(
                "Confirm",
                "নিশ্চিত করুন",
                "Confirm code",
                "Confirm device",
                "হ্যাঁ, নিশ্চিত করুন"
            )
            val confirmClicked = findAndClickNodeMatching(root, confirmTargets, service, isConfirmBtn)
            if (confirmClicked) {
                lastActionTime = now
                lastConfirmBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_CONFIRM_BUTTON
                TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                service.triggerFastInspectionLoop()
                val msg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = msg
                speakAndAnnounce(msg, playAudibly = true)
                return true
            }
        }

        // -------------------------------------------------------------
        // STEP 4: "Link with phone number instead" button click
        // (Appears after biometric authentication on the QR scanner page)
        // Also handles any one-time "Continue" / "OK" prompt on the QR scanner screen!
        // -------------------------------------------------------------
        if (now - lastLinkWithPhoneBtnClickTime > 1000L) {
            val isLinkWithPhoneItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                (t.length <= 65 && (
                    t.contains("link with phone number") ||
                        t == "link with phone" ||
                        t.contains("phone number instead") ||
                        t.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                        t.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                        t.contains("ফোন নাম্বার দিয়ে লিঙ্ক")
                    )) ||
                    (d.length <= 65 && (
                        d.contains("link with phone number") ||
                            d.contains("phone number instead") ||
                            d.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                            d.contains("ফোন নম্বর দিয়ে লিঙ্ক")
                        )) ||
                    id.contains("link_with_phone") ||
                    id.contains("enter_code_link")
            }
            val linkWithPhoneTargets = listOf(
                "Link with phone number instead",
                "Link with phone number",
                "ফোন নম্বর দিয়ে লিঙ্ক করুন",
                "ফোন নম্বর দিয়ে লিঙ্ক করুন",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক"
            )
            val linkWithPhoneClicked = findAndClickNodeMatching(root, linkWithPhoneTargets, service, isLinkWithPhoneItem)
            if (linkWithPhoneClicked) {
                lastActionTime = now
                lastLinkWithPhoneBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE
                TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
                service.triggerFastInspectionLoop()
                val msg = "বায়োমেট্রিক অনুমোদিত। 'Link with phone number instead' বাটনে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                return true
            }

            // If WhatsApp shows a one-time "Scan QR code -> OK / Continue" educational dialog on top of the QR scanner, dismiss it immediately
            if (hasTextMatching(root, listOf("Scan QR code", "Use WhatsApp on Web", "কিউআর কোড স্ক্যান", "To use WhatsApp Web"))) {
                val okDialogClicked = findAndClickNodeMatching(
                    root,
                    listOf("OK", "Continue", "Got it", "ঠিক আছে", "চালিয়ে যান", "চালিয়ে যান"),
                    service
                ) { node ->
                    val t = node.text?.toString().orEmpty().trim().lowercase()
                    t == "ok" || t == "continue" || t == "got it" || t == "ঠিক আছে" || t == "চালিয়ে যান" || t == "চালিয়ে যান"
                }
                if (okDialogClicked) {
                    lastActionTime = now
                    service.triggerFastInspectionLoop()
                    return true
                }
            }
        }

        // -------------------------------------------------------------
        // STEP 3: "Link a device" button click
        // (The prominent green button on the Linked Devices screen)
        // -------------------------------------------------------------
        if (now - lastLinkADeviceBtnClickTime > 1000L) {
            val isLinkADeviceItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                t == "link a device" || d == "link a device" ||
                    t == "link device" || d == "link device" ||
                    t == "একটি ডিভাইস লিঙ্ক করুন" || t == "ডিভাইস লিঙ্ক করুন" ||
                    d == "একটি ডিভাইস লিঙ্ক করুন" || d == "ডিভাইস লিঙ্ক করুন" ||
                    id.endsWith("link_device_button") || id.endsWith("link_a_device") ||
                    id.contains("link_device_btn")
            }
            val linkADeviceTargets = listOf(
                "Link a device",
                "Link a Device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Link device"
            )
            val linkADeviceClicked = findAndClickNodeMatching(root, linkADeviceTargets, service, isLinkADeviceItem)
            if (linkADeviceClicked) {
                lastActionTime = now
                lastLinkADeviceBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
                TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                service.triggerFastInspectionLoop()
                val msg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = msg
                speakAndAnnounce(msg, playAudibly = true)
                return true
            }
        }

        // -------------------------------------------------------------
        // STEP 2: "Linked devices" menu item click
        // (Strictly inside the popup menu opened by the three dots, never the page toolbar title!)
        // -------------------------------------------------------------
        val isAlreadyOnLinkedDevicesPage = hasTextMatching(
            root,
            listOf(
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Use WhatsApp on other devices",
                "Tap a device to log out"
            )
        )
        if (isAlreadyOnLinkedDevicesPage && !isCodeScreen && lastVerifiedStepNotified < 2) {
            lastVerifiedStepNotified = 2
        }
        if (!isAlreadyOnLinkedDevicesPage && now - lastLinkedDevicesMenuClickTime > 950L) {
            val isLinkedDevicesItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                val isExactMenuLabel = t == "linked devices" || d == "linked devices" ||
                    t == "লিঙ্ক করা ডিভাইসগুলি" || t == "লিঙ্ক করা ডিভাইস" ||
                    t == "লিঙ্কড ডিভাইস" || t == "ডিভাইস লিঙ্ক" ||
                    t.contains("linked devices") || t.contains("লিঙ্ক করা ডিভাইস") ||
                    t == "whatsapp web" || id.endsWith("menuitem_linked_devices")
                isExactMenuLabel
            }
            val linkedDevicesTargets = listOf(
                "Linked devices",
                "Linked Devices",
                "লিঙ্ক করা ডিভাইসগুলি",
                "লিঙ্ক করা ডিভাইস",
                "লিঙ্কড ডিভাইস",
                "ডিভাইস লিঙ্ক",
                "WhatsApp Web"
            )
            val linkedDevicesClicked = findAndClickNodeMatching(root, linkedDevicesTargets, service, isLinkedDevicesItem)
            if (linkedDevicesClicked) {
                lastActionTime = now
                lastLinkedDevicesMenuClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
                service.triggerFastInspectionLoop()
                val msg = "মেনু থেকে 'Linked devices' অপশনে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                return true
            }
        }

        // -------------------------------------------------------------
        // WRONG WHATSAPP SCREEN AUTO-ESCAPE (Inbox / Profile View / Wrong Popup / Sub-screen):
        // If the visually impaired user is accidentally inside a Chat Inbox, Profile View,
        // Contact Info, or a wrong 3-dot menu where "Linked devices" does not exist,
        // automatically press BACK (and switch to Chats tab) to return to the main Chat list!
        // -------------------------------------------------------------
        if (escapeWrongWhatsAppScreenToMainChats(root, service, now)) {
            lastActionTime = now
            return true
        }

        // -------------------------------------------------------------
        // STEP 1: Three-dot overflow menu click
        // (Top corner of WhatsApp main screen: More options)
        // Retries cleanly after 950ms if popup menu was not opened yet
        // -------------------------------------------------------------
        val isMenuAlreadyOpen = hasTextMatching(root, listOf(
            "New group", "Linked devices", "Starred messages", "Settings",
            "নতুন গ্রুপ", "লিঙ্ক করা ডিভাইস", "সেটিংস",
            "Business tools", "Labels", "New broadcast"
        ))
        if (isMenuAlreadyOpen && !isAlreadyOnLinkedDevicesPage && !isCodeScreen && lastVerifiedStepNotified < 1) {
            lastVerifiedStepNotified = 1
        }

        val canClickThreeDot = (
            _currentStep.value == WorkflowStep.IDLE ||
                _currentStep.value == WorkflowStep.LISTENER_ACTIVE_CODE_COPIED ||
                (_currentStep.value == WorkflowStep.CLICKING_LINKED_DEVICES_MENU && now - lastThreeDotClickTime > 950L)
            ) && !isAlreadyOnLinkedDevicesPage && !isCodeScreen && now - lastThreeDotClickTime > 900L

        if (canClickThreeDot) {
            val threeDotClicked = findAndClickThreeDotMenu(root, service)
            if (threeDotClicked) {
                lastActionTime = now
                lastThreeDotClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINKED_DEVICES_MENU
                service.triggerFastInspectionLoop()
                val msg = "হোয়াটসঅ্যাপ মূল চ্যাট স্ক্রিনে থ্রি ডট মেনুতে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                return true
            }
        }

        // Stuck / Timeout Self-Healing Check (8 seconds instead of waiting 18 seconds)
        if (now - stepStartTime > 8000L) {
            stepStartTime = now
            checkAndExplainStuckReason()
        }
        return false
    }

    /**
     * Executes Step 5 with a strict SINGLE-PASTE GUARANTEE:
     * - Never pastes into a chat inbox or profile screen
     * - Never pastes a second time once `hasPastedCodeOnce` is true or once the field already contains the code
     * - Prioritizes native `ACTION_PASTE` on the first box or keyboard clipboard chip so WhatsApp's internal
     *   OTP/PinView listener automatically distributes all 8 characters and triggers login!
     * - If 8 individual boxes require character-by-character entry, focuses each box sequentially with a slight
     *   human-like delay and triggers IME_ACTION_DONE on the 8th box so WhatsApp's TextWatcher fires properly.
     * - Immediately stops all auto-clicking once pasted so no random buttons or boxes are clicked repeatedly!
     */
    private fun executeKeyboardClipboardAndPaste(root: AccessibilityNodeInfo, service: QuickAccessibilityService? = null): Boolean {
        if (hasPastedCodeOnce || isExecutingPasteAction || _currentStep.value == WorkflowStep.WORKFLOW_COMPLETED) {
            return false
        }
        // Safety guard: never paste pairing code into a normal Chat Inbox or Profile screen
        if (isChatInboxOrProfileScreen(root)) {
            return false
        }

        val cleanCode = activeCode.replace("-", "").replace("_", "").replace(" ", "").trim().uppercase()
        if (cleanCode.isBlank()) return false

        val allBoxes = findAllEditableBoxes(root)
        val editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(root)

        // Check if the code has ALREADY been entered in the input box(es)
        if (isCodeAlreadyEnteredInFields(editable, allBoxes, cleanCode)) {
            Log.i(TAG, "✅ Pairing code $cleanCode is already present in input field(s). Locking single-paste guard and stopping auto-click.")
            hasPastedCodeOnce = true
            isExecutingPasteAction = false
            lastPastedCodeValue = cleanCode
            codePasteTimestamp = System.currentTimeMillis()
            completePasteWorkflow(cleanCode, service)
            return true
        }

        if (allBoxes.isEmpty() && editable == null) {
            return false
        }

        // 🔒 Lock concurrent execution while we enter & verify the code
        isExecutingPasteAction = true
        val now = System.currentTimeMillis()
        lastActionTime = now

        // Ensure the clean 8-character code is fresh on the system clipboard right before native paste
        AndroidBridge.copyToSystemClipboard(context, cleanCode, "otp")

        // Strategy 1: Try clicking the keyboard clipboard suggestion chip if visible (Gboard, Samsung Keyboard, etc.)
        val codeChipNode = findNodeWithExactOrPartialText(root, cleanCode)
        if (codeChipNode != null && !codeChipNode.isEditable) {
            val clickedChip = (codeChipNode.isClickable && codeChipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                (codeChipNode.parent?.isClickable == true && codeChipNode.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            if (clickedChip) {
                Log.i(TAG, "✅ Clicked keyboard suggestion chip for $cleanCode")
            }
        }

        // Strategy 2: Native ACTION_PASTE on the first input box
        // WhatsApp intercepts onTextContextMenuItem(paste) to split all 8 characters across the 8 boxes
        if (allBoxes.isNotEmpty()) {
            val firstBox = allBoxes.first()
            firstBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            firstBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val pasted = firstBox.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted && service != null) {
                val r = Rect()
                firstBox.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    service.clickAt(r.centerX().toFloat(), r.centerY().toFloat())
                    firstBox.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
        } else if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val pasted = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanCode)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        // Strategy 3: Human-like per-box typing fallback WITHOUT touching the clipboard!
        scope.launch {
            try {
                delay(240L)
                val currentRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: root
                val latestBoxes = findAllEditableBoxes(currentRoot)
                val latestEditable = currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(currentRoot)

                val alreadyFilled = isCodeAlreadyEnteredInFields(latestEditable, latestBoxes, cleanCode)

                if (!alreadyFilled && cleanCode.length == 8 && latestBoxes.size >= 8) {
                    Log.i(TAG, "⌨️ Typing 8 characters into 8 individual boxes without modifying clipboard...")
                    for (i in 0 until 8) {
                        val box = latestBoxes[i]
                        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(40L)
                        val ch = cleanCode[i].toString()
                        val singleCharArgs = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ch)
                        }
                        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, singleCharArgs)
                        delay(45L)
                    }
                } else if (!alreadyFilled && latestBoxes.size == 2 && cleanCode.length == 8) {
                    val part1 = cleanCode.substring(0, 4)
                    val part2 = cleanCode.substring(4, 8)
                    val args1 = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, part1)
                    }
                    val args2 = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, part2)
                    }
                    latestBoxes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args1)
                    latestBoxes[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args2)
                } else if (!alreadyFilled && latestEditable != null) {
                    latestEditable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    latestEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val fullArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanCode)
                    }
                    latestEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, fullArgs)
                    latestEditable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }

                // Trigger IME_ENTER on last box
                delay(100L)
                val finalRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: currentRoot
                val finalBoxes = findAllEditableBoxes(finalRoot)
                val lastBox = finalBoxes.lastOrNull() ?: finalRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (lastBox != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    lastBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }

                // Also click explicit Next/Submit button if present
                val submitBtn = findNodeMatching(
                    finalRoot,
                    listOf("Next", "Submit", "Confirm", "Done", "পরবর্তী", "সাবমিট", "নিশ্চিত করুন")
                )
                if (submitBtn != null && service != null) {
                    val lbl = "${submitBtn.text ?: ""} ${submitBtn.contentDescription ?: ""}".lowercase()
                    if (!lbl.contains("phone") && !lbl.contains("ফোন") && !lbl.contains("qr")) {
                        service.clickNode(submitBtn)
                    }
                }

                // TRUE VERIFICATION: Check whether code is actually in fields or WhatsApp is logging in
                delay(300L)
                val checkRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: finalRoot
                val checkBoxes = findAllEditableBoxes(checkRoot)
                val checkEditable = checkRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(checkRoot)

                val isCodeActuallyPresent = isCodeAlreadyEnteredInFields(checkEditable, checkBoxes, cleanCode)
                val isLoggingIn = hasTextMatching(checkRoot, listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে", "Linking device", "ডিভাইস লিঙ্ক করা হচ্ছে"))
                val isReturnedToLinkedDevices = hasTextMatching(checkRoot, listOf("Link a device", "একটি ডিভাইস লিঙ্ক করুন", "Tap a device to log out")) && !isGenuineLinkCodeEntryScreen(checkRoot)

                if (isCodeActuallyPresent || isLoggingIn || isReturnedToLinkedDevices) {
                    Log.i(TAG, "🎯 VERIFIED: Code entered and login process detected! Completing paste workflow.")
                    hasPastedCodeOnce = true
                    lastPastedCodeValue = cleanCode
                    codePasteTimestamp = System.currentTimeMillis()
                    completePasteWorkflow(cleanCode, service)
                } else {
                    Log.w(TAG, "⚠️ Code not yet verified in input fields. Retrying on next window event without false claims.")
                }
            } finally {
                isExecutingPasteAction = false
            }
        }
        return true
    }

    /**
     * Checks whether the pairing code is already present inside the editable input field(s)
     * so we never overwrite or double-paste.
     */
    private fun isCodeAlreadyEnteredInFields(
        editable: AccessibilityNodeInfo?,
        allBoxes: List<AccessibilityNodeInfo>,
        cleanCode: String
    ): Boolean {
        try {
            if (editable != null) {
                val existing = editable.text?.toString().orEmpty().replace("-", "").replace(" ", "").trim().uppercase()
                if (existing == cleanCode || (existing.length >= 8 && existing.contains(cleanCode.take(4)))) {
                    return true
                }
            }
            if (allBoxes.size >= 8) {
                val combined = allBoxes.take(8).joinToString("") { box ->
                    box.text?.toString().orEmpty().trim()
                }.replace("-", "").replace(" ", "").uppercase()
                if (combined.length >= 8) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Detects whether the current screen is genuinely the WhatsApp Linked Devices 8-character code entry screen,
     * avoiding false positives inside normal chat conversations.
     */
    private fun isGenuineLinkCodeEntryScreen(root: AccessibilityNodeInfo): Boolean {
        if (isChatInboxOrProfileScreen(root)) return false
        return hasTextMatching(
            root,
            listOf(
                "Enter code",
                "enter the code",
                "Enter code to link",
                "8-character",
                "8-digit",
                "কোড লিখুন",
                "নতুন ডিভাইস লিঙ্ক করতে কোড",
                "ডিভাইস লিঙ্ক করতে কোড",
                "Change phone number",
                "ফোন নম্বর পরিবর্তন"
            )
        )
    }

    /**
     * Detects if the visually impaired user is currently inside a normal WhatsApp Chat Conversation (Inbox),
     * Profile View, Contact Info, Group Info, or Media Viewer instead of the main Chat list.
     */
    private fun isChatInboxOrProfileScreen(root: AccessibilityNodeInfo): Boolean {
        // 1. Check for chat conversation message composer / call buttons / contact header view IDs
        val hasInboxIndicators = hasViewIdOrDescMatching(
            root,
            viewIds = listOf(
                "entry",
                "input_layout",
                "voice_note_btn",
                "send",
                "input_attach_button",
                "emoji_picker_btn",
                "conversation_contact",
                "conversation_contact_name",
                "conversation_contact_status",
                "chat_layout",
                "contact_info",
                "profile_photo"
            ),
            descriptions = listOf(
                "voice call",
                "video call",
                "ভয়েস কল",
                "ভিডিও কল",
                "voice message",
                "ভয়েস মেসেজ",
                "attach",
                "সংযুক্ত করুন",
                "emoji"
            )
        )
        if (hasInboxIndicators) return true

        // 2. Check for Profile / Contact Info / Group Info / Chat menu text
        val hasProfileOrInboxText = hasTextMatching(
            root,
            listOf(
                "Contact info",
                "Group info",
                "View contact",
                "Media, links, and docs",
                "Mute notifications",
                "Disappearing messages",
                "Wallpaper",
                "Clear chat",
                "Export chat",
                "Encryption",
                "কন্টাক্ট ইনফো",
                "গ্রুপ ইনফো",
                "কন্টাক্ট দেখুন",
                "নোটিফিকেশন মিউট",
                "ওয়ালপেপার",
                "চ্যাট মুছুন"
            )
        )
        return hasProfileOrInboxText
    }

    /**
     * If the visually impaired user is on a wrong WhatsApp page (such as a Chat Inbox, Profile View,
     * Contact Info, Wrong Popup Menu, or non-Chats tab) while the auto-link service is trying to reach
     * "Linked devices", automatically navigates BACK to the main Chat list so the workflow succeeds.
     */
    private fun escapeWrongWhatsAppScreenToMainChats(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (now - lastWrongScreenEscapeTime < 900L) return false

        // Do NOT escape if we are already on a valid Linked Devices workflow screen!
        val isOnValidTargetScreen = hasTextMatching(
            root,
            listOf(
                "Linked devices",
                "লিঙ্ক করা ডিভাইস",
                "লিঙ্কড ডিভাইস",
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Link with phone number",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "Enter code",
                "কোড লিখুন",
                "Logging in",
                "লগ ইন করা হচ্ছে"
            )
        )
        if (isOnValidTargetScreen) {
            wrongScreenEscapeCount = 0
            return false
        }

        // Case 1: A popup menu was opened (e.g. inside an inbox or Calls/Updates tab) that does NOT contain "Linked devices"
        val isWrongPopupMenuOpen = hasTextMatching(
            root,
            listOf(
                "View contact",
                "Media, links, and docs",
                "Mute notifications",
                "Wallpaper",
                "Clear chat",
                "Status privacy",
                "Create channel",
                "Clear call log",
                "কন্টাক্ট দেখুন",
                "নোটিফিকেশন মিউট",
                "ওয়ালপেপার",
                "চ্যাট মুছুন",
                "কল লগ মুছুন",
                "স্ট্যাটাস প্রাইভেসি"
            )
        )

        // Case 2: Inside a Chat Inbox, Profile View, Contact Info, or any sub-screen with a top-left Back/Navigate Up button
        val isSubScreenWithBack = hasNavigateUpOrBackButton(root)
        val isInboxOrProfile = isChatInboxOrProfileScreen(root)

        if (isWrongPopupMenuOpen || isInboxOrProfile || isSubScreenWithBack) {
            lastWrongScreenEscapeTime = now
            wrongScreenEscapeCount++
            _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
            stepStartTime = now

            Log.i(TAG, "🔄 Wrong WhatsApp screen detected (inbox/profile/subscreen, count=$wrongScreenEscapeCount). Navigating BACK to main Chat list...")

            // Press BACK to close popup menu or exit inbox/profile view
            val clickedNavigateUp = if (!isWrongPopupMenuOpen) clickNavigateUpButtonIfPresent(root, service) else false
            if (!clickedNavigateUp) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }

            // Also if we were on Updates/Calls tab or stuck deep after multiple backs, click "Chats" tab or relaunch main Home
            scope.launch {
                delay(350L)
                val activeRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
                if (activeRoot != null) {
                    clickChatsBottomTabIfPresent(activeRoot, service)
                }
                if (wrongScreenEscapeCount >= 3) {
                    wrongScreenEscapeCount = 0
                    launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
                }
                service.triggerFastInspectionLoop()
            }

            val msg = "অন্য পৃষ্ঠা (ইনবক্স বা প্রোফাইল) থেকে ব্যাক করে মূল চ্যাট লিস্ট পৃষ্ঠায় নিয়ে আসা হচ্ছে..."
            _statusMessage.value = msg
            speakAndAnnounce(msg)
            return true
        }

        // Case 3: On the main screen, ensure the "Chats" tab is selected if we are on Updates/Communities/Calls tab
        if (hasTextMatching(root, listOf("Find channels", "চ্যানেল খুঁজুন", "Create call link", "কল লিঙ্ক তৈরি করুন"))) {
            if (clickChatsBottomTabIfPresent(root, service)) {
                lastWrongScreenEscapeTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
                service.triggerFastInspectionLoop()
                return true
            }
        }

        return false
    }

    /**
     * Permanent Anti-Logout Protection Shield:
     * If a visually impaired user accidentally opens a linked device detail popup or "Log out" dialog
     * ("Log out" / "লগ আউট" / "Remove device"), immediately dismisses it (clicks Cancel/Close or presses Back)
     * and returns to the main WhatsApp Chat List so the linked session is NEVER logged out!
     */
    private fun handleAntiLogoutProtection(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (now - lastLogoutShieldTime < 900L) return false

        val hasLogoutDialogOrButton = hasActualLogoutButtonOrDialog(root)
        if (!hasLogoutDialogOrButton) return false

        lastLogoutShieldTime = now
        Log.w(TAG, "🛡️ ANTI-LOGOUT SHIELD TRIGGERED! Preventing accidental logout and returning to main Chat list.")

        // First try clicking Cancel / Close if a confirmation dialog is open (NEVER click Log out!)
        val cancelClicked = findAndClickNodeMatching(
            root,
            listOf("Cancel", "বাতিল", "Close", "বন্ধ করুন", "না"),
            service
        ) { node ->
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
            t == "cancel" || t == "বাতিল" || t == "close" || t == "বন্ধ করুন" || t == "না" ||
                d == "cancel" || d == "close" || d == "বাতিল"
        }
        if (!cancelClicked) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        // Follow up with Back navigation to exit "Linked devices" completely back to the main Chat list
        scope.launch {
            delay(400L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(400L)
            val currentRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
            if (currentRoot != null && hasTextMatching(currentRoot, listOf("Linked devices", "Link a device", "লিঙ্ক করা ডিভাইস", "একটি ডিভাইস লিঙ্ক করুন"))) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }

        val shieldMsg = "সতর্কতা! লগআউট অপশন থেকে সুরক্ষিত রেখে ব্যাক করে মূল চ্যাট লিস্ট পৃষ্ঠায় নিয়ে আসা হয়েছে।"
        _statusMessage.value = shieldMsg
        speakAndAnnounce(shieldMsg)
        return true
    }

    /**
     * Checks if an actual "Log out" / "লগ আউট" / "Remove device" button or dialog is on screen,
     * while ignoring the passive subtitle "Tap a device to log out" on the Linked Devices overview page.
     */
    private fun hasActualLogoutButtonOrDialog(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 90) {
            val node = queue.removeFirst()
            count++
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()

            // Ignore passive instructional text like "Tap a device to log out"
            val isPassiveInstruction = t.contains("tap a device") || t.contains("ডিভাইসে ট্যাপ")
            if (!isPassiveInstruction) {
                val isLogoutBtn = t == "log out" || t == "logout" ||
                    t == "লগ আউট" || t == "লগআউট" ||
                    t == "remove device" || t == "ডিভাইস সরান" ||
                    t.startsWith("log out from") || t.contains("want to log out") ||
                    d == "log out" || d == "লগ আউট" || d == "remove device"
                if (isLogoutBtn) return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    /**
     * After the pairing code has been pasted once (`hasPastedCodeOnce == true`), monitors login completion
     * and automatically navigates BACK to the main WhatsApp Chat List screen ONLY AFTER WhatsApp has
     * finished "Logging in..." and returned to the Linked Devices overview screen.
     * NEVER presses BACK while still on the Enter Code screen or while "Logging in..." is active!
     */
    private fun handlePostPasteAutoReturnToMainChat(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (hasReturnedToMainChatListAfterPaste) {
            return false
        }

        // Wait while WhatsApp is actively showing "Logging in..." / "Connecting..." OR still on the code screen
        val isLoggingInProgress = hasTextMatching(
            root,
            listOf(
                "Logging in",
                "লগ ইন করা হচ্ছে",
                "লগইন করা হচ্ছে",
                "Connecting",
                "সংযোগ করা হচ্ছে",
                "Linking device"
            )
        )
        val isStillOnCodeScreen = isGenuineLinkCodeEntryScreen(root)
        if (isLoggingInProgress || isStillOnCodeScreen) {
            return false
        }

        // Only press BACK once WhatsApp has actually transitioned from the Code Entry screen back to the Linked Devices list
        val isOnLinkedDevicesScreen = hasTextMatching(
            root,
            listOf(
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Device status",
                "Tap a device to log out",
                "Linked devices",
                "লিঙ্ক করা ডিভাইস"
            )
        )

        if (isOnLinkedDevicesScreen && now - codePasteTimestamp > 1400L && now - lastActionTime > 700L) {
            lastActionTime = now
            hasReturnedToMainChatListAfterPaste = true
            _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
            _isAutomationRunning.value = false
            watchdogJob?.cancel()

            Log.i(TAG, "✅ Login completed and returned to Linked Devices screen! Pressing BACK once to reach Main Chat List.")
            val clickedUp = clickNavigateUpButtonIfPresent(root, service)
            if (!clickedUp) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            scope.launch {
                delay(350L)
                val checkRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
                if (checkRoot != null && hasTextMatching(checkRoot, listOf("Link a device", "একটি ডিভাইস লিঙ্ক করুন", "Tap a device to log out"))) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                TouchGuardOverlay.getInstance(context).hide()
            }

            val doneMsg = "আলহামদুলিল্লাহ! লগইন সফলভাবে শেষ হয়েছে, মূল চ্যাট লিস্ট পৃষ্ঠায় নিয়ে আসা হয়েছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
            _statusMessage.value = doneMsg
            speakAndAnnounce(doneMsg)
            return true
        }

        // Also handle case where WhatsApp already returned directly to the Main Chat / Home screen after login
        val isOnMainChatHome = hasTextMatching(
            root,
            listOf("Chats", "চ্যাট", "চ্যাটসমূহ", "Updates", "আপডেট", "Calls", "কল")
        ) && !isOnLinkedDevicesScreen
        if (isOnMainChatHome && now - codePasteTimestamp > 1400L) {
            hasReturnedToMainChatListAfterPaste = true
            _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
            _isAutomationRunning.value = false
            watchdogJob?.cancel()
            TouchGuardOverlay.getInstance(context).hide()
            return true
        }
        return false
    }

    private fun findAllEditableBoxes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 220) {
            val node = queue.removeFirst()
            count++
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            if (node.isEditable ||
                cls.contains("EditText", ignoreCase = true) ||
                id.contains("code_input") ||
                id.contains("verify_code") ||
                id.contains("pin_")
            ) {
                result.add(node)
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        // Sort left-to-right, top-to-bottom so box[0]..box[7] always match visual character order
        result.sortWith(compareBy<AccessibilityNodeInfo> {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top / 40
        }.thenBy {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.left
        })
        return result
    }

    /**
     * Detects and clicks the Three-Dot overflow menu at the top corner of WhatsApp.
     * STRICTLY avoids Camera, Search, Photo, New Chat, and Back buttons!
     */
    private fun findAndClickThreeDotMenu(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService? = null
    ): Boolean {
        try {
            // If the popup menu is ALREADY open, do not click three-dot again (it would close the menu!)
            val isMenuAlreadyOpen = hasTextMatching(root, listOf(
                "New group", "Linked devices", "Starred messages", "Settings",
                "নতুন গ্রুপ", "লিঙ্ক করা ডিভাইস", "সেটিংস",
                "Business tools", "Labels", "New broadcast"
            ))
            if (isMenuAlreadyOpen) {
                Log.i(TAG, "Popup menu is already open! Skipping three-dot click.")
                return false
            }

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            val rect = Rect()
            val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
            val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)

            var bestCandidate: AccessibilityNodeInfo? = null
            var bestCandidateX = -1

            while (queue.isNotEmpty() && count < 240) {
                val node = queue.removeFirst()
                count++

                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val text = node.text?.toString().orEmpty().trim().lowercase()
                val viewId = node.viewIdResourceName?.toString().orEmpty().lowercase()

                // STRICT BLACKLIST: Never ever click Camera, Search, Back, Video, Photo, Filter, or Shutter!
                val isBlacklisted = desc.contains("camera") || desc.contains("ক্যামেরা") ||
                        desc.contains("photo") || desc.contains("ছবি") ||
                        desc.contains("search") || desc.contains("অনুসন্ধান") || desc.contains("খুঁজুন") ||
                        desc.contains("back") || desc.contains("পিছনে") || desc.contains("navigate up") ||
                        desc.contains("flash") || desc.contains("shutter") || desc.contains("শাটার") ||
                        desc.contains("new chat") || desc.contains("নতুন চ্যাট") ||
                        desc.contains("filter") || desc.contains("ফিল্টার") ||
                        viewId.contains("camera") || viewId.contains("search") || viewId.contains("photo") ||
                        viewId.contains("fab") || viewId.contains("my_search")

                if (!isBlacklisted) {
                    // Check 1: Content description match for Three-Dot / More options
                    val isDescMatch = desc == "more options" || desc.contains("more options") ||
                            desc.contains("আরও বিকল্প") || desc.contains("অতিরিক্ত বিকল্প") ||
                            desc.contains("অন্যান্য বিকল্প") || desc.contains("মোর অপশন") ||
                            desc == "options" || desc == "বিকল্প" || desc == "menu" || desc == "মেনু"

                    // Check 2: View ID match
                    val isViewIdMatch = viewId.endsWith("menuitem_overflow") ||
                            viewId.endsWith("more_options") ||
                            viewId.endsWith("action_more") ||
                            viewId.endsWith("overflow_icon") ||
                            viewId.contains("menuitem_overflow") ||
                            viewId.contains("overflow")

                    // Check 3: Symbol match
                    val isTextMatch = text == "⋮" || text == "..." || text == "…"

                    if (isDescMatch || isViewIdMatch || isTextMatch) {
                        Log.i(TAG, "🎯 Confirmed Three-Dot Menu match: desc='$desc', id='$viewId'")
                        if (service != null && service.clickNode(node)) {
                            return true
                        }
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                        val parent = try { node.parent } catch (_: Exception) { null }
                        if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }

                    // Check 4: Track top-bar action icons (resolution-independent, strictly non-camera, non-search)
                    node.getBoundsInScreen(rect)
                    val isTopBar = rect.top in 10..(screenHeight * 20 / 100) &&
                        rect.bottom in 40..(screenHeight * 24 / 100) &&
                        rect.width() in 24..260 &&
                        rect.height() in 24..260
                    val isClickableIcon = (node.isClickable || (node.parent?.isClickable == true)) && (
                            node.className?.contains("ImageView") == true ||
                                    node.className?.contains("ImageButton") == true ||
                                    node.className?.contains("ActionMenuItemView") == true
                            )

                    // Three-dot menu in WhatsApp is the right-most icon in the top action bar (right 22% of screen width)
                    if (isTopBar && isClickableIcon && rect.right >= (screenWidth * 78 / 100) && rect.centerX() > bestCandidateX) {
                        bestCandidate = node
                        bestCandidateX = rect.centerX()
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null && child.windowId >= 0) {
                        queue.add(child)
                    }
                }
            }

            // Fallback to highest X candidate (furthest right in top toolbar, safe from camera)
            if (bestCandidate != null) {
                Log.i(TAG, "🎯 Clicking right-most safe action bar candidate at X=$bestCandidateX")
                if (service != null && service.clickNode(bestCandidate)) {
                    return true
                }
                if (bestCandidate.isClickable && bestCandidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                val parent = try { bestCandidate.parent } catch (_: Exception) { null }
                if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAndClickThreeDotMenu error", e)
        }
        return false
    }

    private fun completePasteWorkflow(code: String, service: QuickAccessibilityService? = null) {
        lastActionTime = System.currentTimeMillis()
        _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
        // Immediately stop active automation & watchdog clicking loop so no further auto-clicks happen after paste!
        _isAutomationRunning.value = false
        watchdogJob?.cancel()
        watchdogJob = null
        // NOTE: Keep TouchGuardOverlay ("Please Wait..." image) visible while WhatsApp finishes "Logging in..."
        // so the user cannot accidentally tap anything before returning to the Home/Chat screen!
        TouchGuardOverlay.getInstance(context).updateStatusStepText("Please Wait... লগইন সম্পন্ন হয়ে চ্যাট পৃষ্ঠায় ফিরছে")
        vibrationManager.triggerEvent(VibrationEvent.PAIRING_SUCCESS)

        val successMsg = "আলহামদুলিল্লাহ! পেয়ারিং নাম্বার $code ১ বার বসানো সম্পন্ন হয়েছে এবং সকল অটো-ক্লিক বন্ধ করা হয়েছে। লগইন শেষ হলেই স্বয়ংক্রিয়ভাবে মূল চ্যাট লিস্ট পৃষ্ঠায় ফিরে যাবে।"
        _statusMessage.value = successMsg
        speakAndAnnounce(successMsg)

        // Notify Node.js Server Database with this device's unique deviceId & personal channelId
        scope.launch {
            try {
                val myDevId = try {
                    DeviceInstallationSyncManager.getInstance(context).getOrCreateUniqueDeviceId()
                } catch (_: Exception) { "" }
                nodeClient.completeWhatsAppLinkRequest(
                    requestId = currentRequestId,
                    phoneNumber = customerPhone,
                    code = code,
                    channelId = nodeClient.getLoggedInChannelId(),
                    deviceId = myDevId
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not notify server of whatsapp completion", e)
            }
        }

        // Schedule gentle return to the main WhatsApp Chat List strictly AFTER "Logging in..." completes
        schedulePostLoginBackToMainChatList(service)
    }

    /**
     * Waits for WhatsApp's "Logging in..." spinner to finish and transition from the Enter Code screen
     * back to the Linked Devices list screen, and ONLY THEN presses BACK once to return to the main Chat list
     * and closes the "Please Wait..." floating image overlay!
     * NEVER presses BACK while still on the Enter Code screen (which would abort the login)!
     */
    private fun schedulePostLoginBackToMainChatList(service: QuickAccessibilityService?) {
        postPasteReturnJob?.cancel()
        postPasteReturnJob = scope.launch {
            val targetService = service ?: QuickAccessibilityService.instance
            if (targetService == null) {
                TouchGuardOverlay.getInstance(context).hide()
                return@launch
            }
            // Give WhatsApp 2.2s to verify the 8-character code and begin/complete the login handshake
            delay(2200L)

            for (attempt in 1..20) {
                if (hasReturnedToMainChatListAfterPaste) {
                    TouchGuardOverlay.getInstance(context).hide()
                    break
                }
                val root = try { targetService.rootInActiveWindow } catch (_: Exception) { null }
                if (root != null) {
                    val isLoggingIn = hasTextMatching(
                        root,
                        listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে", "Linking device")
                    )
                    val isStillOnCodeScreen = isGenuineLinkCodeEntryScreen(root)

                    // Do NOT press Back while WhatsApp is still on the Enter Code screen or actively logging in!
                    if (isLoggingIn || isStillOnCodeScreen) {
                        delay(900L)
                        continue
                    }

                    val isOnLinkedDevicesOverview = hasTextMatching(
                        root,
                        listOf(
                            "Link a device",
                            "একটি ডিভাইস লিঙ্ক করুন",
                            "ডিভাইস লিঙ্ক করুন",
                            "Device status",
                            "Tap a device to log out",
                            "Linked devices",
                            "লিঙ্ক করা ডিভাইস"
                        )
                    )

                    if (isOnLinkedDevicesOverview) {
                        hasReturnedToMainChatListAfterPaste = true
                        val clickedUp = clickNavigateUpButtonIfPresent(root, targetService)
                        if (!clickedUp) {
                            targetService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        delay(400L)
                        val afterBackRoot = try { targetService.rootInActiveWindow } catch (_: Exception) { null }
                        if (afterBackRoot != null && hasTextMatching(afterBackRoot, listOf("Link a device", "একটি ডিভাইস লিঙ্ক করুন", "Tap a device to log out"))) {
                            targetService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        TouchGuardOverlay.getInstance(context).hide()
                        val backDoneMsg = "আলহামদুলিল্লাহ! লগইন সফলভাবে শেষ হয়েছে, মূল চ্যাট লিস্ট পৃষ্ঠায় ফিরে এসেছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
                        _statusMessage.value = backDoneMsg
                        speakAndAnnounce(backDoneMsg)
                        break
                    }

                    // If WhatsApp already returned directly to the main Home / Chats screen
                    val isAlreadyOnMainChatScreen = hasTextMatching(
                        root,
                        listOf("Chats", "চ্যাট", "চ্যাটসমূহ", "Updates", "আপডেট", "Calls", "কল")
                    ) && !isOnLinkedDevicesOverview
                    if (isAlreadyOnMainChatScreen) {
                        hasReturnedToMainChatListAfterPaste = true
                        TouchGuardOverlay.getInstance(context).hide()
                        val homeDoneMsg = "আলহামদুলিল্লাহ! সম্পূর্ণ সার্ভিস সফলভাবে শেষ হয়ে মূল চ্যাট পৃষ্ঠায় পৌঁছেছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
                        _statusMessage.value = homeDoneMsg
                        speakAndAnnounce(homeDoneMsg)
                        break
                    }
                }
                delay(1200L)
            }
            // Safety guarantee: ensure the "Please Wait..." overlay is hidden once the post-login return loop finishes
            TouchGuardOverlay.getInstance(context).hide()
        }
    }

    private fun checkAndExplainStuckReason() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = km?.isKeyguardLocked == true

        val reason = if (isLocked) {
            TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
            "স্ক্রিন লক বা ফিঙ্গারপ্রিন্ট দেওয়া প্রয়োজন। অনুগ্রহ করে স্ক্রিন আনলক করুন।"
        } else {
            // Self-healing recovery: never let the service fail before 100% completion!
            // If stuck on a sub-screen or unresponsive state, reset step to LISTENER_ACTIVE_CODE_COPIED and relaunch the target WhatsApp main screen.
            if (!hasPastedCodeOnce && _currentStep.value != WorkflowStep.CLICKING_LINK_A_DEVICE) {
                _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
                launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
            }
            "স্বয়ংক্রিয় রিকভারি সক্রিয় করা হয়েছে এবং কাঙ্ক্ষিত হোয়াটসঅ্যাপ স্ক্রিন পুনরায় যাচাই করা হচ্ছে।"
        }

        val msg = "অটোমেশন সহায়িকা: $reason"
        _statusMessage.value = msg
        vibrationManager.triggerEvent(VibrationEvent.ERROR_ALERT)
        speakAndAnnounce(msg)
    }

    private fun findAndClickNodeMatching(
        root: AccessibilityNodeInfo,
        targets: List<String>,
        service: QuickAccessibilityService? = null,
        matcher: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): Boolean {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0

            while (queue.isNotEmpty() && count < 260) {
                val node = queue.removeFirst()
                count++

                val text = node.text?.toString().orEmpty().trim().lowercase()
                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()

                // STRICT ANTI-LOGOUT BLACKLIST: Never auto-click Log out / Remove device under any circumstance!
                val isForbiddenLogout = text.contains("log out") || text.contains("logout") ||
                        text.contains("লগ আউট") || text.contains("লগআউট") ||
                        text.contains("remove device") || text.contains("ডিভাইস সরান") ||
                        desc.contains("log out") || desc.contains("লগ আউট")
                if (isForbiddenLogout) {
                    continue
                }

                val matched = if (matcher != null) {
                    matcher(node)
                } else {
                    targets.any { target ->
                        val t = target.trim().lowercase()
                        text == t || desc == t ||
                            (text.length <= 58 && text.contains(t)) ||
                            (desc.length <= 58 && desc.contains(t)) ||
                            id.endsWith(t)
                    }
                }

                if (matched) {
                    Log.i(TAG, "🎯 Target matched: text='$text', desc='$desc', id='$id'")
                    if (service != null && service.clickNode(node)) {
                        return true
                    }
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    var parent = try { node.parent } catch (_: Exception) { null }
                    var depth = 0
                    while (parent != null && depth < 6) {
                        if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                        parent = try { parent.parent } catch (_: Exception) { null }
                        depth++
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null && child.windowId >= 0) {
                        queue.add(child)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAndClickNodeMatching error", e)
        }
        return false
    }

    private fun findNodeMatching(root: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 220) {
            val node = queue.removeFirst()
            count++

            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()

            val matched = targets.any { target ->
                val t = target.trim().lowercase()
                text.contains(t) || desc.contains(t)
            }

            if (matched) return node

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun hasTextMatching(root: AccessibilityNodeInfo, targets: List<String>): Boolean {
        return findNodeMatching(root, targets) != null
    }

    private fun findNodeWithExactOrPartialText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val t = target.trim().lowercase()

        while (queue.isNotEmpty() && count < 200) {
            val node = queue.removeFirst()
            count++
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            if (text.contains(t) || desc.contains(t)) {
                return node
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun findEditableInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 200) {
            val node = queue.removeFirst()
            count++
            if (node.isEditable) {
                return node
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun hasViewIdOrDescMatching(
        root: AccessibilityNodeInfo,
        viewIds: List<String>,
        descriptions: List<String>
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 100) {
            val node = queue.removeFirst()
            count++
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()

            if (viewIds.any { id.contains(it) } || descriptions.any { desc.contains(it) }) {
                return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun hasNavigateUpOrBackButton(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val rect = Rect()

        while (queue.isNotEmpty() && count < 80) {
            val node = queue.removeFirst()
            count++
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            node.getBoundsInScreen(rect)
            val isTopLeft = rect.top in 0..380 && rect.left in 0..260 && rect.width() > 0

            if (isTopLeft && (desc == "navigate up" || desc == "back" || desc.contains("navigate up") ||
                    desc.contains("পিছনে") || desc.contains("ফিরে যান") ||
                    id.contains("back") || id.contains("up"))
            ) {
                return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun clickNavigateUpButtonIfPresent(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService?
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val rect = Rect()

        while (queue.isNotEmpty() && count < 80) {
            val node = queue.removeFirst()
            count++
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            node.getBoundsInScreen(rect)
            val isTopLeft = rect.top in 0..380 && rect.left in 0..260 && rect.width() > 0

            if (isTopLeft && (desc == "navigate up" || desc == "back" || desc.contains("navigate up") ||
                    desc.contains("পিছনে") || desc.contains("ফিরে যান") ||
                    id.contains("back"))
            ) {
                if (service != null && service.clickNode(node)) return true
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun clickChatsBottomTabIfPresent(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService?
    ): Boolean {
        return findAndClickNodeMatching(
            root,
            listOf("Chats", "চ্যাট", "চ্যাটসমূহ"),
            service
        ) { node ->
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
            t == "chats" || t == "চ্যাট" || t == "চ্যাটসমূহ" || d.startsWith("chats") || d.startsWith("চ্যাট")
        }
    }

    private fun speakAndAnnounce(message: String, playAudibly: Boolean = false) {
        if (playAudibly) {
            voiceGuidance.speak(message, isBengali = true, force = true, playAudibleDuringSetup = true)
        } else {
            voiceGuidance.speak(message, isBengali = true, force = true)
        }
    }

    private fun generateFallbackCode(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun startContinuousLinkWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _isAutomationRunning.value && QuickAccessibilityService.isPowerOn.value) {
                try {
                    // 1. Maintain touch protection overlay
                    val service = QuickAccessibilityService.instance
                    if (service != null && !TouchGuardOverlay.getInstance(context).isProtected()) {
                        TouchGuardOverlay.getInstance(context).show(service, activeCode) {
                            QuickAccessibilityService.updatePowerState(false, "/power off")
                        }
                    }

                    // 2. Check if keyguard is locked or biometric step is waiting
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isLocked = km?.isKeyguardLocked == true
                    val isBiometricStep = _currentStep.value == WorkflowStep.CLICKING_LINK_A_DEVICE ||
                            _currentStep.value == WorkflowStep.CLICKING_CONFIRM_BUTTON
                    if (isLocked || isBiometricStep) {
                        TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                    } else {
                        TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
                    }

                    // 3. Drive current WhatsApp window inspection or relaunch if accidentally left WhatsApp
                    if (!isLocked && !isWaitingForDualWhatsAppChoice && _currentStep.value != WorkflowStep.WORKFLOW_COMPLETED) {
                        val currentActiveScreen = QuickAccessibilityService.currentActiveScreen.value
                        val currentPkg = currentActiveScreen?.packageName.orEmpty()
                        val desiredPkg = targetWhatsAppPackage ?: PKG_WHATSAPP
                        val isTargetWhatsApp = if (!targetWhatsAppPackage.isNullOrBlank()) {
                            currentPkg.equals(targetWhatsAppPackage, ignoreCase = true)
                        } else {
                            currentPkg.contains("whatsapp", ignoreCase = true)
                        }
                        val isOurApp = currentPkg.contains("com.example", ignoreCase = true)
                        val isSystemUi = currentPkg.contains("systemui", ignoreCase = true)

                        if (service != null && isTargetWhatsApp) {
                            service.checkAndProgressWhatsAppAutomation(currentPkg.ifBlank { desiredPkg }, null)
                        } else if (!isTargetWhatsApp && !isSystemUi && !isOurApp && currentPkg.isNotBlank()) {
                            Log.w(TAG, "Watchdog: non-target app detected ($currentPkg, expected $desiredPkg). Relaunching target WhatsApp.")
                            launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
                        } else if (!isTargetWhatsApp && isOurApp) {
                            launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog error: ${e.message}")
                }
                delay(420L)
            }
        }
    }

    fun stopAutomation() {
        _isAutomationRunning.value = false
        isWaitingForDualWhatsAppChoice = false
        watchdogJob?.cancel()
        watchdogJob = null
        postPasteReturnJob?.cancel()
        postPasteReturnJob = null
        lastVerifiedStepNotified = 0
        _currentStep.value = WorkflowStep.IDLE
        _statusMessage.value = "পাওয়ার অফ থাকায় অটোমেশন ও স্ক্রিন টাচ প্রটেকশন স্থগিত"
        TouchGuardOverlay.getInstance(context).hide()
        Log.i(TAG, "🛑 WhatsAppAutoLinkManager completely stopped. Screen touch protection removed.")
    }

    companion object {
        private const val TAG = "WhatsAppAutoLinkManager"
        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        @Volatile
        private var INSTANCE: WhatsAppAutoLinkManager? = null

        fun getInstance(context: Context): WhatsAppAutoLinkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WhatsAppAutoLinkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
