package com.riverrunner.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PenaltyScaleTest {
    @Test fun percentUsesStartingStackAndIgnoresLaterBlinds() {
        val rules = SquidRules(6, penaltyPercent = 5)
        val start = 100 * GameSession.BASE_BB
        assertEquals(500, Squid.penaltyChips(start, GameSession.BASE_BB, rules))
        assertEquals(100, Squid.penaltyChips(20 * GameSession.BASE_BB, GameSession.BASE_BB, rules))
        assertEquals(0, Squid.penaltyChips(0, GameSession.BASE_BB, rules))
        // A later tournament blind is not an input. The chip amount stays 500.
        assertEquals(500, Squid.penaltyChips(start, 1000, rules))
        val two = SquidRules(6, penaltyPercent = 2)
        val ten = SquidRules(6, penaltyPercent = 10)
        assertEquals(200, Squid.penaltyChips(start, GameSession.BASE_BB, two))
        assertEquals(1000, Squid.penaltyChips(start, GameSession.BASE_BB, ten))
    }

    @Test fun legacyBlindIsNotReadAsPercent() {
        val unset = SquidRules.fromSaved(true, 6, storedPercent = 0, legacyPresent = false, legacyBb = 1)
        assertEquals(5, unset!!.penaltyPercent)
        val oldFive = SquidRules.fromSaved(true, 6, storedPercent = 0, legacyPresent = true, legacyBb = 5)
        assertNull(oldFive!!.penaltyPercent)
        assertEquals(5.0, oldFive.penaltyBb)
        assertEquals(500, Squid.penaltyChips(10_000, 100, oldFive))
        val chosen = SquidRules.fromSaved(true, 6, storedPercent = 2, legacyPresent = true, legacyBb = 5)
        assertEquals(2, chosen!!.penaltyPercent)
        assertEquals(200, Squid.penaltyChips(10_000, 100, chosen))
        val garbage = SquidRules.fromSaved(true, 6, storedPercent = 1, legacyPresent = true, legacyBb = 1)
        assertNull(garbage!!.penaltyPercent)
        assertEquals(1.0, garbage.penaltyBb)
        assertNull(SquidRules.fromSaved(false, 6, 5, true, 5))
    }

    @Test fun urgencyFollowsPenaltyAndNotSafePlayers() {
        val start = 10_000
        assertEquals(0.0, Squid.combinedUrgency(1.0, 0, start), 0.0)
        assertEquals(0.4, Squid.combinedUrgency(1.0, 200, start), 1e-9)
        assertEquals(1.0, Squid.combinedUrgency(1.0, 500, start), 1e-9)
        assertEquals(1.0, Squid.combinedUrgency(1.0, 1000, start), 1e-9)
        assertEquals(0.25, Squid.combinedUrgency(0.25, 500, start), 1e-9)
        assertTrue(Squid.combinedUrgency(1.0, 5000, start) <= 1.0)

        val s = GameSession(SessionConfig(GameMode.CASH, 3, 100, TableType.GTO, seed = 3, squid = SquidRules(2, penaltyPercent = 10)))
        val e = s.startHand()
        val unsafe = s.pressureFor(e, e.toAct)
        assertTrue(unsafe.squidUnsafe)
        assertEquals(0.25, unsafe.squidUrgency, 1e-9)
        // Fold everyone to the big blind so that seat becomes safe, then the next hand has no squid urgency for them.
        while (e.phase != Phase.HAND_DONE) {
            if (e.phase == Phase.STREET_DONE) e.dealNextStreet()
            else {
                val la = e.legalActions()
                e.apply(if (la.canCheck) Move.CHECK else Move.FOLD)
            }
        }
        val winner = Squid.potWinners(e.result!!).first()
        s.completeHand(e)
        val e2 = s.startHand()
        val safe = s.pressureFor(e2, winner)
        assertEquals(false, safe.squidUnsafe)
        assertEquals(0.0, safe.squidUrgency, 0.0)
        val other = s.players.first { it.seat != winner }.seat
        val still = s.pressureFor(e2, other)
        assertTrue(still.squidUnsafe)
        assertTrue(still.squidUrgency > safe.squidUrgency)
        assertTrue(still.squidUrgency > unsafe.squidUrgency)
    }

    @Test fun percentSettlementConservesAndCaps() {
        val stacks = mapOf(0 to 150, 1 to 5000, 2 to 5000)
        val paid = Squid.settleAmounts(stacks, setOf(1, 2), mapOf(0 to 500, 1 to 500, 2 to 500))
        assertEquals(-150, paid!!.delta[0])
        assertEquals(75, paid.delta[1])
        assertEquals(75, paid.delta[2])
        assertEquals(0, paid.delta.values.sum())
        assertEquals(500, paid.penaltyChips)
        assertNull(Squid.settleAmounts(stacks, setOf(1), mapOf(0 to 0, 1 to 0)))
    }
}

class ExportTest {
    @Test fun exportSeparatesUncalledReturnAndOmitsUnrecordedSquid() {
        val e = HandEngine(
            HandSetup(50, 100, 0, 0, listOf(SeatSetup(0, "You", 1000), SeatSetup(1, "Ace", 300), SeatSetup(2, "Blaze", 600))),
            PresetCardSource(
                mapOf(0 to Cards.parseMany("7c2d"), 1 to Cards.parseMany("AsAh"), 2 to Cards.parseMany("KsKh")),
                Cards.parseMany("QdJd9c4s3h"),
            ),
        )
        e.apply(Move.raiseTo(1000))
        e.apply(Move.CALL)
        e.apply(Move.CALL)
        while (e.phase == Phase.STREET_DONE) e.dealNextStreet()
        val record = HandRecord.from(e, GameMode.CASH, 1, 0, 3, mapOf(0 to BotStyle.GTO, 1 to BotStyle.LAG, 2 to BotStyle.NIT))
            .copy(id = 42L, time = 1_700_000_000_000L, favorite = true)
        val oldLine = record.encode()
        assertEquals(oldLine, HandRecord.decode(oldLine)!!.encode())

        val json = Export.history(listOf(record), emptyMap(), "0.8.0", 1_700_000_000_100L, "p1", "Ace", "all")
        val fixture = checkNotNull(javaClass.getResource("/sample-export.json")).readText()
        assertEquals(fixture.trim(), json.trim())
        val root = Export.parse(json) as Json.Obj
        assertEquals("river-runner.export", (root.field("format") as Json.Str).value)
        assertEquals(1L, (root.field("formatVersion") as Json.Num).value)
        val totals = root.field("totals") as Json.Obj
        assertEquals(Json.Null, totals.field("squidNetChipsForHero"))
        assertEquals(false, (totals.field("bbPer100IncludesSquid") as Json.Bool).value)
        val hand = ((root.field("hands") as Json.Arr).items[0]) as Json.Obj
        val squid = hand.field("squid") as Json.Obj
        assertEquals("not-recorded", (squid.field("availability") as Json.Str).value)
        val result = hand.field("result") as Json.Obj
        assertEquals("from-recorded-actions", (result.field("reconstruction") as Json.Str).value)
        val returned = result.field("uncalledReturned") as Json.Obj
        assertEquals(0L, (returned.field("seat") as Json.Num).value)
        assertEquals(false, (returned.field("countsAsWinningAPot") as Json.Bool).value)
        val winners = (result.field("potWinners") as Json.Arr).items.map { (it as Json.Num).value }
        assertEquals(listOf(1L, 2L), winners)
        assertTrue(0L !in winners)
        val players = (hand.field("players") as Json.Arr).items.map { it as Json.Obj }
        val hero = players.first { (it.field("seat") as Json.Num).value == 0L }
        assertEquals("known-to-hero", (hero.field("holeVisibility") as Json.Str).value)
        val other = players.first { (it.field("seat") as Json.Num).value == 1L }
        assertEquals("shown-at-showdown", (other.field("holeVisibility") as Json.Str).value)
        val firstDecision = ((hand.field("actions") as Json.Arr).items.first { (it as Json.Obj).field("type") == Json.str("raise") }) as Json.Obj
        assertEquals("reconstructed", (firstDecision.field("decisionFacts") as Json.Str).value)
        assertTrue((firstDecision.field("toCallBefore") as Json.Num).value >= 0L)

        val note = SquidHandNote(6, 5, null, 500, 10_000, 100, 6, 1, listOf(1), mapOf(0 to -150, 1 to 150))
        assertEquals(note, SquidHandNote.decode(note.encode()))
        val withSquid = Export.history(listOf(record), mapOf(record.id to note), "0.8.0", 1_700_000_000_100L, null, null, "starred")
        val again = Export.parse(withSquid) as Json.Obj
        val tot = again.field("totals") as Json.Obj
        val handNet = (tot.field("heroNetChipsFromHands") as Json.Num).value
        val squidNet = (tot.field("squidNetChipsForHero") as Json.Num).value
        assertEquals(-150L, squidNet)
        assertEquals(handNet + squidNet, (tot.field("heroNetChipsHandsPlusRecordedSquid") as Json.Num).value)
        assertEquals("starred", (again.field("scope") as Json.Str).value)
    }
}
