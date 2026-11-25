package com.riverrunner.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stack pressure is layered on the existing styles, and squid penalties move
 * chips without creating or destroying them. Saves are untouched: hand records
 * keep the old line format.
 */
class PressureTest {

    private fun six(stack: Int, hole: String, button: Int = 0): HandEngine = HandEngine(
        HandSetup(50, 100, 0, button, List(6) { SeatSetup(it, "P$it", stack) }),
        PresetCardSource(
            (0 until 6).associateWith { Cards.parseMany(hole) },
            Cards.parseMany("2c3d4h5s6c"),
        ),
    )

    private fun foldTo(e: HandEngine, seat: Int) {
        var n = 0
        while (e.phase == Phase.BETTING && e.toAct != seat) {
            e.apply(Move.FOLD)
            check(++n < 12) { "never reached seat $seat" }
        }
    }

    /** Button, unopened. */
    private fun buttonOpen(stack: Int, hole: String): HandEngine {
        val e = six(stack, hole)
        foldTo(e, 0)
        check(e.toAct == 0)
        return e
    }

    private fun foldProb(e: HandEngine, style: BotStyle, ctx: PressureContext): Double =
        Bot.decide(e, e.toAct, style, Random(1), pressure = ctx).probability(MoveType.FOLD)

    @Test fun stylesAreUnchanged() {
        assertEquals(1.0, BotStyle.GTO.looseness, 0.0)
        assertEquals(0.85, BotStyle.TAG.looseness, 0.0)
        assertEquals(1.35, BotStyle.LAG.looseness, 0.0)
        assertEquals(1.5, BotStyle.STATION.looseness, 0.0)
        assertEquals(0.6, BotStyle.NIT.looseness, 0.0)
        assertEquals(1.9, BotStyle.MANIAC.looseness, 0.0)
        assertEquals(0.0, BotStyle.GTO.callBias, 0.0)
        assertEquals(-0.06, BotStyle.NIT.callBias, 0.0)
        assertEquals(1.0, BotStyle.GTO.bluff, 0.0)
        assertEquals(0.35, BotStyle.NIT.bluff, 0.0)
        val off = Pressure.tune(BotStyle.NIT, six(10000, "AsKd"), 3, PressureContext.OFF)
        assertEquals(BotStyle.NIT.looseness, off.looseness, 0.0)
        assertEquals(BotStyle.NIT.bluff, off.bluff, 0.0)
        assertEquals(1.0, off.foldDamp, 0.0)
        assertEquals(0.0, off.pushFoldBb, 0.0)
        assertEquals(1.0, off.pushMult, 0.0)
    }

    @Test fun pressureKeepsStyleOrder() {
        val e = six(10000, "AsKd")
        val ctx = PressureContext.on(10000)
        val nit = Pressure.tune(BotStyle.NIT, e, 0, ctx)
        val gto = Pressure.tune(BotStyle.GTO, e, 0, ctx)
        val lag = Pressure.tune(BotStyle.LAG, e, 0, ctx)
        val maniac = Pressure.tune(BotStyle.MANIAC, e, 0, ctx)
        assertTrue(nit.looseness < gto.looseness)
        assertTrue(gto.looseness < lag.looseness)
        assertTrue(lag.looseness < maniac.looseness)
        assertTrue(nit.aggression < lag.aggression)
        assertTrue(nit.bluff < gto.bluff)
    }

    @Test fun depthShrinkingAndSquidMoveTheKnobs() {
        val deep = Pressure.tune(BotStyle.GTO, six(10000, "AsKd"), 0, PressureContext.on(10000))
        assertTrue(deep.callBias > BotStyle.GTO.callBias, "deep stacks call more")
        assertTrue(deep.bluff > BotStyle.GTO.bluff, "deep stacks bluff more")
        assertTrue(deep.preflopCall > BotStyle.GTO.preflopCall)
        assertTrue(deep.looseness > BotStyle.GTO.looseness)
        assertTrue(deep.foldDamp < 1.0)
        assertEquals(0.0, deep.pushFoldBb, 0.0)

        val short = Pressure.tune(BotStyle.GTO, six(1500, "AsKd"), 0, PressureContext.on(1500))
        assertTrue(short.pushFoldBb >= PressureConfig.SHORT_BB)
        val tiny = Pressure.tune(BotStyle.GTO, six(800, "AsKd"), 0, PressureContext.on(800))
        assertTrue(tiny.pushMult > short.pushMult, "8bb jams wider than 15bb")
        assertTrue(tiny.pushFoldBb >= PressureConfig.SHORT_BB)

        val flat = Pressure.tune(BotStyle.GTO, six(4000, "AsKd"), 0, PressureContext.on(4000))
        val shrunk = Pressure.tune(BotStyle.GTO, six(4000, "AsKd"), 0, PressureContext.on(10000))
        assertTrue(shrunk.looseness > flat.looseness, "a shrinking stack opens wider")
        assertTrue(shrunk.aggression > flat.aggression)
        assertTrue(shrunk.foldDamp < flat.foldDamp)
        assertEquals(0.0, Pressure.shrink01(10000, 10000), 0.0)
        assertEquals(1.0, Pressure.shrink01(4000, 10000), 0.0)
        assertTrue(Pressure.shrink01(8000, 10000) in 0.1..0.5)

        val early = Pressure.tune(BotStyle.GTO, six(10000, "AsKd"), 0, PressureContext.on(10000, true, 0.25))
        val late = Pressure.tune(BotStyle.GTO, six(10000, "AsKd"), 0, PressureContext.on(10000, true, 1.0))
        val safe = Pressure.tune(BotStyle.GTO, six(10000, "AsKd"), 0, PressureContext.on(10000, false, 1.0))
        assertTrue(late.looseness > early.looseness)
        assertTrue(late.aggression > early.aggression)
        assertTrue(late.foldDamp < early.foldDamp)
        assertEquals(deep.looseness, safe.looseness, 1e-9)
    }

    @Test fun shortStackShovesAndShrinkingStackStopsFolding() {
        val hole = listOf("Kd9c", "Qh8s", "Jc7d", "Ts6h", "9d7c", "8s5d", "Ah3c", "Kh4d", "Qs7c", "Jd6s", "Th5c", "9c4d").first { h ->
            val c = Cards.parseMany(h)
            HandClasses.percentile(c[0], c[1]) in 0.58..0.82
        }
        val deep = buttonOpen(10000, hole)
        val short = buttonOpen(800, hole)
        val pDeep = Bot.decide(deep, 0, BotStyle.GTO, Random(1), pressure = PressureContext.on(10000))
        val pShort = Bot.decide(short, 0, BotStyle.GTO, Random(1), pressure = PressureContext.on(800))
        assertTrue(
            pShort.probability(MoveType.RAISE) > pDeep.probability(MoveType.RAISE) + 0.25,
            "$hole short raise ${pShort.probability(MoveType.RAISE)} deep ${pDeep.probability(MoveType.RAISE)}",
        )
        val max = short.legalActions().maxRaiseTo
        assertTrue(pShort.options.filter { it.first.type == MoveType.RAISE }.all { it.first.raiseTo == max })

        // 12bb is a normal open without pressure, and a shove with it.
        val mid = buttonOpen(1200, "AsKd")
        val off = Bot.decide(mid, 0, BotStyle.GTO, Random(1))
        val on = Bot.decide(mid, 0, BotStyle.GTO, Random(1), pressure = PressureContext.on(1200))
        val jamTo = mid.legalActions().maxRaiseTo
        assertTrue(off.probability(MoveType.RAISE) > 0.8)
        assertTrue(off.options.any { it.first.type == MoveType.RAISE && it.first.raiseTo < jamTo })
        assertTrue(on.options.filter { it.first.type == MoveType.RAISE }.all { it.first.raiseTo == jamTo })

        val edge = listOf("Kd9c", "Qh8s", "Jc7d", "Ts6h", "9d7c", "Ah3c", "Kh4d", "Qs7c").first { h ->
            val c = Cards.parseMany(h)
            HandClasses.percentile(c[0], c[1]) in 0.50..0.62
        }
        val forty = buttonOpen(4000, edge)
        val flatFold = foldProb(forty, BotStyle.GTO, PressureContext.on(4000))
        val shrunkFold = foldProb(forty, BotStyle.GTO, PressureContext.on(10000))
        assertTrue(shrunkFold + 0.2 < flatFold, "$edge flat $flatFold shrunk $shrunkFold")
    }

    @Test fun pressureLowersPreflopFoldRateForEveryStyle() {
        val holes = listOf(
            "AsKd", "AhQh", "KsQs", "JsTs", "Td9d", "9h8h", "8s7s", "7c6c",
            "As5h", "Kh9d", "Qc8s", "Jh6d", "Ts4d", "Ad9c", "Kc7s", "Qs4c",
        )
        val off = HashMap<BotStyle, Double>()
        val on = HashMap<BotStyle, Double>()
        val n = HashMap<BotStyle, Int>()
        for (hole in holes) {
            for (style in BotStyle.entries) {
                val spots = listOf(
                    six(10000, hole), // UTG, unopened
                    buttonOpen(10000, hole),
                    six(10000, hole).also { it.apply(Move.raiseTo(250)) }, // HJ facing an open
                    buttonOpen(10000, hole).also {
                        it.apply(Move.raiseTo(250))
                        it.apply(Move.FOLD) // SB folds, BB faces the button
                    },
                )
                for (e in spots) {
                    check(e.phase == Phase.BETTING)
                    off[style] = (off[style] ?: 0.0) + foldProb(e, style, PressureContext.OFF)
                    on[style] = (on[style] ?: 0.0) + foldProb(e, style, PressureContext.on(10000))
                    n[style] = (n[style] ?: 0) + 1
                }
            }
        }
        var offAll = 0.0
        var onAll = 0.0
        var nAll = 0
        for (style in BotStyle.entries) {
            val o = off.getValue(style) / n.getValue(style)
            val x = on.getValue(style) / n.getValue(style)
            // A maniac already folds little; everyone else should drop by a clearly visible amount,
            // and nobody should fold more than they did without pressure.
            if (o >= 0.30) assertTrue(x < o - 0.03, "$style fold $o -> $x")
            else assertTrue(x <= o + 1e-9, "$style fold rose $o -> $x")
            offAll += off.getValue(style)
            onAll += on.getValue(style)
            nAll += n.getValue(style)
        }
        val before = offAll / nAll
        val after = onAll / nAll
        assertTrue(after < before - 0.06, "overall preflop fold $before -> $after")
    }

    @Test fun squidUrgencyRampsAndResets() {
        val t = SquidTracker(SquidRules(6, 1.0))
        assertEquals(0.25, t.urgency(), 1e-9)
        assertEquals(6, t.status(setOf(0, 1, 2)).handsLeft)
        assertNull(t.completeHand(setOf(1)))
        assertTrue(t.urgency() > 0.25)
        assertTrue(t.isSafe(1))
        assertEquals(2, t.status(setOf(0, 1, 2)).unsafeCount)
        repeat(4) { assertNull(t.completeHand(setOf(1))) }
        assertEquals(1.0, t.urgency(), 1e-9)
        assertEquals(1, t.status(setOf(0, 1, 2)).handsLeft)
        val closed = t.completeHand(setOf(0))
        assertNotNull(closed)
        assertTrue(0 in closed && 1 in closed)
        assertEquals(0.25, t.urgency(), 1e-9)
        assertEquals(1, t.status(setOf(0, 1, 2)).handInRound)
        assertTrue(t.status(setOf(0, 1, 2)).safeSeats.isEmpty())
        assertEquals(1.0, SquidTracker(SquidRules(1, 2.0)).urgency(), 0.0)
    }

    @Test fun squidPenaltyConservesChipsAndCapsAtTheStack() {
        val even = Squid.settle(mapOf(0 to 1000, 1 to 50, 2 to 800, 3 to 800), setOf(2, 3), 100, 1.0)
        assertNotNull(even)
        assertEquals(-100, even.delta[0])
        assertEquals(-50, even.delta[1])
        assertEquals(75, even.delta[2])
        assertEquals(75, even.delta[3])
        assertEquals(0, even.delta.values.sum())
        assertEquals(setOf(0, 1), even.unsafe)
        assertEquals(100, even.penaltyChips)

        val rem = Squid.settle(mapOf(0 to 500, 1 to 500, 2 to 500, 3 to 1000), setOf(0, 1, 2), 100, 1.0)
        assertNotNull(rem)
        assertEquals(-100, rem.delta[3])
        assertEquals(34, rem.delta[0])
        assertEquals(33, rem.delta[1])
        assertEquals(33, rem.delta[2])
        assertEquals(0, rem.delta.values.sum())

        assertNull(Squid.settle(mapOf(0 to 100, 1 to 100), setOf(0, 1), 100, 1.0))
        assertNull(Squid.settle(mapOf(0 to 100, 1 to 100), emptySet(), 100, 1.0))
        // a busted seat is not in the map, so it neither pays nor is paid
        val gone = Squid.settle(mapOf(0 to 400, 1 to 400), setOf(0, 2), 100, 1.0)
        assertNotNull(gone)
        assertEquals(-100, gone.delta[1])
        assertEquals(100, gone.delta[0])
        assertEquals(null, gone.delta[2])
    }

    @Test fun uncalledReturnDoesNotMakeTheLoserSafe() {
        // Seat 0 covers both, loses every pot, and only gets the unmatched shove back.
        // Seat 1 (AA, short) wins the main pot. Seat 2 (KK) wins the side pot.
        val e = HandEngine(
            HandSetup(50, 100, 0, 0, listOf(SeatSetup(0, "P0", 1000), SeatSetup(1, "P1", 300), SeatSetup(2, "P2", 600))),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("7c2d"), 1 to Cards.parseMany("AsAh"), 2 to Cards.parseMany("KsKh")),
                Cards.parseMany("QdJd9c4s3h"),
            ),
        )
        e.apply(Move.raiseTo(1000))
        e.apply(Move.CALL)
        e.apply(Move.CALL)
        while (e.phase == Phase.STREET_DONE) e.dealNextStreet()
        val r = assertNotNull(e.result)
        assertTrue((r.won[0] ?: 0) > 0, "uncalled chips came back to the losing bettor, won=${r.won}")
        assertEquals(setOf(1, 2), Squid.potWinners(r))
        assertTrue(0 !in Squid.potWinners(r))

        val total = e.players.sumOf { it.stack }
        val s = GameSession(SessionConfig(GameMode.CASH, 3, 10, TableType.GTO, seed = 1, squid = SquidRules(1, 1.0)))
        s.completeHand(e)
        val settlement = assertNotNull(s.lastSquidSettlement)
        assertEquals(setOf(1, 2), settlement.safe)
        assertEquals(setOf(0), settlement.unsafe)
        assertEquals(0, settlement.delta.values.sum())
        assertEquals(total, s.players.sumOf { it.stack })
        assertTrue((settlement.delta[0] ?: 0) < 0)
    }

    @Test fun sidePotSplitAndFoldWinners() {
        // Side pot: smallest stack has the best hand, the oversized bettor wins the side pot
        // and also receives an uncalled return. Both are pot winners. The middle stack is not.
        val side = HandEngine(
            HandSetup(50, 100, 0, 0, listOf(SeatSetup(0, "P0", 1000), SeatSetup(1, "P1", 300), SeatSetup(2, "P2", 600))),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("KsKh"), 1 to Cards.parseMany("AsAh"), 2 to Cards.parseMany("QsQh")),
                Cards.parseMany("2c7d9hJc3d"),
            ),
        )
        side.apply(Move.raiseTo(1000))
        side.apply(Move.CALL)
        side.apply(Move.CALL)
        while (side.phase == Phase.STREET_DONE) side.dealNextStreet()
        val sideResult = assertNotNull(side.result)
        assertEquals(600 + 400, sideResult.won[0])
        assertEquals(setOf(0, 1), Squid.potWinners(sideResult))
        assertTrue(2 !in Squid.potWinners(sideResult))
        assertEquals(1900, side.players.sumOf { it.stack })

        // Split: the folder wins nothing. The two live players chop the board.
        val split = HandEngine(
            HandSetup(50, 100, 0, 0, listOf(SeatSetup(0, "P0", 1000), SeatSetup(1, "P1", 1000), SeatSetup(2, "P2", 1000))),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("2c3d"), 1 to Cards.parseMany("7c8d"), 2 to Cards.parseMany("2h3h")),
                Cards.parseMany("AsKdQcJsTd"),
            ),
        )
        split.apply(Move.raiseTo(300))
        split.apply(Move.FOLD)
        split.apply(Move.CALL)
        while (split.phase != Phase.HAND_DONE) {
            if (split.phase == Phase.STREET_DONE) split.dealNextStreet() else split.apply(Move.CHECK)
        }
        val splitResult = assertNotNull(split.result)
        assertEquals(setOf(0, 2), Squid.potWinners(splitResult))
        assertEquals(0, splitResult.won[1] ?: 0)
        assertTrue(1 !in Squid.potWinners(splitResult))
        assertEquals(3000, split.players.sumOf { it.stack })

        // Folded around: the opener wins the pot and also gets the uncalled raise back.
        // The return does not add any other winner. Folded seats stay unsafe.
        val folded = HandEngine(
            HandSetup(50, 100, 0, 0, listOf(SeatSetup(0, "P0", 1000), SeatSetup(1, "P1", 1000), SeatSetup(2, "P2", 1000))),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("AsKs"), 1 to Cards.parseMany("2c7d"), 2 to Cards.parseMany("3c8d")),
                Cards.parseMany("QsJsTs3h4d"),
            ),
        )
        folded.apply(Move.raiseTo(300))
        folded.apply(Move.FOLD)
        folded.apply(Move.FOLD)
        val foldResult = assertNotNull(folded.result)
        assertTrue((foldResult.uncalledReturned?.second ?: 0) > 0)
        assertEquals(setOf(0), Squid.potWinners(foldResult))
        assertEquals(3000, folded.players.sumOf { it.stack })

        val s = GameSession(SessionConfig(GameMode.CASH, 3, 10, TableType.GTO, seed = 2, squid = SquidRules(2, 1.0)))
        val before = s.players.sumOf { it.stack }
        s.completeHand(folded)
        assertEquals(setOf(0), s.squidStatus()!!.safeSeats)
        assertNull(s.lastSquidSettlement)
        // Button is seat 1, so seat 1 acts first, raises, and the others fold.
        // Seat 1 wins the pot (and is handed back the uncalled raise) and becomes safe.
        // Seat 2 won neither hand, so seat 2 pays when the round closes.
        val hand2 = HandEngine(
            HandSetup(50, 100, 0, 1, s.players.map { SeatSetup(it.seat, it.name, it.stack) }),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("AsKs"), 1 to Cards.parseMany("QcQd"), 2 to Cards.parseMany("3c8d")),
                Cards.parseMany("9s8h4c5d6h"),
            ),
        )
        assertEquals(1, hand2.toAct)
        hand2.apply(Move.raiseTo(300))
        hand2.apply(Move.FOLD)
        hand2.apply(Move.FOLD)
        val endTotal = hand2.players.sumOf { it.stack }
        s.completeHand(hand2)
        val settlement = assertNotNull(s.lastSquidSettlement)
        assertEquals(setOf(0, 1), settlement.safe)
        assertEquals(setOf(2), settlement.unsafe)
        assertTrue((settlement.delta[2] ?: 0) < 0)
        assertEquals(0, settlement.delta.values.sum())
        assertEquals(endTotal, s.players.sumOf { it.stack })
        assertEquals(before, endTotal)
    }

    @Test fun sessionAppliesSquidWithoutBreakingRecordsOrChipTotal() {
        fun stacksAfter(squid: SquidRules?, hands: Int, size: Int = 3): Pair<List<Int>, SquidSettlement?> {
            val s = GameSession(SessionConfig(GameMode.CASH, size, 100, TableType.GTO, seed = 4, squid = squid))
            val total = s.players.sumOf { it.stack }
            repeat(hands) { foldToBlind(s) }
            assertEquals(total, s.players.sumOf { it.stack }, "chips created or destroyed")
            assertTrue(s.players.all { it.stack >= 0 })
            return s.players.map { it.stack } to s.lastSquidSettlement
        }

        // Heads-up, two hands: each player wins one (the big blind changes), so nobody pays.
        val plainHu = stacksAfter(null, 2, 2)
        val squidHu = stacksAfter(SquidRules(2, 1.0), 2, 2)
        assertEquals(plainHu.first, squidHu.first)
        assertNull(squidHu.second)

        // Three-handed, two hands: two different blinds win, one player pays.
        val plain = stacksAfter(null, 2, 3)
        val squid = stacksAfter(SquidRules(2, 1.0), 2, 3)
        assertEquals(plain.first.sum(), squid.first.sum())
        assertTrue(plain.first != squid.first)
        val settlement = assertNotNull(squid.second)
        assertEquals(1, settlement.unsafe.size)
        assertEquals(2, settlement.safe.size)
        assertEquals(100, settlement.penaltyChips)
        assertEquals(0, settlement.delta.values.sum())

        val s = GameSession(SessionConfig(GameMode.CASH, 3, 100, TableType.GTO, seed = 4, squid = SquidRules(2, 1.0)))
        foldToBlind(s)
        val line = foldToBlindRecord(s)
        val back = assertNotNull(HandRecord.decode(line))
        assertEquals(line, back.encode())
        // the next round starts clean
        assertEquals(1, s.squidStatus()!!.handInRound)
        assertTrue(s.squidStatus()!!.safeSeats.isEmpty())

        val live = GameSession(SessionConfig(GameMode.CASH, 6, 100, TableType.GTO, seed = 2, squid = SquidRules(6, 1.0)))
        val e = live.startHand()
        val ctx = live.pressureFor(e, 3)
        assertTrue(ctx.enabled && ctx.squidUnsafe)
        // Legacy 1 starting big blind is 1% of a 100BB stack, so the weight is 0.2 and the
        // first-hand clock (0.25) becomes 0.05. It is not the old clock alone.
        assertEquals(0.05, ctx.squidUrgency, 1e-9)
        assertEquals(6, live.squidStatus()!!.handsLeft)
        assertEquals(6, live.squidStatus()!!.unsafeCount)
    }

    private fun foldToBlind(s: GameSession): Set<Int> {
        val e = s.startHand()
        var n = 0
        while (e.phase != Phase.HAND_DONE) {
            if (e.phase == Phase.STREET_DONE) e.dealNextStreet()
            else {
                val la = e.legalActions()
                e.apply(if (la.canCheck) Move.CHECK else Move.FOLD)
            }
            check(++n < 40) { "hand did not finish" }
        }
        val winners = Squid.potWinners(e.result!!)
        s.completeHand(e)
        return winners
    }

    private fun foldToBlindRecord(s: GameSession): String {
        val e = s.startHand()
        var n = 0
        while (e.phase != Phase.HAND_DONE) {
            if (e.phase == Phase.STREET_DONE) e.dealNextStreet()
            else {
                val la = e.legalActions()
                e.apply(if (la.canCheck) Move.CHECK else Move.FOLD)
            }
            check(++n < 40)
        }
        return s.completeHand(e).encode()
    }
}
