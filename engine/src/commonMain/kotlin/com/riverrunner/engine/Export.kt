package com.riverrunner.engine

/**
 * A downloadable JSON snapshot for offline analysis. Nothing here is uploaded.
 *
 * Old hand lines (H1) do not contain squid payments, decision-time pot, or a
 * session id. Those fields are marked not-recorded instead of filled with zeros.
 * Hole cards of other players are stored so a hand can be replayed; they were
 * not known to the hero while the hand was being played, except cards that
 * reached showdown. The export says which is which.
 */
object Export {
    const val FORMAT = "river-runner.export"
    const val FORMAT_VERSION = 1
    const val HISTORY_NON_STARRED_LIMIT = 1000

    fun history(
        hands: List<HandRecord>,
        squidNotes: Map<Long, SquidHandNote>,
        appVersion: String,
        exportedAt: Long,
        profileId: String?,
        profileName: String?,
        scope: String,
    ): String = render(build(hands, squidNotes, appVersion, exportedAt, profileId, profileName, scope, session = null))

    fun session(
        hands: List<HandRecord>,
        squidNotes: Map<Long, SquidHandNote>,
        appVersion: String,
        exportedAt: Long,
        profileId: String?,
        profileName: String?,
        config: SessionConfig,
        players: List<SessionPlayer>,
        heroProfitOnResultScreen: Double,
        handsPlayed: Int,
    ): String {
        val session = sessionNode(config, players, heroProfitOnResultScreen, handsPlayed, squidNotes)
        return render(build(hands, squidNotes, appVersion, exportedAt, profileId, profileName, "session", session))
    }

    private fun build(
        hands: List<HandRecord>,
        squidNotes: Map<Long, SquidHandNote>,
        appVersion: String,
        exportedAt: Long,
        profileId: String?,
        profileName: String?,
        scope: String,
        session: Json?,
    ): Json {
        val ordered = hands.sortedWith(compareBy({ it.time }, { it.handNumber }, { it.id }))
        var handNet = 0L
        var squidNet = 0L
        var squidKnown = 0
        var squidMissing = 0
        val handNodes = ArrayList<Json>()
        for (h in ordered) {
            val note = squidNotes[h.id]
            if (note == null) squidMissing++ else {
                squidKnown++
                squidNet += note.settlementDeltas?.get(h.heroSeat) ?: 0
            }
            handNet += h.heroNet
            handNodes += handNode(h, note)
        }
        val fields = ArrayList<Pair<String, Json>>()
        fields += "format" to Json.str(FORMAT)
        fields += "formatVersion" to Json.num(FORMAT_VERSION)
        fields += "exportedAtEpochMs" to Json.num(exportedAt)
        fields += "appVersion" to Json.str(appVersion)
        fields += "scope" to Json.str(scope)
        fields += "profile" to if (profileId == null) Json.Null else Json.obj(
            "id" to Json.str(profileId),
            "name" to Json.str(profileName ?: ""),
        )
        fields += "retention" to Json.obj(
            "nonStarredHandsKept" to Json.num(HISTORY_NON_STARRED_LIMIT),
            "starredHandsKept" to Json.bool(true),
            "note" to Json.str(
                "Unstarred hands beyond the newest $HISTORY_NON_STARRED_LIMIT are dropped and cannot be exported. " +
                    "Starred hands are kept. This file is not uploaded.",
            ),
        )
        fields += "summary" to Json.str(
            "heroNetChipsFromHands is the sum of per-hand results. Each result uses chips won " +
                "(including uncalled chips returned to the bettor) minus chips that seat committed. " +
                "It does not include squid payments. The in-app BB/100 uses the same hand results. " +
                "The end-of-session profit number on the result screen does include squid when a session " +
                "was played with squid on. squidNetChipsForHero sums only settlements that were recorded; " +
                "it is null when every hand in this file predates the squid ledger.",
        )
        fields += "totals" to Json.obj(
            "hands" to Json.num(ordered.size),
            "starredHands" to Json.num(ordered.count { it.favorite }),
            "heroNetChipsFromHands" to Json.num(handNet),
            "squidHandsWithLedger" to Json.num(squidKnown),
            "squidHandsWithoutLedger" to Json.num(squidMissing),
            "squidNetChipsForHero" to if (squidKnown == 0) Json.Null else Json.num(squidNet),
            "heroNetChipsHandsPlusRecordedSquid" to if (squidKnown == 0) Json.Null else Json.num(handNet + squidNet),
            "bbPer100IncludesSquid" to Json.bool(false),
        )
        fields += "session" to (session ?: Json.Null)
        fields += "hands" to Json.arr(handNodes)
        return Json.obj(fields)
    }

    private fun sessionNode(
        config: SessionConfig,
        players: List<SessionPlayer>,
        heroProfit: Double,
        handsPlayed: Int,
        notes: Map<Long, SquidHandNote>,
    ): Json {
        val hpl = config.handsPerLevel.coerceAtLeast(1)
        val levels = if (config.mode != GameMode.TOURNAMENT || handsPlayed <= 0) {
            listOf(GameSession.levelFor(0).let { if (config.mode == GameMode.CASH) GameSession.currentCash(config) else it })
        } else {
            (0 until handsPlayed).map { GameSession.levelFor(it / hpl) }.distinctBy { it.level }
        }
        val squid = config.squid
        return Json.obj(
            "mode" to Json.str(config.mode.name),
            "tableSize" to Json.num(config.tableSize),
            "startingStackBb" to Json.num(config.startingBB),
            "startingStackChips" to Json.num(config.startingBB.toLong() * GameSession.BASE_BB),
            "sessionStartBlindChips" to Json.num(GameSession.BASE_BB),
            "rebuy" to Json.str("none"),
            "tableType" to Json.str(config.tableType.name),
            "styleMix" to (config.styleMix?.let { Json.str(it.encode()) } ?: Json.Null),
            "handsPerBlindLevel" to Json.num(config.handsPerLevel),
            "cashAnteFraction" to Json.dbl(config.cashAnteFraction),
            "seed" to Json.num(config.seed),
            "handsPlayed" to Json.num(handsPlayed),
            "heroProfitOnResultScreen" to Json.dbl(heroProfit),
            "heroProfitOnResultScreenIncludesSquid" to Json.bool(true),
            "blindSchedule" to Json.arr(levels.map {
                Json.obj(
                    "level" to Json.num(it.level),
                    "smallBlind" to Json.num(it.smallBlind),
                    "bigBlind" to Json.num(it.bigBlind),
                    "ante" to Json.num(it.ante),
                )
            }),
            "players" to Json.arr(players.map {
                Json.obj(
                    "seat" to Json.num(it.seat),
                    "name" to Json.str(it.name),
                    "style" to Json.str(it.style.name),
                    "isHuman" to Json.bool(it.isHuman),
                    "sessionStartStack" to Json.num(it.startStack),
                )
            }),
            "squid" to if (squid == null) Json.Null else Json.obj(
                "handsPerRound" to Json.num(squid.handsPerRound),
                "penaltyPercentOfStartingStack" to (squid.penaltyPercent?.let { Json.num(it) } ?: Json.Null),
                "legacyPenaltyStartingBb" to if (squid.penaltyPercent != null) Json.Null else Json.dbl(squid.penaltyBb),
                "basis" to Json.str("session-starting-stack-fixed"),
                "settlementsRecorded" to Json.num(notes.values.count { it.settlementDeltas != null }),
            ),
        )
    }

    private fun handNode(record: HandRecord, note: SquidHandNote?): Json {
        val replay = reconstruct(record)
        val order = seatOrder(record)
        val shown = replay?.result?.showdown?.toSet()
        return Json.obj(
            "id" to Json.num(record.id),
            "timeEpochMs" to Json.num(record.time),
            "orderInSession" to Json.num(record.handNumber),
            "starred" to Json.bool(record.favorite),
            "mode" to Json.str(record.mode.name),
            "tableSize" to Json.num(record.tableSize),
            "buttonSeat" to Json.num(record.buttonSeat),
            "heroSeat" to Json.num(record.heroSeat),
            "blinds" to Json.obj(
                "smallBlind" to Json.num(record.smallBlind),
                "bigBlind" to Json.num(record.bigBlind),
                "ante" to Json.num(record.ante),
            ),
            "players" to Json.arr(record.players.map { p ->
                val idx = order.indexOf(p.seat)
                Json.obj(
                    "seat" to Json.num(p.seat),
                    "name" to Json.str(p.name),
                    "style" to Json.str(p.style.name),
                    "position" to Json.str(if (idx < 0) "unknown" else HandEngine.positionName(idx, order.size)),
                    "stackAtHandStart" to Json.num(p.startStack),
                    "hole" to Json.arr(p.hole.map { Json.str(Cards.toString(it)) }),
                    "holeVisibility" to Json.str(visibility(p.seat, record.heroSeat, shown)),
                )
            }),
            "boardByStreet" to boardNode(record.board),
            "actions" to Json.arr(actionNodes(record, replay)),
            "result" to resultNode(record, replay),
            "heroNetChipsThisHand" to Json.num(record.heroNet),
            "heroNetIncludesUncalledReturn" to Json.bool(true),
            "heroNetIncludesSquid" to Json.bool(false),
            "squid" to squidNode(note, record.heroSeat),
        )
    }

    private fun visibility(seat: Int, hero: Int, showdown: Set<Int>?): String = when {
        seat == hero -> "known-to-hero"
        showdown == null -> "stored-for-replay; showdown-membership-unavailable"
        seat in showdown -> "shown-at-showdown"
        else -> "stored-for-replay-not-known-to-hero-during-hand"
    }

    private fun boardNode(board: IntArray): Json {
        fun take(n: Int): Json = if (board.size < n) Json.Null else Json.arr(board.take(n).map { Json.str(Cards.toString(it)) })
        return Json.obj(
            "flop" to take(3),
            "turn" to if (board.size < 4) Json.Null else Json.str(Cards.toString(board[3])),
            "river" to if (board.size < 5) Json.Null else Json.str(Cards.toString(board[4])),
            "cardsDealt" to Json.num(board.size),
        )
    }

    private fun actionNodes(record: HandRecord, replay: HandEngine?): List<Json> {
        val nodes = ArrayList<Json>()
        var voluntary = 0
        for (a in record.actions) {
            val before = if (!a.type.isPost && replay != null) runCatching { Replayer(record).engineBeforeAction(voluntary) }.getOrNull() else null
            val legal = before?.takeIf { it.phase == Phase.BETTING && it.toAct == a.seat }?.let { runCatching { it.legalActions() }.getOrNull() }
            val me = before?.players?.firstOrNull { it.seat == a.seat }
            nodes += Json.obj(
                "order" to Json.num(nodes.size),
                "street" to Json.str(a.street.name.lowercase()),
                "seat" to Json.num(a.seat),
                "type" to Json.str(a.type.name.lowercase()),
                "isBlindOrAnte" to Json.bool(a.type.isPost),
                "amount" to Json.num(a.amount),
                "streetTotal" to Json.num(a.streetTotal),
                "allIn" to Json.bool(a.allIn),
                "potBefore" to (legal?.let { Json.num(it.pot) } ?: Json.Null),
                "toCallBefore" to (legal?.let { Json.num(it.toCall) } ?: Json.Null),
                "stackBehindBefore" to (me?.let { Json.num(it.stack) } ?: Json.Null),
                "streetBetBefore" to (me?.let { Json.num(it.streetBet) } ?: Json.Null),
                "boardBefore" to Json.arr((before?.board ?: emptyList()).map { Json.str(Cards.toString(it)) }),
                "decisionFacts" to if (a.type.isPost) Json.str("forced-post") else if (legal == null) Json.str("unavailable") else Json.str("reconstructed"),
            )
            if (!a.type.isPost) voluntary++
        }
        return nodes
    }

    private fun resultNode(record: HandRecord, replay: HandEngine?): Json {
        val result = replay?.result
        if (result == null) {
            return Json.obj(
                "reconstruction" to Json.str("unavailable"),
                "wonIncludingUncalledReturn" to Json.obj(record.won.entries.sortedBy { it.key }.map { it.key.toString() to Json.num(it.value) }),
                "uncalledReturned" to Json.Null,
                "pots" to Json.Null,
                "potWinners" to Json.Null,
                "showdownSeats" to Json.Null,
            )
        }
        val back = result.uncalledReturned
        return Json.obj(
            "reconstruction" to Json.str("from-recorded-actions"),
            "wonIncludingUncalledReturn" to Json.obj(result.won.entries.sortedBy { it.key }.map { it.key.toString() to Json.num(it.value) }),
            "uncalledReturned" to if (back == null) Json.Null else Json.obj(
                "seat" to Json.num(back.first),
                "chips" to Json.num(back.second),
                "countsAsWinningAPot" to Json.bool(false),
            ),
            "pots" to Json.arr(result.pots.map {
                Json.obj(
                    "amount" to Json.num(it.amount),
                    "eligible" to Json.arr(it.eligible.map { s -> Json.num(s) }),
                    "winners" to Json.arr(it.winners.map { s -> Json.num(s) }),
                )
            }),
            "potWinners" to Json.arr(Squid.potWinners(result).sorted().map { Json.num(it) }),
            "showdownSeats" to Json.arr(result.showdown.map { Json.num(it) }),
        )
    }

    private fun squidNode(note: SquidHandNote?, hero: Int): Json {
        if (note == null) {
            return Json.obj(
                "availability" to Json.str("not-recorded"),
                "note" to Json.str("This hand was saved before squid settlements were written down, or squid was off and no ledger line was stored."),
            )
        }
        val delta = note.settlementDeltas
        return Json.obj(
            "availability" to Json.str("recorded"),
            "handsPerRound" to Json.num(note.handsPerRound),
            "penaltyPercentOfStartingStack" to (note.penaltyPercent?.let { Json.num(it) } ?: Json.Null),
            "legacyPenaltyStartingBb" to (note.legacyPenaltyBb?.let { Json.dbl(it) } ?: Json.Null),
            "assessedChipsBeforeStackCap" to Json.num(note.penaltyChips),
            "sessionStartStack" to Json.num(note.sessionStartStack),
            "sessionStartBlind" to Json.num(note.sessionStartBlind),
            "handInRound" to Json.num(note.handInRound),
            "handsLeftBeforeThisHand" to Json.num(note.handsLeftBefore),
            "safeSeatsBeforeThisHand" to Json.arr(note.safeBefore.map { Json.num(it) }),
            "settlementDeltas" to if (delta == null) Json.Null else Json.obj(delta.entries.sortedBy { it.key }.map { it.key.toString() to Json.num(it.value) }),
            "heroSquidDelta" to if (delta == null) Json.num(0) else Json.num(delta[hero] ?: 0),
            "roundClosedOnThisHand" to Json.bool(delta != null),
        )
    }

    /** Replays the record. Null when the actions cannot be applied. */
    fun reconstruct(record: HandRecord): HandEngine? = runCatching {
        val setup = HandSetup(
            record.smallBlind, record.bigBlind, record.ante, record.buttonSeat,
            record.players.map { SeatSetup(it.seat, it.name, it.startStack) },
        )
        val board = if (record.board.size >= 5) record.board else record.board + IntArray(5 - record.board.size) { -1 }
        val e = HandEngine(setup, PresetCardSource(record.players.associate { it.seat to it.hole }, board))
        for (a in record.actions) {
            if (a.type.isPost) continue
            while (e.phase == Phase.STREET_DONE) e.dealNextStreet()
            if (e.phase != Phase.BETTING) break
            e.apply(when (a.type) {
                ActionType.FOLD -> Move.FOLD
                ActionType.CHECK -> Move.CHECK
                ActionType.CALL -> Move.CALL
                ActionType.BET, ActionType.RAISE -> Move.raiseTo(a.streetTotal)
                else -> return null
            })
        }
        while (e.phase == Phase.STREET_DONE) e.dealNextStreet()
        e
    }.getOrNull()

    private fun seatOrder(record: HandRecord): List<Int> {
        val seats = record.players.map { it.seat }.sorted()
        val btn = seats.indexOf(record.buttonSeat).coerceAtLeast(0)
        return List(seats.size) { seats[(btn + 1 + it) % seats.size] }
    }

    fun render(json: Json): String = json.render(0)

    /** Minimal parser for the JSON this file writes. Used by tests. */
    fun parse(text: String): Json = Parser(text).parse()

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Json { skip(); val v = value(); skip(); return v }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun value(): Json {
            skip()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Json.str(str())
                't' -> { i += 4; Json.bool(true) }
                'f' -> { i += 5; Json.bool(false) }
                'n' -> { i += 4; Json.Null }
                else -> num()
            }
        }
        private fun obj(): Json {
            i++
            val fields = ArrayList<Pair<String, Json>>()
            skip()
            if (s[i] == '}') { i++; return Json.obj(fields) }
            while (true) {
                skip(); val k = str(); skip(); check(s[i] == ':'); i++
                fields += k to value(); skip()
                when (s[i]) { ',' -> i++; '}' -> { i++; break }; else -> error("json") }
            }
            return Json.obj(fields)
        }
        private fun arr(): Json {
            i++
            val items = ArrayList<Json>()
            skip()
            if (s[i] == ']') { i++; return Json.arr(items) }
            while (true) {
                items += value(); skip()
                when (s[i]) { ',' -> i++; ']' -> { i++; break }; else -> error("json") }
            }
            return Json.arr(items)
        }
        private fun str(): String {
            check(s[i] == '"'); i++
            val out = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    out.append(when (val c = s[i]) {
                        '"', '\\', '/' -> c
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { val h = s.substring(i + 1, i + 5); i += 4; h.toInt(16).toChar() }
                        else -> c
                    })
                } else out.append(s[i])
                i++
            }
            i++
            return out.toString()
        }
        private fun num(): Json {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val t = s.substring(start, i)
            return if ('.' in t) Json.dbl(t.toDouble()) else Json.num(t.toLong())
        }
    }
}

/** A JSON value the exporter and its tests share. */
sealed class Json {
    abstract fun render(indent: Int): String
    data class Obj(val fields: List<Pair<String, Json>>) : Json() {
        override fun render(indent: Int): String = renderObj(fields, indent)
        fun field(name: String): Json = fields.first { it.first == name }.second
        fun opt(name: String): Json? = fields.firstOrNull { it.first == name }?.second
    }
    data class Arr(val items: List<Json>) : Json() {
        override fun render(indent: Int): String {
            if (items.isEmpty()) return "[]"
            val pad = "  ".repeat(indent + 1)
            val end = "  ".repeat(indent)
            return items.joinToString(",\n", "[\n", "\n$end]") { pad + it.render(indent + 1) }
        }
    }
    data class Str(val value: String) : Json() {
        override fun render(indent: Int) = "\"" + escape(value) + "\""
    }
    data class Num(val value: Long) : Json() { override fun render(indent: Int) = value.toString() }
    data class Dbl(val value: Double) : Json() {
        override fun render(indent: Int): String {
            val t = value.toString()
            return if ('.' in t || 'E' in t || 'e' in t) t else "$t.0"
        }
    }
    data class Bool(val value: Boolean) : Json() { override fun render(indent: Int) = if (value) "true" else "false" }
    data object Null : Json() { override fun render(indent: Int) = "null" }

    companion object {
        fun obj(vararg fields: Pair<String, Json>) = Obj(fields.toList())
        fun obj(fields: List<Pair<String, Json>>) = Obj(fields)
        fun arr(items: List<Json>) = Arr(items)
        fun str(v: String) = Str(v)
        fun num(v: Int) = Num(v.toLong())
        fun num(v: Long) = Num(v)
        fun dbl(v: Double) = Dbl(v)
        fun bool(v: Boolean) = Bool(v)
    }
}

private fun renderObj(fields: List<Pair<String, Json>>, indent: Int): String {
    if (fields.isEmpty()) return "{}"
    val pad = "  ".repeat(indent + 1)
    val end = "  ".repeat(indent)
    return fields.joinToString(",\n", "{\n", "\n$end}") { (k, v) ->
        pad + "\"" + escape(k) + "\": " + v.render(indent + 1)
    }
}

private fun escape(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    }
}

/** Cash level used in a session export. Tournament levels use [GameSession.levelFor]. */
fun GameSession.Companion.currentCash(config: SessionConfig): BlindLevel =
    BlindLevel(1, BASE_BB / 2, BASE_BB, (BASE_BB * config.cashAnteFraction).toInt())
