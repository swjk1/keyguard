package com.keyguard.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle

import com.keyguard.app.RoleActivity

/**
 * A second launcher icon, in `familyDebug` only, that reopens the role chooser.
 *
 * The product deliberately asks which side a phone is once and then never again — see
 * `RoleActivity`. That is right for a real device and impossible to test on one, because
 * checking the child UI and the parent UI on the same phone means answering that question
 * twice.
 *
 * This exists in the `familyDebug` source set rather than behind a runtime flag so that the
 * class is not compiled into any other variant at all. There is no release build in which an
 * icon labelled "Keyguard role" can appear on a child's home screen.
 *
 * It is a trampoline rather than a screen: an `activity-alias` cannot attach an intent extra,
 * and the alternative — teaching `RoleActivity` to treat a bare launch as "ask again" — would
 * change what happens on every ordinary app launch.
 */
class RoleSwitchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, RoleActivity::class.java)
                // An empty value means "show the chooser". Passing "parent" or "child" here
                // would skip it, which is what the adb form in RoleActivity's comment does.
                .putExtra(RoleActivity.EXTRA_DEBUG_ROLE, "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
