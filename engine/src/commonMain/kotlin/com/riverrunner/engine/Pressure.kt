package com.riverrunner.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Tunable stack-pressure and squid-pressure knobs.
 *
 * These sit on top of [BotStyle]. The style numbers themselves do not change;
 * [Pressure.tune] multiplies them for one decision. [PressureContext.OFF] is the
 * old behaviour (used for the GTO hint, so practice mode still shows the
 * unpressured mix).
 *
 * Short stacks (≤ [SHORT_BB]) push/fold and jam wider. Very short stacks
 * (≤ [VERY_SHORT_BB]) jam wider still, including reshoves. Deep stacks
 * (≥ [DEEP_BB]) call and bluff more. A stack that has shrunk versus the
 * session start plays wider instead of folding its way to zero. An unsafe
 * squid player does the same, more so as the round runs out.
 */
object PressureConfig {
    /** Effective stack at or under this uses push/fold and a wider jam. */
    const val SHORT_BB = 15.0

    /** At or under this, unopened pots and single raises are wide shoves. */
    const val VERY_SHORT_BB = 8.0

    /** At or above this, call and bluff frequencies rise. */
    const val DEEP_BB = 70.0

    /**
     * Extra multiplier on [Bot.pushWidth] at [SHORT_BB]. The built-in chart
     * tightens above 10bb, so this puts 15bb back above the 10bb chart.
     */
    const val SHORT_PUSH_MULT = 1.70

    /** Extra multiplier on [Bot.pushWidth] at [VERY_SHORT_BB] and below. */
    const val VERY_SHORT_PUSH_MULT = 1.90

    /** Deep-stack looseness at full effect (150bb+). 100bb gets most of it. */
    const val DEEP_LOOSENESS = 1.40
    const val DEEP_BLUFF = 1.55
    const val DEEP_CALL_BIAS = 0.06
    const val DEEP_PREFLOP_CALL = 1.70
    /** Fold weight kept at full deep-stack effect (1 = no change). */
    const val DEEP_FOLD_DAMP = 0.75

    /** Shrinking starts to matter below this fraction of the session start stack. */
    const val SHRINK_FROM = 0.92
    /** Full shrinking effect at this fraction (and below). */
    const val SHRINK_FLOOR = 0.40
    const val SHRINK_LOOSENESS = 0.50
    const val SHRINK_AGGRESSION = 0.40
    const val SHRINK_BLUFF = 0.30
    const val SHRINK_CALL_BIAS = 0.05
    const val SHRINK_PREFLOP_CALL = 0.30
    const val SHRINK_FOLD_DAMP = 0.58

    /** Below this fraction of the table average, play with tournament-like urgency. */
    const val TABLE_SHORT_RATIO = 0.72
    const val TABLE_SHORT_LOOSENESS = 1.12
    const val TABLE_SHORT_AGGRESSION = 1.15
    /** Push/fold line extends by up to this many blinds when short versus the table. */
    const val TABLE_SHORT_PUSH_EXTRA_BB = 3.0

    /** Above this fraction of the table average, the big stack applies a little more pressure. */
    const val TABLE_BIG_RATIO = 1.55
    const val TABLE_BIG_BLUFF = 1.20
    const val TABLE_BIG_CALL_BIAS = 0.02
    const val TABLE_BIG_PREFLOP_CALL = 1.12

    /** Added on top of the style, scaled by squid urgency (0.25 early … 1 on the last hand). */
    const val SQUID_LOOSENESS = 0.40
    const val SQUID_AGGRESSION = 0.55
    const val SQUID_BLUFF = 0.50
    const val SQUID_CALL_BIAS = 0.04
    const val SQUID_PREFLOP_CALL = 0.35
    const val SQUID_FOLD_DAMP = 0.55
    const val SQUID_PUSH_MULT = 0.45

    /** Fold weight is never multiplied below this, so trash can still fold. */
    const val FOLD_DAMP_FLOOR = 0.42
}

/**
 * What one decision knows about the session, beyond the current hand.
 * [sessionStart] of 0 means "no shrinking adjustment" (the hand stack is used as-is).
 */
data class PressureContext(
    val enabled: Boolean = false,
    /** Chips this player started the session with. */
    val sessionStart: Int = 0,
    /** Unsafe in the current squid round. Ignored when squid is off. */
    val squidUnsafe: Boolean = false,
    /** 0.25 on the first hand of a round, 1 on the last. */
    val squidUrgency: Double = 0.0,
) {
    companion object {
        val OFF = PressureContext(enabled = false)

        fun on(sessionStart: Int = 0, squidUnsafe: Boolean = false, squidUrgency: Double = 0.0) =
            PressureContext(true, sessionStart, squidUnsafe, squidUrgency)
    }
}

/**
 * Style numbers after pressure. [pushFoldBb] is the effective-stack threshold
 * at or under which an unopened pot is a shove; 0 leaves the bot's own line.
 * [pushMult] scales that shove's width. [foldDamp] multiplies fold weight
 * after the mix is built (1 = unchanged) and gives the rest to a continue.
 */
data class PressureTune(
    val looseness: Double,
    val aggression: Double,
    val bluff: Double,
    val callBias: Double,
    val preflopCall: Double,
    val pushMult: Double,
    val pushFoldBb: Double,
    val foldDamp: Double,
) {
    companion object {
        fun identity(style: BotStyle) = PressureTune(
            style.looseness, style.aggression, style.bluff, style.callBias, style.preflopCall,
            pushMult = 1.0, pushFoldBb = 0.0, foldDamp = 1.0,
        )
    }
}

object Pressure {

    /** 0 when the stack is healthy, 1 when it has shrunk to [PressureConfig.SHRINK_FLOOR]. */
    fun shrink01(stackNow: Int, sessionStart: Int): Double {
        if (sessionStart <= 0) return 0.0
        val ratio = stackNow.toDouble() / sessionStart
        if (ratio >= PressureConfig.SHRINK_FROM) return 0.0
        val span = PressureConfig.SHRINK_FROM - PressureConfig.SHRINK_FLOOR
        return ((PressureConfig.SHRINK_FROM - ratio) / span).coerceIn(0.0, 1.0)
    }

    fun tune(style: BotStyle, engine: HandEngine, seat: Int, ctx: PressureContext): PressureTune {
        if (!ctx.enabled) return PressureTune.identity(style)
        val me = engine.player(seat)
        val bb = engine.bigBlind.toDouble().coerceAtLeast(1.0)
        val othersMax = engine.players.filter { it.seat != seat && !it.folded }
            .maxOfOrNull { it.stack + it.streetBet } ?: 0
        val effBb = min(me.stack + me.streetBet, othersMax).toDouble() / bb
        val avg = engine.players.map { it.startStack }.average().coerceAtLeast(1.0)
        val vsTable = me.startStack / avg
        val shrink = shrink01(me.startStack, ctx.sessionStart)

        var looseness = style.looseness
        var aggression = style.aggression
        var bluff = style.bluff
        var callBias = style.callBias
        var preflopCall = style.preflopCall
        var pushMult = 1.0
        var pushFoldBb = 0.0
        var foldDamp = 1.0

        if (effBb <= PressureConfig.SHORT_BB) {
            val span = (PressureConfig.SHORT_BB - PressureConfig.VERY_SHORT_BB).coerceAtLeast(1.0)
            val t = ((PressureConfig.SHORT_BB - effBb) / span).coerceIn(0.0, 1.0)
            pushFoldBb = PressureConfig.SHORT_BB
            pushMult = PressureConfig.SHORT_PUSH_MULT +
                (PressureConfig.VERY_SHORT_PUSH_MULT - PressureConfig.SHORT_PUSH_MULT) * t
            val very = effBb <= PressureConfig.VERY_SHORT_BB
            looseness *= if (very) 1.22 else 1.10
            aggression *= if (very) 1.25 else 1.12
            callBias += if (very) 0.03 else 0.015
            preflopCall *= 1.08
            foldDamp *= if (very) 0.72 else 0.86
        } else if (effBb >= PressureConfig.DEEP_BB) {
            val t = ((effBb - PressureConfig.DEEP_BB) / 80.0).coerceIn(0.0, 1.0)
            val f = 0.65 + 0.35 * t
            looseness *= 1.0 + (PressureConfig.DEEP_LOOSENESS - 1.0) * f
            bluff *= 1.0 + (PressureConfig.DEEP_BLUFF - 1.0) * f
            callBias += PressureConfig.DEEP_CALL_BIAS * f
            preflopCall *= 1.0 + (PressureConfig.DEEP_PREFLOP_CALL - 1.0) * f
            aggression *= 1.0 + 0.08 * f
            foldDamp *= 1.0 - (1.0 - PressureConfig.DEEP_FOLD_DAMP) * f
        }

        if (shrink > 0.0) {
            looseness *= 1.0 + PressureConfig.SHRINK_LOOSENESS * shrink
            aggression *= 1.0 + PressureConfig.SHRINK_AGGRESSION * shrink
            bluff *= 1.0 + PressureConfig.SHRINK_BLUFF * shrink
            callBias += PressureConfig.SHRINK_CALL_BIAS * shrink
            preflopCall *= 1.0 + PressureConfig.SHRINK_PREFLOP_CALL * shrink
            foldDamp *= 1.0 - (1.0 - PressureConfig.SHRINK_FOLD_DAMP) * shrink
            // Lost chips push the shove line a few blinds earlier, even above 15bb.
            pushFoldBb = max(pushFoldBb, PressureConfig.SHORT_BB + 4.0 * shrink)
            if (effBb > PressureConfig.SHORT_BB) pushMult = max(pushMult, 1.0 + 0.25 * shrink)
        }

        if (vsTable < PressureConfig.TABLE_SHORT_RATIO) {
            val t = ((PressureConfig.TABLE_SHORT_RATIO - vsTable) / PressureConfig.TABLE_SHORT_RATIO).coerceIn(0.0, 1.0)
            looseness *= 1.0 + (PressureConfig.TABLE_SHORT_LOOSENESS - 1.0) * t
            aggression *= 1.0 + (PressureConfig.TABLE_SHORT_AGGRESSION - 1.0) * t
            foldDamp *= 1.0 - 0.10 * t
            pushFoldBb = max(pushFoldBb, PressureConfig.SHORT_BB + PressureConfig.TABLE_SHORT_PUSH_EXTRA_BB * t)
            pushMult = max(pushMult, 1.0 + 0.30 * t)
        } else if (vsTable > PressureConfig.TABLE_BIG_RATIO) {
            val t = ((vsTable - PressureConfig.TABLE_BIG_RATIO) / 1.5).coerceIn(0.0, 1.0)
            bluff *= 1.0 + (PressureConfig.TABLE_BIG_BLUFF - 1.0) * t
            callBias += PressureConfig.TABLE_BIG_CALL_BIAS * t
            preflopCall *= 1.0 + (PressureConfig.TABLE_BIG_PREFLOP_CALL - 1.0) * t
            foldDamp *= 1.0 - 0.06 * t
        }

        if (ctx.squidUnsafe) {
            val u = ctx.squidUrgency.coerceIn(0.0, 1.0)
            looseness *= 1.0 + PressureConfig.SQUID_LOOSENESS * u
            aggression *= 1.0 + PressureConfig.SQUID_AGGRESSION * u
            bluff *= 1.0 + PressureConfig.SQUID_BLUFF * u
            callBias += PressureConfig.SQUID_CALL_BIAS * u
            preflopCall *= 1.0 + PressureConfig.SQUID_PREFLOP_CALL * u
            foldDamp *= 1.0 - (1.0 - PressureConfig.SQUID_FOLD_DAMP) * u
            pushMult *= 1.0 + PressureConfig.SQUID_PUSH_MULT * u
            if (u >= 0.65) pushFoldBb = max(pushFoldBb, min(effBb, 25.0))
        }

        return PressureTune(
            looseness, aggression, bluff, callBias, preflopCall, pushMult, pushFoldBb,
            foldDamp.coerceIn(PressureConfig.FOLD_DAMP_FLOOR, 1.0),
        )
    }

    /**
     * Moves fold weight onto a continue. A pure shove-or-fold mix is left alone:
     * its width is already the chart. Tiny continues that [normalized] would drop
     * are not revived, so 72o stays a fold.
     */
    fun dampFolds(options: List<Pair<Move, Double>>, damp: Double): List<Pair<Move, Double>> {
        if (damp >= 0.999) return options
        val merged = LinkedHashMap<Move, Double>()
        for ((m, p) in options) if (p > 0) merged[m] = (merged[m] ?: 0.0) + p
        val fold = merged[Move.FOLD] ?: return options
        if (fold <= 0.0) return options
        val raises = merged.filter { it.key.type == MoveType.RAISE }
        val call = merged[Move.CALL] ?: 0.0
        val pushFold = call <= 0.0 && raises.size == 1 &&
            merged.keys.all { it.type == MoveType.FOLD || it.type == MoveType.RAISE }
        if (pushFold) return options
        val keep = fold * damp
        val freed = fold - keep
        when {
            call > 0.0 -> {
                merged[Move.FOLD] = keep
                merged[Move.CALL] = call + freed
            }
            raises.isNotEmpty() -> {
                merged[Move.FOLD] = keep
                val sum = raises.values.sum().coerceAtLeast(1e-9)
                for ((m, p) in raises) merged[m] = p + freed * (p / sum)
            }
        }
        return merged.map { it.key to it.value }
    }
}
