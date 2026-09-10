package com.keyguard.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.keyguard.app.databinding.ActivityRoleBinding
import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.Supervision
import com.keyguard.app.settings.DeviceRole
import com.keyguard.app.settings.RolePolicy
import com.keyguard.app.settings.RoleStore

/**
 * The launcher activity, and the whole of the parent/child split as a user experiences it.
 *
 * Before this existed, both sides of the product shared one screen: the child's setup carried
 * the pairing form *and* a button through to the parent dashboard. On a developer's phone that
 * is convenient. On a real one it means a supervised child is one tap from the screen that
 * administers their own supervision, and a parent's first experience of the app is being told to
 * enable a keyboard they have no use for.
 *
 * So this asks once, routes, and then gets out of the way — after the first answer it never
 * appears again, it just forwards.
 *
 * The routing is deliberately dumber than it could be. It does not infer the role from whether a
 * family exists or whether the keyboard is enabled: inference would be wrong exactly on the test
 * devices that play both parts, and being wrong here means showing a child the dashboard.
 */
class RoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleBinding
    private lateinit var roles: RoleStore
    private lateinit var supervision: Supervision

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roles = RoleStore(this)
        supervision = Supervision(this)

        // A supervised device is locked to the child side regardless of what the stored role
        // says. Same rule as "only a parent can unpair", enforced at the one place a child could
        // otherwise walk out of it — and deliberately before the stored value is even read, so a
        // hand-edited preference is not a way round it.
        if (supervision.isSupervised) {
            open(DeviceRole.CHILD)
            return
        }

        when (roles.role) {
            DeviceRole.CHILD -> open(DeviceRole.CHILD)
            DeviceRole.PARENT -> open(DeviceRole.PARENT)
            DeviceRole.UNSET -> renderChooser()
        }
    }

    private fun renderChooser() {
        binding = ActivityRoleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.childCard.setOnClickListener { choose(DeviceRole.CHILD) }
        binding.parentCard.setOnClickListener { choose(DeviceRole.PARENT) }
    }

    private fun open(role: DeviceRole) {
        val target = when (role) {
            DeviceRole.PARENT -> ParentActivity::class.java
            else -> SetupActivity::class.java
        }
        startActivity(Intent(this, target))
        // Finished rather than kept on the stack, so Back from the destination leaves the app
        // instead of returning to a chooser the user already answered.
        finish()
    }

    private fun choose(role: DeviceRole) {
        val hasSession = runCatching {
            val endpoint = getString(R.string.verify_base_url)
            endpoint.isNotBlank() && FamilyClient(this, endpoint).hasParentSession
        }.getOrDefault(false)

        if (roles.choose(role, supervision.isSupervised, hasSession)) {
            open(role)
            return
        }

        // Refused rather than silently ignored. The two reasons are different and the user can
        // act on one of them, so the screen says which applies instead of appearing to do
        // nothing when tapped.
        binding.roleLockedText.setText(
            when (RolePolicy.verdict(roles.role, supervision.isSupervised, hasSession)) {
                RolePolicy.Verdict.LockedBySupervision -> R.string.role_locked_supervised
                RolePolicy.Verdict.LockedByParentSession -> R.string.role_locked_parent
                RolePolicy.Verdict.Allowed -> R.string.parent_failed
            },
        )
        binding.roleLockedText.visibility = View.VISIBLE
    }
}
