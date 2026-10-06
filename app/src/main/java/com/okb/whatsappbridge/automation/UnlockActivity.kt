package com.okb.whatsappbridge.automation

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns the screen on and asks Android to dismiss the lock screen before an automatic TEXT report is sent.
 * Android dismisses a NON-secure lock screen (None / Swipe, or Smart Lock keeping the phone unlocked) without
 * user interaction. A PIN, pattern or password can never be bypassed by an app: the request is then cancelled
 * and the send attempt fails with that reason. No UI of its own.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            finishWith(true)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finishWith(true)
                override fun onDismissCancelled() = finishWith(false)
                override fun onDismissError() = finishWith(false)
            },
        )
    }

    private fun finishWith(unlocked: Boolean) {
        pending?.complete(unlocked)
        pending = null
        finish()
    }

    companion object {
        @Volatile
        private var pending: CompletableDeferred<Boolean>? = null

        /**
         * Starts the activity (allowed from the background because the accessibility service is bound) and waits
         * for the lock screen to be dismissed. False on a secure lock or timeout.
         */
        suspend fun dismissKeyguard(context: Context, timeoutMs: Long = 10_000): Boolean {
            val result = CompletableDeferred<Boolean>()
            pending = result
            return try {
                context.startActivity(
                    Intent(context, UnlockActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
                )
                withTimeoutOrNull(timeoutMs) { result.await() } ?: false
            } catch (_: RuntimeException) {
                false
            } finally {
                if (pending === result) pending = null
            }
        }
    }
}
