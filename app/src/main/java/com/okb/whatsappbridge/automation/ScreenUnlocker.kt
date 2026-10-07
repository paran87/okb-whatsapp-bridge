package com.okb.whatsappbridge.automation

/**
 * Gets past a NON-secure lock screen (None / Swipe) before an automatic TEXT report is sent:
 *
 *   1. Android's own dismiss request (UnlockActivity → requestDismissKeyguard).
 *   2. If the lock screen is still there — some phones (Xiaomi/MIUI in particular) ignore the request or block
 *      the activity while in the background, and MIUI always shows a swipe lock screen — the accessibility
 *      service swipes it up, like a person would.
 *
 * A PIN, pattern or password is never attempted: Android does not let apps unlock it.
 */
class ScreenUnlocker(
    private val isLocked: () -> Boolean,
    private val isSecure: () -> Boolean,
    private val dismiss: suspend () -> Boolean,
    /** Swipe gesture number [attempt] (0-based) from the lower part of the screen upwards; false if not performed. */
    private val swipeUp: suspend (attempt: Int) -> Boolean,
    private val sleep: suspend (Long) -> Unit,
    private val log: (String) -> Unit = {},
) {
    /** Null once the phone is unlocked, otherwise why it could not be. */
    suspend fun unlock(): String? {
        if (!isLocked()) return null
        if (isSecure()) return SECURE_LOCK
        if (dismiss() && !isLocked()) return null
        sleep(SETTLE_MS)
        if (!isLocked()) return null
        for (attempt in 0 until SWIPE_ATTEMPTS) {
            log("Lock screen still shown: swiping it up (attempt ${attempt + 1})")
            if (!swipeUp(attempt)) log("The swipe gesture could not be performed")
            sleep(SETTLE_MS)
            if (!isLocked()) {
                log("Lock screen dismissed by swiping")
                return null
            }
        }
        return NOT_DISMISSED
    }

    companion object {
        const val SWIPE_ATTEMPTS = 3
        const val SETTLE_MS = 900L
        const val SECURE_LOCK =
            "The phone is locked with a PIN, pattern or password, which Android does not let apps unlock. " +
                "Set Screen lock to None or Swipe on the bridge phone."
        const val NOT_DISMISSED =
            "The lock screen could not be dismissed. On Xiaomi: Settings → Apps → OKB WhatsApp Bridge → Other permissions → " +
                "allow \"Show on Lock screen\" and \"Display pop-up windows while running in the background\"; then turn the " +
                "OKB accessibility service off and on again"
    }
}
