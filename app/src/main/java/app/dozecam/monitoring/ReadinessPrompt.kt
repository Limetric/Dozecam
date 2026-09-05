package app.dozecam.monitoring

/**
 * How loudly a failed bedtime check is allowed to speak outside settings.
 *
 * The card in settings is only ever seen by someone who goes looking, and a
 * permanent banner over the cameras would be a warning shown so often it stops
 * being read — the same fate as every notice that is right on the hundredth
 * night and wrong on the ninety-nine before it. So a failure interrupts exactly
 * once: the first time the viewer sees it, and never again for that failure
 * unless it clears and comes back.
 *
 * "Comes back" is doing the work there. Acknowledging is not "don't tell me
 * about this check"; it is "I have seen that this is broken now". A check that
 * starts passing is forgotten, so the next time notifications are revoked, or
 * Do Not Disturb is left on, the user is told again — which is what makes the
 * one interruption worth having.
 *
 * Kept apart from [Readiness] because it is a different question: that decides
 * what is true, this decides what is worth saying.
 */
object ReadinessPrompt {

    /**
     * The problems worth interrupting for: everything not passing that the user
     * has not already been shown.
     */
    fun unannounced(
        findings: List<ReadinessFinding>,
        acknowledged: Set<String>,
    ): List<ReadinessFinding> =
        findings.problems().filterNot { finding ->
            // Only when there is nothing left in it to be new about. A camera
            // row naming two rooms where one was acknowledged is still worth
            // saying, for the room that was not.
            identities(finding).all { it in acknowledged }
        }

    /**
     * What was acknowledged, said precisely enough that a *different* failure
     * of the same check still counts as new — and independently enough that one
     * room recovering cannot un-acknowledge another.
     *
     * Every check but one yields a single id: "alerts are off" is one fact, and
     * it is either acknowledged or it is not. The camera row is not one fact —
     * it stands for however many rooms are unheard, a set that changes as rooms
     * drop out and come back without the row ever passing in between. So it
     * yields one id per room, and each is forgotten only when that room is
     * heard again.
     */
    private fun identities(finding: ReadinessFinding): List<String> = when {
        finding.cameras.isEmpty() -> listOf(finding.check.name)
        // By id, never by name: a rename is not a room recovering, and two
        // rooms called the same thing are two rooms.
        else -> finding.cameras.map { room -> finding.check.name + ":" + room.cameraId }
    }

    /**
     * What to remember after showing [findings] — the failures that still
     * stand, and nothing else.
     *
     * Pruning is the whole mechanism: an id kept for a check that now passes
     * would silently spend the one interruption that check is owed the next
     * time it breaks. Returns the set unchanged when nothing moved, so a caller
     * can compare and skip a pointless write.
     */
    fun remembered(findings: List<ReadinessFinding>, acknowledged: Set<String>): Set<String> {
        val failing = findings.problems().flatMap(::identities).toSet()
        // A check that stood aside keeps whatever was already recorded against
        // it. Standing aside is not recovering: the camera row names no room
        // whenever monitoring is starting up or a permission is missing, and
        // the channel rows say nothing at all while notifications are denied.
        // Pruned on those, a failure the user was told about last week would be
        // announced as new the moment its prerequisite came back — which is the
        // opposite of one interruption.
        val undecided = findings.filter { it.masked }.map { it.check.name }.toSet()
        return acknowledged
            .filterTo(mutableSetOf()) { it in failing || it.substringBefore(':') in undecided }
    }

    /**
     * [acknowledged], plus everything in [shown] — purely additive.
     *
     * Deliberately not pruned here. What is handed to this is the *prompt*: the
     * few failures a person has just been shown, not the whole checklist. Taking
     * that partial list as the state of the night would drop the acknowledgement
     * of every other failure still standing — so acknowledging a fresh
     * alarm-volume failure would make an old, already-dismissed battery warning
     * pop straight back up. Forgetting is [remembered]'s job, and it is asked of
     * the full findings.
     */
    fun acknowledging(
        shown: List<ReadinessFinding>,
        acknowledged: Set<String>,
    ): Set<String> = acknowledged + shown.problems().flatMap(::identities)

    /**
     * The same, for one check that has been said out loud by something other
     * than the prompt.
     *
     * The full-screen-access explanation is the case this exists for: it is a
     * dialog about exactly the thing [ReadinessCheck.WAKE_SCREEN] reports, and
     * answering it must count, or the prompt would raise a second modal about
     * the same missing grant moments after the first was dismissed.
     */
    fun acknowledging(check: ReadinessCheck, acknowledged: Set<String>): Set<String> =
        acknowledging(
            listOf(ReadinessFinding(check, ReadinessState.FAIL)),
            acknowledged,
        )
}
