package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.keyguard.app.ParentActivity
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentAccountBinding
import com.keyguard.app.family.PairingCode
import com.keyguard.app.ui.SectionFragment

/**
 * The account, caregivers, and the two destructive actions.
 *
 * These lived among the policy controls and the child list before, which put *Delete account* a
 * short scroll from *Refresh*. They are their own section now and the destructive pair sits at
 * the bottom of it, behind a confirmation.
 *
 * The recovery code is shown here rather than on the sign-in panel that produced it, because
 * that panel disappears the instant registration succeeds — the code would flash up on a view
 * being torn down. The shell holds it until this section is built and asks for it.
 */
class AccountFragment : SectionFragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.signOutButton.setOnClickListener { signOut() }
        binding.deleteAccountButton.setOnClickListener { confirmDelete() }
        binding.inviteParentButton.setOnClickListener { inviteCaregiver() }
        binding.joinParentButton.setOnClickListener { joinFamily() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        binding.accountStatusText.visibility = View.GONE
        binding.signedInText.text = getString(
            R.string.parent_signed_in,
            host.client?.parentEmail.orEmpty(),
        )

        // Shown once, then never again — the server does not keep a readable copy, so a parent
        // who does not write it down has lost it. Consumed rather than peeked so it does not
        // reappear on every visit to this tab as though it were a permanent property.
        (activity as? ParentActivity)?.consumeRecoveryCode()?.let { code ->
            binding.recoveryCodeText.text = getString(R.string.parent_recovery_code, code)
            binding.recoveryCodeText.visibility = View.VISIBLE
        }
    }

    private fun signOut() {
        val client = host.client ?: return
        binding.signOutButton.isEnabled = false
        host.background({ client.signOutParent() }) {
            _binding?.signOutButton?.isEnabled = true
            host.onSignedOut()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.parent_delete_account_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.parent_delete_account) { _, _ ->
                val client = host.client ?: return@setPositiveButton
                host.background({ client.deleteParentAccount() }) { deleted ->
                    if (deleted == true) host.onSignedOut() else showStatus(R.string.parent_failed)
                }
            }
            .show()
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
            binding.parentInviteCodeText.text = PairingCode.format(invite.code)
            binding.parentInviteCodeText.visibility = View.VISIBLE
            binding.accountStatusText.visibility = View.GONE
        }
    }

    private fun joinFamily() {
        val client = host.client ?: return
        val code = PairingCode.normalize(binding.parentInviteField.text.toString())
        if (!PairingCode.isValid(code)) {
            showStatus(R.string.supervision_code_invalid)
            return
        }
        host.background({ client.joinAsParent(code) }) { joined ->
            if (_binding == null) return@background
            if (joined == true) {
                binding.parentInviteField.text?.clear()
                binding.accountStatusText.visibility = View.GONE
                host.reload()
            } else {
                showStatus(R.string.supervision_code_invalid)
            }
        }
    }

    private fun showStatus(messageRes: Int) {
        if (_binding == null) return
        binding.accountStatusText.setText(messageRes)
        binding.accountStatusText.visibility = View.VISIBLE
    }
}
