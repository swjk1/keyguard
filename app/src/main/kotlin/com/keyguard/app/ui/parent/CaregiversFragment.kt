package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentCaregiversBinding
import com.keyguard.app.family.PairingCode
import com.keyguard.app.ui.SectionFragment

/**
 * Invite another caregiver into this family, or join someone else's. Pushed from Settings.
 *
 * This was half of the old Account tab, which also held sign-out and account deletion - so
 * "Delete account" sat a short scroll below "Create an invite code". The destructive pair now
 * lives in Settings' Account group and this screen only adds people.
 */
class CaregiversFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentCaregiversBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    /** The invite on screen, kept across a rotation so it is not silently re-minted. */
    private var inviteCode: String? = null

    override fun screenTitle(): CharSequence = getString(R.string.parent_settings_caregivers)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inviteCode = savedInstanceState?.getString(STATE_INVITE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        inviteCode?.let { outState.putString(STATE_INVITE, it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCaregiversBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.inviteParentButton.setOnClickListener { inviteCaregiver() }
        binding.joinParentButton.setOnClickListener { joinFamily() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        val code = inviteCode
        binding.parentInviteCodeText.text = code?.let(PairingCode::format)
        binding.parentInviteCodeText.visibility = if (code != null) View.VISIBLE else View.GONE
    }

    private fun inviteCaregiver() {
        val client = host.client ?: return
        binding.inviteParentButton.isEnabled = false
        host.background({ client.issueParentInvite() }) { invite ->
            if (_binding == null) return@background
            binding.inviteParentButton.isEnabled = true
            if (invite == null) {
                showStatus(R.string.parent_failed)
                return@background
            }
            inviteCode = invite.code
            binding.caregiversStatusText.visibility = View.GONE
            refresh()
        }
    }

    private fun joinFamily() {
        val client = host.client ?: return
        val code = PairingCode.normalize(binding.parentInviteField.text.toString())
        if (!PairingCode.isValid(code)) {
            showStatus(R.string.supervision_code_invalid)
            return
        }
        binding.joinParentButton.isEnabled = false
        host.background({ client.joinAsParent(code) }) { joined ->
            if (_binding == null) return@background
            binding.joinParentButton.isEnabled = true
            if (joined == true) {
                binding.parentInviteField.text?.clear()
                binding.caregiversStatusText.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    R.string.parent_caregivers_joined,
                    Toast.LENGTH_LONG,
                ).show()
                host.reload()
            } else {
                showStatus(R.string.supervision_code_invalid)
            }
        }
    }

    private fun showStatus(messageRes: Int) {
        if (_binding == null) return
        binding.caregiversStatusText.setText(messageRes)
        binding.caregiversStatusText.visibility = View.VISIBLE
    }

    private companion object {
        const val STATE_INVITE = "invite"
    }
}
