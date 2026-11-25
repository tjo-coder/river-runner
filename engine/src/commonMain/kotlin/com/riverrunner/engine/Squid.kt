package com.riverrunner.engine

/**
 * One squid round. [handsPerRound] is the orbit length (a full table is one
 * orbit). At the end, each player who has not won a hand pays [penaltyBb]
 * big blinds, split by the players who have.
 */
data class SquidRules(
    val handsPerRound: Int,
    val penaltyBb: Double = 1.0,
) {
    init {
        require(handsPerRound >= 1) { "squid round must be at least 1 hand" }
        require(penaltyBb > 0.0) { "squid penalty must be positive" }
    }

    companion object {
        /** One hand per seat: a full orbit. */
        fun orbit(tableSize: Int, penaltyBb: Double = 1.0) =
            SquidRules(tableSize.coerceAtLeast(1), penaltyBb)
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
        val alive = stacks.filterValues { it > 0 }.keys
        val safeAlive = safe.intersect(alive).sorted()
        val unsafe = (alive - safe).sorted()
        if (safeAlive.isEmpty() || unsafe.isEmpty()) return null
        val penalty = (penaltyBb * bigBlind).toInt().coerceAtLeast(1)
        val paid = unsafe.associateWith { seat -> stacks.getValue(seat).coerceAtMost(penalty) }
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
