package io.github.beacon.app.domain.policy

/**
 * Decides whether a server probe may take over the displayed verdict.
 *
 * Returning to the foreground re-checks the saved server, but the toolbar entry and the status card
 * keep the previous verdict while it runs: an always-healthy server must not degrade to a visible
 * "checking" state on every entry. Only the first probe (nothing to preserve) and explicit user
 * actions (the user asked for it) show progress.
 */
object ServerProbePolicy {
    fun showsProgress(manual: Boolean, hasVerdict: Boolean): Boolean = manual || !hasVerdict
}
