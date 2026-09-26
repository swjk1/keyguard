package com.keyguard.app.ui.parent

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentAddChildBinding
import com.keyguard.app.databinding.ItemParentStepBinding
import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.PairingCode
import com.keyguard.app.family.PairingInvite
import com.keyguard.app.ui.SectionFragment
import java.util.concurrent.TimeUnit

/**
 * Pairing a child's phone, with a screen that notices when it happens.
 *
 * Opening the screen asks for a code straight away - tapping "Add a child" is the request, and
 * a second button saying "Get a pairing code" was a step with nothing to decide. The code is
 * shown with a live countdown, and while the screen is visible it re-fetches the overview every
 * [POLL_MS]. The moment a child appears that was not in the family when the code was issued,
 * the screen says so and hands over to that child's page. Found in testing: a parent watching
 * the old screen saw nothing happen until they thought to press Refresh, and the natural
 * conclusion was that pairing had failed.
 *
 * The poll runs only between `onResume` and `onPause`. A parent who leaves this screen open in
 * a background task is not charged a request every four seconds for it, and the reload that
 * `ParentActivity.onResume` does on return catches anything that happened meanwhile.
 *
 * The code, its deadline and the baseline survive rotation in saved state; a rotation must not
 * mint a second code (the first would still be valid, and the parent may already have read it
 * out) or forget which children were already here.
 */
class AddChildFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentAddChildBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost
    private val main = Handler(Looper.getMainLooper())

    /** The code on screen, normalized, or null before one has arrived. */
    private var code: String? = null

    /**
     * When the code stops working, on *this* phone's wall clock.
     *
     * The server's `expiresAt` is on the server's clock. Converting it once, at arrival, into
     * "this many minutes from now" - falling back to the ten-minute lifetime when the answer is
     * impossible - means a parent phone whose clock is wrong still counts down from roughly ten
     * minutes rather than showing a code as expired the moment it appears.
     */
    private var deadline: Long = 0

    /** Children already in the family when the code arrived. Null until an overview is known. */
    private var baseline: Set<String>? = null

    private var requesting = false
    private var failed = false

    /** Set once a new child has been seen; the screen is then only a success message. */
    private var connectedId: String? = null
    private var connectedLabel: String? = null

    /**
     * Asked for when a code is issued, not at launch: the permission exists to carry one
     * specific thing - the alert that a serious warning was reported - and this is the moment
     * "we will tell you if something happens" starts to mean something.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val tick = object : Runnable {
        override fun run() {
            renderExpiry()
            if (waiting()) main.postDelayed(this, TICK_MS)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!waiting()) return
            // Invisible: a progress bar flickering across the top every four seconds would
            // read as the app struggling. The waiting line already says we are listening.
            host.reload(visible = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    private val handOver = Runnable {
        val id = connectedId ?: return@Runnable
        if (isResumed) host.replaceTop(ChildDetailFragment.newInstance(id))
    }

    override fun screenTitle(): CharSequence = getString(R.string.parent_add_child_title)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            code = state.getString(STATE_CODE)
            deadline = state.getLong(STATE_DEADLINE)
            baseline = state.getStringArrayList(STATE_BASELINE)?.toSet()
            connectedId = state.getString(STATE_CONNECTED_ID)
            connectedLabel = state.getString(STATE_CONNECTED_LABEL)
            failed = state.getBoolean(STATE_FAILED)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CODE, code)
        outState.putLong(STATE_DEADLINE, deadline)
        baseline?.let { outState.putStringArrayList(STATE_BASELINE, ArrayList(it)) }
        outState.putString(STATE_CONNECTED_ID, connectedId)
        outState.putString(STATE_CONNECTED_LABEL, connectedLabel)
        outState.putBoolean(STATE_FAILED, failed)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAddChildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val steps = listOf(
            R.string.parent_add_child_step_1,
            R.string.parent_add_child_step_2,
            R.string.parent_add_child_step_3,
        )
        steps.forEachIndexed { index, text ->
            val step = ItemParentStepBinding.inflate(layoutInflater, binding.stepsContainer, true)
            step.stepNumber.text = (index + 1).toString()
            step.stepText.setText(text)
        }
        binding.newCodeButton.setOnClickListener { requestCode() }
    }

    override fun onResume() {
        super.onResume()
        if (connectedId != null) {
            render()
            main.postDelayed(handOver, HAND_OVER_MS)
            return
        }
        if (code == null && !failed) requestCode()
        startWaiting()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        main.removeCallbacks(poll)
        main.removeCallbacks(handOver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        main.removeCallbacksAndMessages(null)
        _binding = null
    }

    /** Called after every overview reload: this is where an arrival is noticed. */
    override fun refresh() {
        if (_binding == null) return
        val overview = host.overview
        if (code != null && connectedId == null && overview != null) {
            val known = baseline
            if (known == null) {
                // The code arrived before any overview had loaded. The first one to land is the
                // baseline - a child could only have used the code in the second between, and
                // missing that is better than announcing an existing child as new.
                baseline = overview.children.map { it.installId }.toSet()
            } else {
                FamilyDigest.newlyPaired(known, overview.children).firstOrNull()?.let(::onConnected)
            }
        }
        render()
    }

    private fun requestCode() {
        val client = host.client ?: return
        if (requesting) return
        requesting = true
        failed = false
        code = null
        render()

        host.background({ client.issuePairingCode() }) { invite: PairingInvite? ->
            requesting = false
            if (_binding == null) return@background
            if (invite == null) {
                failed = true
                render()
                return@background
            }
            val now = System.currentTimeMillis()
            code = invite.code
            // A code that has only just been minted cannot really have expired, nor have more
            // than its lifetime left; either answer means the two clocks disagree, and the
            // lifetime is the better guess.
            val remaining = invite.expiresAt - now
            deadline = now + if (remaining in 1..CODE_LIFETIME_MS) remaining else CODE_LIFETIME_MS
            baseline = host.overview?.children?.map { it.installId }?.toSet()
            render()
            AlertPermission.requestIfPromptable(this, notificationPermission)
            // Asking for a code creates the family server-side, so the overview now has one.
            host.reload()
            startWaiting()
        }
    }

    private fun onConnected(child: ChildActivity) {
        connectedId = child.installId
        connectedLabel = child.label
        main.removeCallbacks(tick)
        main.removeCallbacks(poll)
        render()
        // A moment to read "Sam is connected" before the page changes under the parent.
        main.postDelayed(handOver, HAND_OVER_MS)
    }

    private fun startWaiting() {
        main.removeCallbacks(tick)
        main.removeCallbacks(poll)
        if (!waiting()) return
        main.post(tick)
        main.postDelayed(poll, POLL_MS)
    }

    private fun waiting(): Boolean =
        code != null && connectedId == null && System.currentTimeMillis() < deadline

    private fun render() {
        if (_binding == null) return
        val connected = connectedId != null
        binding.pairingContent.visibility = if (connected) View.GONE else View.VISIBLE
        binding.connectedContent.visibility = if (connected) View.VISIBLE else View.GONE
        // The screen is read from, often across a room. It should not dim mid-code.
        binding.root.keepScreenOn = waiting()
        if (connected) {
            binding.connectedTitle.text =
                getString(R.string.parent_child_connected, connectedLabel.orEmpty())
            return
        }

        val current = code
        val expired = current != null && !waiting()
        binding.codeProgress.visibility = if (requesting) View.VISIBLE else View.GONE
        binding.codeText.visibility =
            if (current != null && !requesting) View.VISIBLE else View.INVISIBLE
        binding.codeText.text = current?.let(PairingCode::format)
        binding.codeText.alpha = if (expired) 0.35f else 1f
        binding.codeText.contentDescription = current?.let { it.toCharArray().joinToString(" ") }

        val showWaiting = current != null && !expired
        binding.waitingDivider.visibility = if (showWaiting) View.VISIBLE else View.GONE
        binding.waitingRow.visibility = if (showWaiting) View.VISIBLE else View.GONE
        binding.newCodeButton.visibility = if (expired || failed) View.VISIBLE else View.GONE
        renderExpiry()
    }

    private fun renderExpiry() {
        if (_binding == null) return
        val text = binding.expiryText
        text.setTextColor(requireContext().getColor(R.color.on_surface_muted))
        when {
            requesting -> text.setText(R.string.parent_code_getting)
            failed -> {
                text.setText(R.string.parent_code_failed)
                text.setTextColor(requireContext().getColor(R.color.status_alert))
            }
            code == null -> text.text = null
            !waiting() -> {
                text.setText(R.string.parent_code_expired)
                text.setTextColor(requireContext().getColor(R.color.status_alert))
                // Stopped waiting: re-render once so the waiting line and the new-code button
                // swap over at the moment the countdown reaches zero.
                if (binding.waitingRow.visibility == View.VISIBLE) render()
            }
            else -> {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(deadline - System.currentTimeMillis())
                text.text = getString(
                    R.string.parent_code_expires,
                    DateUtils.formatElapsedTime(seconds.coerceAtLeast(0)),
                )
            }
        }
    }

    private companion object {
        const val POLL_MS = 4_000L
        const val TICK_MS = 1_000L
        const val HAND_OVER_MS = 1_600L
        val CODE_LIFETIME_MS = TimeUnit.MINUTES.toMillis(10)

        const val STATE_CODE = "code"
        const val STATE_DEADLINE = "deadline"
        const val STATE_BASELINE = "baseline"
        const val STATE_CONNECTED_ID = "connectedId"
        const val STATE_CONNECTED_LABEL = "connectedLabel"
        const val STATE_FAILED = "failed"
    }
}
