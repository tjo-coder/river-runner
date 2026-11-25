package com.riverrunner.engine

/**
 * One squid round. [handsPerRound] is the orbit length (a full table is one orbit).
 *
 * The penalty is a percent of each player's **session starting stack** when
 * [penaltyPercent] is set (2, 5 or 10). That stack is the chips they were dealt
 * when the session began. This app has no rebuy: a player who reaches zero is
 * out, and the starting stack is never replaced or increased. Rising blinds do
 * not change the chip penalty. In a tournament the same chips are simply worth
 * fewer big blinds after a level.
 *
 * [penaltyPercent] null keeps an older save: [penaltyBb] starting big blinds
 * (the blind at the beginning of the session, not the current level). A stored
 * "5" from that era means 5 starting big blinds, not 5 percent.
 */
data class SquidRules(
    val handsPerRound: Int,
    /** Used only when [penaltyPercent] is null. */
    val penaltyBb: Double = 1.0,
    /** Whole percent of the session starting stack. Null = legacy big-blind amount. */
    val penaltyPercent: Int? = null,
) {
    init {
        require(handsPerRound >= 1) { "squid round must be at least 1 hand" }
        val pct = penaltyPercent
        if (pct == null) require(penaltyBb > 0.0) { "squid penalty must be positive" }
        else require(pct in 1..100) { "squid percent out of range" }
    }

    companion object {
        val PERCENT_CHOICES = intArrayOf(2, 5, 10)
        const val DEFAULT_PERCENT = 5

        /** One hand per seat: a full orbit. Defaults to [DEFAULT_PERCENT] of the starting stack. */
        fun orbit(tableSize: Int, penaltyPercent: Int = DEFAULT_PERCENT) =
            SquidRules(tableSize.coerceAtLeast(1), penaltyPercent = penaltyPercent)

        /**
         * Turns saved settings into rules without reading an old big-blind number as a percent.
         * [storedPercent] 0 means the new key was never written. [legacyPresent] is true only
         * when the old key exists in the save, not when a getter would invent a default.
         */
        fun fromSaved(enabled: Boolean, handsPerRound: Int, storedPercent: Int, legacyPresent: Boolean, legacyBb: Int): SquidRules? {
            if (!enabled) return null
            val hands = handsPerRound.coerceAtLeast(1)
            if (storedPercent in PERCENT_CHOICES) return SquidRules(hands, penaltyPercent = storedPercent)
            if (legacyPresent) return SquidRules(hands, penaltyBb = legacyBb.coerceIn(1, 20).toDouble())
            return SquidRules(hands, penaltyPercent = DEFAULT_PERCENT)
        }
    }
}

/** What the table shows during a hand. */
data class SquidStatus(
    /** 1-based index of the hand being played inside the round. */
    val handInRound: Int,
    val handsPerRound: Int,
    /** Hands still to play, including the current one. */
    val handsLeft: Int,
    val penaltyBb: Double,
    val safeSeats: Set<Int>,
    val unsafeCount: Int,
)

/**
 * What one finished hand knew about squid. Absent on hands saved before this
 * ledger existed. [settlementDeltas] is null when the round did not close on
 * this hand. There is no rebuy, so [sessionStartStack] stays the opening deal.
 */
data class SquidHandNote(
    val handsPerRound: Int,
    val penaltyPercent: Int?,
    val legacyPenaltyBb: Double?,
    val penaltyChips: Int,
    val sessionStartStack: Int,
    val sessionStartBlind: Int,
    val handInRound: Int,
    val handsLeftBefore: Int,
    val safeBefore: List<Int>,
    val settlementDeltas: Map<Int, Int>?,
) {
    fun encode(): String = buildString {
        append(handsPerRound).append(';')
        append(penaltyPercent?.toString() ?: "-").append(';')
        append(legacyPenaltyBb?.let { trimNum(it) } ?: "-").append(';')
        append(penaltyChips).append(';')
        append(sessionStartStack).append(';')
        append(sessionStartBlind).append(';')
        append(handInRound).append(';')
        append(handsLeftBefore).append(';')
        append(safeBefore.joinToString(",")).append(';')
        append(settlementDeltas?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}:${it.value}" } ?: "-")
    }

    companion object {
        private fun trimNum(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

        fun decode(text: String): SquidHandNote? = try {
            val f = text.split(';')
            require(f.size == 10)
            fun optInt(s: String) = if (s == "-") null else s.toInt()
            fun optDouble(s: String) = if (s == "-") null else s.toDouble()
            val safe = if (f[8].isEmpty()) emptyList() else f[8].split(',').map { it.toInt() }
            val deltas = if (f[9] == "-") null else f[9].split(',').filter { it.isNotEmpty() }.associate {
                val p = it.split(':'); p[0].toInt() to p[1].toInt()
            }
            SquidHandNote(
                f[0].toInt(), optInt(f[1]), optDouble(f[2]), f[3].toInt(), f[4].toInt(), f[5].toInt(),
                f[6].toInt(), f[7].toInt(), safe, deltas,
            )
        } catch (e: Exception) { null }
    }
}

/** Chips moved when a round closes. [delta] sums to zero. */
data class SquidSettlement(
    val safe: Set<Int>,
    val unsafe: Set<Int>,
    /** Chips each unsafe player was assessed, before the stack cap. */
    val penaltyChips: Int,
    /** Stack change per seat. Negative means they paid. */
    val delta: Map<Int, Int>,
)

/**
 * Who has won a hand in the current round, and how close the round is to ending.
 * Call [completeHand] once per finished hand, in order.
 */
class SquidTracker(val rules: SquidRules) {
    private var completed = 0
    private val safe = LinkedHashSet<Int>()

    fun isSafe(seat: Int): Boolean = seat in safe

    /**
     * 0.25 on the first hand (a little urgency already) and 1 on the last.
     * A one-hand round is entirely urgent.
     */
    fun urgency(): Double {
        if (rules.handsPerRound <= 1) return 1.0
        val span = rules.handsPerRound - 1
        val idx = completed.coerceIn(0, span)
        val t = idx.toDouble() / span
        return 0.25 + 0.75 * t * t
    }

    fun status(alive: Set<Int>): SquidStatus {
        val safeAlive = safe.filter { it in alive }.toSet()
        return SquidStatus(
            handInRound = completed + 1,
            handsPerRound = rules.handsPerRound,
            handsLeft = (rules.handsPerRound - completed).coerceAtLeast(1),
            penaltyBb = rules.penaltyBb,
            safeSeats = safeAlive,
            unsafeCount = alive.count { it !in safe },
        )
    }

    /**
     * Records [winners] (seats who took chips from a pot). When the round is
     * over, returns the safe seats and starts the next round. Null otherwise.
     */
    fun completeHand(winners: Set<Int>): Set<Int>? {
        safe.addAll(winners)
        completed++
        if (completed < rules.handsPerRound) return null
        val closed = safe.toSet()
        safe.clear()
        completed = 0
        return closed
    }
}

object Squid {

    /**
     * Chips one player owes if they finish the round unsafe, before the stack cap.
     * [sessionStartStack] and [sessionStartBlind] are fixed at the deal of the session.
     * The current blind level is intentionally not an argument: the penalty must not
     * grow when the blinds go up. Zero stack or a non-positive rate produces 0, not a
     * forced 1-chip charge.
     */
    fun penaltyChips(sessionStartStack: Int, sessionStartBlind: Int, rules: SquidRules): Int {
        val pct = rules.penaltyPercent
        if (pct != null) {
            if (sessionStartStack <= 0 || pct <= 0) return 0
            return (sessionStartStack.toLong() * pct / 100L).toInt()
        }
        if (sessionStartBlind <= 0 || rules.penaltyBb <= 0.0) return 0
        return (rules.penaltyBb * sessionStartBlind).toInt()
    }

    /**
     * How much of the squid urgency knob this penalty is worth. 0 when nothing is owed.
     * 1 when the penalty is at least 5% of the session starting stack (the default, and
     * the top of the 2/5/10 choices saturates here). This is not a solver adjustment.
     * Short-stack push/fold stays in the separate pressure layer.
     */
    fun penaltyUrgencyWeight(penaltyChips: Int, sessionStartStack: Int): Double {
        if (penaltyChips <= 0 || sessionStartStack <= 0) return 0.0
        val percent = penaltyChips * 100.0 / sessionStartStack
        return (percent / SquidRules.DEFAULT_PERCENT).coerceIn(0.0, 1.0)
    }

    /** Round-clock urgency times the penalty weight, still in 0..1. */
    fun combinedUrgency(time: Double, penaltyChips: Int, sessionStartStack: Int): Double {
        if (time <= 0.0) return 0.0
        return (time.coerceIn(0.0, 1.0) * penaltyUrgencyWeight(penaltyChips, sessionStartStack)).coerceIn(0.0, 1.0)
    }

    /**
     * Seats that won a pot. Uncalled chips returned to a bettor are not a win:
     * [HandResult.won] includes that return, so a player who lost the pot can
     * still have a positive entry there.
     */
    fun potWinners(result: HandResult): Set<Int> =
        result.pots.filter { it.amount > 0 }.flatMap { it.winners }.toSet()

    /**
     * Each alive unsafe player pays up to [penaltyBb] big blinds. What is
     * actually taken (never more than the stack) is split evenly across the
     * alive safe players. Leftover chips, one each, go to the lowest seat
     * numbers. Returns null when nobody would pay or nobody would receive.
     * The sum of [SquidSettlement.delta] is zero.
     */
    fun settle(stacks: Map<Int, Int>, safe: Set<Int>, bigBlind: Int, penaltyBb: Double): SquidSettlement? {
        val penalty = (penaltyBb * bigBlind).toInt()
        if (penalty <= 0) return null
        return settleAmounts(stacks, safe, stacks.keys.associateWith { penalty })
    }

    /**
     * Each alive unsafe player pays up to their own assessed amount (already in chips).
     * Nothing is paid when every assessment is 0. A player cannot pay more than their
     * stack. The chips taken are split evenly across the alive safe players. Leftover
     * chips, one each, go to the lowest seat numbers. Returns null when nobody would
     * pay or nobody would receive. The sum of [SquidSettlement.delta] is zero.
     * [SquidSettlement.penaltyChips] is the largest uncapped assessment, for display.
     */
    fun settleAmounts(stacks: Map<Int, Int>, safe: Set<Int>, penaltyBySeat: Map<Int, Int>): SquidSettlement? {
        val alive = stacks.filterValues { it > 0 }.keys
        val safeAlive = safe.intersect(alive).sorted()
        val unsafe = (alive - safe).sorted()
        if (safeAlive.isEmpty() || unsafe.isEmpty()) return null
        val assessed = unsafe.associateWith { (penaltyBySeat[it] ?: 0).coerceAtLeast(0) }
        val penalty = assessed.values.maxOrNull() ?: 0
        val paid = unsafe.associateWith { seat ->
            val due = assessed.getValue(seat)
            if (due <= 0) 0 else stacks.getValue(seat).coerceAtMost(due)
        }
        val pot = paid.values.sum()
        if (pot <= 0) return null
        val share = pot / safeAlive.size
        val rem = pot % safeAlive.size
        val delta = HashMap<Int, Int>()
        for (seat in unsafe) delta[seat] = -paid.getValue(seat)
        safeAlive.forEachIndexed { i, seat ->
            delta[seat] = share + if (i < rem) 1 else 0
        }
        return SquidSettlement(safeAlive.toSet(), unsafe.toSet(), penalty, delta)
    }
}
