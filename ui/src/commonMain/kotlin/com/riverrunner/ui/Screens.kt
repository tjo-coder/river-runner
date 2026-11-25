package com.riverrunner.ui

import kotlin.math.roundToInt
import com.riverrunner.IoDispatcher
import com.riverrunner.Fmt
import com.riverrunner.PlatformBackHandler
import androidx.compose.foundation.background
import com.riverrunner.engine.Cards
import com.riverrunner.data.Prefs
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riverrunner.engine.GameMode
import com.riverrunner.engine.HandRecord
import com.riverrunner.engine.HeroStats
import com.riverrunner.engine.SessionConfig
import com.riverrunner.engine.SquidRules
import com.riverrunner.engine.Stats
import com.riverrunner.engine.Progress
import com.riverrunner.engine.Achievements
import com.riverrunner.engine.TableType
import com.riverrunner.engine.TablePreset
import com.riverrunner.engine.StyleMix
import com.riverrunner.engine.BotStyle
import com.riverrunner.engine.DeviationTally
import com.riverrunner.engine.Street
import androidx.compose.foundation.layout.size
import com.riverrunner.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================ Home

@Composable
fun HomeScreen(vm: AppViewModel) {
    val st = LocalStrings.current
    val p = vm.prefs
    Row(Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("River Runner", color = Palette.Text, fontSize = 30.sp, lineHeight = 30.sp, fontFamily = Numeric, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(st.tagline, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
            PlayerCard(vm.profile, vm.profileList.size, vm.maxSlots, onSwitch = { vm.screen = Screen.Saves }, modifier = Modifier.fillMaxWidth())
            val tiles = listOf(
                st.guide to { vm.openGuide() },
                st.achievements to { vm.screen = Screen.Achievements },
                st.history to { vm.screen = Screen.History },
                st.stats to { vm.screen = Screen.Stats },
                st.saves to { vm.screen = Screen.Saves },
                st.settings to { vm.screen = Screen.Settings },
            )
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, go) -> NavItem(label, Modifier.weight(1f), onClick = go) }
                }
            }
        }
        // the "table" side of the lobby: the one place with felt
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(Palette.FeltCenter, Palette.FeltEdge), radius = 900f))
                .border(6.dp, Palette.Rail, RoundedCornerShape(20.dp))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.height(96.dp).width(130.dp)) {
                    Box(Modifier.align(Alignment.Center).offset(x = (-24).dp).graphicsLayer { rotationZ = -10f }) {
                        PlayingCard(Cards.parse("As"), 62.dp)
                    }
                    Box(Modifier.align(Alignment.Center).offset(x = 24.dp).graphicsLayer { rotationZ = 10f }) {
                        PlayingCard(Cards.parse("Kh"), 62.dp)
                    }
                }
                ActionButton(st.startGame, Palette.Gold, textColor = Palette.Ink, modifier = Modifier.width(260.dp).height(52.dp)) {
                    vm.screen = Screen.Setup
                }
                val summary = st.setupSummary(
                    p.lastMode == GameMode.TOURNAMENT, p.lastSize, p.lastStackBB,
                    p.lastTableType == TableType.MIXED, p.lastHandsPerLevel, p.lastAnte,
                )
                Column(
                    Modifier.width(260.dp).pressable { vm.startGame(lastConfig(p)) }.clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66000000)).border(1.dp, Palette.Gold.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(st.quickStart + "  ▶", color = Palette.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        summary.take(2).joinToString(if (st.isZh) "，" else ", ") +
                            if (p.squidEnabled) (if (st.isZh) " · 鱿鱼" else " · Squid") else "",
                        color = Palette.Text.copy(alpha = 0.85f),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun lastConfig(p: Prefs) = SessionConfig(
    mode = p.lastMode, tableSize = p.lastSize, startingBB = p.lastStackBB, tableType = p.lastTableType,
    handsPerLevel = p.lastHandsPerLevel, cashAnteFraction = if (p.lastAnte) 0.125 else 0.0,
    styleMix = p.lastStyleMix,
    squid = p.squidRules(p.lastSize),
)

@Composable
private fun ExportDialog(vm: AppViewModel) {
    val notice = vm.exportNotice ?: return
    val st = LocalStrings.current
    AlertDialog(
        onDismissRequest = { vm.exportNotice = null },
        confirmButton = { TextButton({ vm.copyLastExport() }) { Text(st.exportCopy) } },
        dismissButton = { TextButton({ vm.exportNotice = null }) { Text(st.exportClose) } },
        text = { Text(notice + "\n\n" + st.exportLimit) },
    )
}

@Composable
private fun NavItem(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.pressable(onClick = onClick).height(48.dp).clip(RoundedCornerShape(10.dp))
            .background(Palette.Panel).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Palette.Text, fontSize = 14.sp, maxLines = 1) }
}

// ============================================================================ New game setup

@Composable
fun SetupScreen(vm: AppViewModel) {
    PlatformBackHandler { vm.screen = Screen.Home }
    val p = vm.prefs
    SetupContent(
        initial = lastConfig(p),
        onBack = { vm.screen = Screen.Home },
        onStart = { c ->
            p.lastMode = c.mode; p.lastSize = c.tableSize; p.lastStackBB = c.startingBB
            p.lastTableType = c.tableType; p.lastHandsPerLevel = c.handsPerLevel; p.lastAnte = c.cashAnteFraction > 0
            if (c.tableType == TableType.MIXED) p.lastStyleMix = c.styleMix
            p.squidEnabled = c.squid != null
            c.squid?.let {
                p.squidHands = if (it.handsPerRound == c.tableSize) 0 else it.handsPerRound
                val pct = it.penaltyPercent
                if (pct != null) p.squidPercent = pct
            }
            vm.startGame(c)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupContent(initial: SessionConfig, onBack: () -> Unit, onStart: (SessionConfig) -> Unit) {
    val st = LocalStrings.current
    var mode by remember { mutableStateOf(initial.mode) }
    var size by remember { mutableIntStateOf(initial.tableSize) }
    var stackBB by remember { mutableIntStateOf(initial.startingBB) }
    var table by remember { mutableStateOf(initial.tableType) }
    var hpl by remember { mutableIntStateOf(initial.handsPerLevel) }
    var ante by remember { mutableStateOf(initial.cashAnteFraction > 0) }
    var squid by remember { mutableStateOf(initial.squid != null) }
    var squidHands by remember {
        mutableIntStateOf(initial.squid?.let { if (it.handsPerRound == initial.tableSize) 0 else it.handsPerRound } ?: 0)
    }
    val openingSquid = initial.squid
    var squidPct by remember { mutableIntStateOf(openingSquid?.penaltyPercent ?: com.riverrunner.engine.SquidRules.DEFAULT_PERCENT) }
    /** True when this save still uses the older fixed-blind penalty and the player has not picked a percent. */
    var squidLegacy by remember { mutableStateOf(openingSquid != null && openingSquid.penaltyPercent == null) }
    // mixed-table line-up: a preset, or null for the custom counts in `custom`
    val initMix = initial.styleMix
    var preset by remember {
        mutableStateOf<TablePreset?>(if (initMix == null) TablePreset.RANDOM else TablePreset.entries.firstOrNull { it.mix == initMix })
    }
    var custom by remember { mutableStateOf(initMix ?: StyleMix(randomSpecial = 2)) }
    val bots = size - 1
    /** The mix this setup would play with; null = random seats. Not trimmed: the session fits it to the seats. */
    val chosenMix: StyleMix? = if (preset == null) custom else preset?.mix
    val cashStacks = listOf(50, 100, 150, 200)
    val tourneyStacks = listOf(20, 30, 50, 75, 100)
    val stackOptions = if (mode == GameMode.CASH) cashStacks else tourneyStacks
    Row(Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("←", onBack)
                Text(st.newGame, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Panel)
                    .padding(horizontal = 14.dp, vertical = 10.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OptionRow(st.mode) {
                    Pill(st.cash, mode == GameMode.CASH, { mode = GameMode.CASH; if (stackBB !in cashStacks) stackBB = 100 })
                    Pill(st.tournament, mode == GameMode.TOURNAMENT, { mode = GameMode.TOURNAMENT; if (stackBB !in tourneyStacks) stackBB = 50 })
                }
                OptionRow(st.players) {
                    for (n in listOf(2, 5, 6, 7, 8)) Pill("$n", size == n, { size = n }, Modifier.width(52.dp))
                }
                OptionRow(st.stackLabelBB) {
                    for (b in stackOptions) Pill("$b", stackBB == b, { stackBB = b }, Modifier.width(60.dp))
                }
                OptionRow(st.tableType) {
                    Pill(st.gtoTable, table == TableType.GTO, { table = TableType.GTO })
                    Pill(st.mixedTable, table == TableType.MIXED, { table = TableType.MIXED })
                }
                if (table == TableType.MIXED) {
                    OptionRow(st.lineup) {
                        for (p in TablePreset.entries) Pill(st.presetName(p), preset == p, { preset = p })
                        Pill(st.presetName(null), preset == null, { preset = null })
                    }
                    if (preset == null) {
                        val fitted = custom.fit(bots)
                        val full = fitted.total >= bots
                        Column(Modifier.padding(start = 92.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(st.specialCount(fitted.total, bots), color = Palette.TextDim, fontSize = 12.sp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (s in StyleMix.SPECIAL) {
                                    StyleCounter(
                                        st.styleName(s), fitted.count(s), canAdd = !full,
                                        onMinus = { custom = fitted.copy(counts = fitted.counts + (s to (fitted.count(s) - 1).coerceAtLeast(0))) },
                                        onPlus = { custom = fitted.copy(counts = fitted.counts + (s to fitted.count(s) + 1)) },
                                    )
                                }
                                StyleCounter(
                                    st.randomStyle, fitted.randomSpecial, canAdd = !full,
                                    onMinus = { custom = fitted.copy(randomSpecial = (fitted.randomSpecial - 1).coerceAtLeast(0)) },
                                    onPlus = { custom = fitted.copy(randomSpecial = fitted.randomSpecial + 1) },
                                )
                            }
                        }
                    }
                }
                if (mode == GameMode.TOURNAMENT) {
                    OptionRow(st.handsPerLevel) {
                        for (h in listOf(5, 8, 10, 15, 20)) Pill("$h", hpl == h, { hpl = h }, Modifier.width(52.dp))
                    }
                } else {
                    OptionRow(st.ante) {
                        Pill(st.none, !ante, { ante = false })
                        Pill("12.5%", ante, { ante = true })
                    }
                }
                OptionRow(st.squidMode) {
                    Pill(st.squidOff, !squid, { squid = false })
                    Pill(st.squidOn, squid, { if (!squid) squidLegacy = false; squid = true })
                }
                if (squid) {
                    Text(st.squidHint, color = Palette.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
                    OptionRow(st.squidHands) {
                        Pill(st.squidOrbit, squidHands == 0, { squidHands = 0 })
                        for (h in listOf(4, 6, 8, 10)) Pill("$h", squidHands == h, { squidHands = h })
                    }
                    if (squidLegacy) Text(st.squidLegacyNote, color = Palette.Gold, fontSize = 12.sp, lineHeight = 17.sp)
                    OptionRow(st.squidPenalty) {
                        Text(st.squidPercentOf, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        for (pen in com.riverrunner.engine.SquidRules.PERCENT_CHOICES) {
                            Pill("$pen%", !squidLegacy && squidPct == pen, { squidLegacy = false; squidPct = pen })
                        }
                    }
                    val chips = if (squidLegacy) ((initial.squid?.penaltyBb ?: 1.0) * 100).toInt() else stackBB * squidPct
                    val bbText = if (chips % 100 == 0) (chips / 100).toString() else Fmt.fixed(chips / 100.0, 1)
                    Text(st.squidAmount(chips, bbText), color = Palette.Text, fontSize = 12.sp)
                }
            }
        }
        // what you are about to play, in words, next to the start button
        Column(
            Modifier.width(230.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(Palette.FeltCenter, Palette.FeltEdge), radius = 600f))
                .border(5.dp, Palette.Rail, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val allLines = st.setupSummary(mode == GameMode.TOURNAMENT, size, stackBB, table == TableType.MIXED, hpl, ante)
            // for a mixed table the last line ("styles are random") is replaced by the line-up below
            val lines = if (table == TableType.MIXED) allLines.dropLast(1) else allLines
            Text(lines[0], color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            for (l in lines.drop(1)) Text(l, color = Palette.Text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
            if (table == TableType.MIXED) {
                val lineupText = chosenMix?.fit(bots)?.let { f ->
                    st.lineupDesc(StyleMix.SPECIAL.associateWith { f.count(it) }, f.randomSpecial, bots - f.total)
                } ?: st.lineupRandomDesc
                Text("${st.lineup}: $lineupText", color = Palette.Text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (squid) {
                val pct = if (squidLegacy) 0 else squidPct
                val line = if (squidLegacy) st.squidLegacyNote else st.squidSummary(squidHands, pct)
                Text(line, color = Palette.Gold, fontSize = 13.sp, lineHeight = 19.sp)
                val chips = if (squidLegacy) ((initial.squid?.penaltyBb ?: 1.0) * 100).toInt() else stackBB * squidPct
                val bbText = if (chips % 100 == 0) (chips / 100).toString() else Fmt.fixed(chips / 100.0, 1)
                Text(st.squidAmount(chips, bbText), color = Palette.Text, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.weight(1f))
            ActionButton(st.start, Palette.Gold, textColor = Palette.Ink, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                onStart(
                    SessionConfig(
                        mode = mode, tableSize = size, startingBB = stackBB, tableType = table,
                        handsPerLevel = hpl, cashAnteFraction = if (ante) 0.125 else 0.0,
                        styleMix = if (table == TableType.MIXED) chosenMix else null,
                        squid = if (!squid) null else {
                            val hands = if (squidHands == 0) size else squidHands
                            if (squidLegacy) SquidRules(hands, penaltyBb = initial.squid?.penaltyBb ?: 1.0)
                            else SquidRules(hands, penaltyPercent = squidPct)
                        },
                    )
                )
            }
        }
    }
}

/** "− TAG 1 +" counter for one style in a custom line-up. */
@Composable
private fun StyleCounter(label: String, count: Int, canAdd: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (count > 0) Palette.AccentDim else Palette.PanelHi),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CounterButton("−", count > 0, onMinus)
        Text(
            "$label $count", color = Palette.Text, fontSize = 13.sp, maxLines = 1, softWrap = false,
            fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal,
        )
        CounterButton("+", canAdd, onPlus)
    }
}

@Composable
private fun CounterButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).pressable(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Palette.Text else Palette.TextDim.copy(alpha = 0.35f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label, color = Palette.TextDim, fontSize = 13.sp, maxLines = 2,
            modifier = Modifier.width(92.dp).padding(top = 10.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

// ============================================================================ Result

@Composable
fun ResultScreen(
    vm: AppViewModel,
    summary: com.riverrunner.engine.SessionSummary,
    report: SessionReport? = vm.sessionReport,
) {
    val st = LocalStrings.current
    PlatformBackHandler { vm.screen = Screen.Home }
    Row(Modifier.fillMaxSize().background(Palette.Bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(st.result, color = Palette.TextDim, fontSize = 14.sp)
            Text(
                // the place only matters with rising blinds (ICM); fixed blinds just end the session
                when {
                    summary.mode != GameMode.TOURNAMENT -> st.champion
                    summary.heroPlace == 1 -> st.championTitle
                    else -> st.placeN(summary.heroPlace)
                },
                color = Palette.Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            )
            if (summary.endedEarly) Text(st.endedEarly, color = Palette.TextDim, fontSize = 13.sp)
            val profit = summary.heroProfit
            val unit = if (summary.mode == GameMode.CASH) " BB" else if (st.isZh) " 积分" else " pts"
            Text(
                "${st.profit}: " + (if (profit >= 0) "+" else "") + Fmt.fixed(profit, 1) + unit,
                color = if (profit >= 0) Palette.Green else Palette.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Text("${st.handsPlayed}: ${summary.handsPlayed}", color = Palette.Text, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            ActionButton(st.exportSession, Palette.PanelHi, modifier = Modifier.fillMaxWidth()) {
                vm.exportThisSession(summary.heroProfit, summary.handsPlayed)
            }
            ActionButton(st.playAgain, Palette.RaiseBtn, modifier = Modifier.fillMaxWidth()) { vm.playAgain() }
            ExportDialog(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(st.history, Palette.PanelHi, modifier = Modifier.weight(1f)) { vm.screen = Screen.History }
                ActionButton(st.home, Palette.PanelHi, modifier = Modifier.weight(1f)) { vm.screen = Screen.Home }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Palette.Panel).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // the report first: how often you deviated, then the standings with each bot's true style
            if (report != null) {
                item { ReportHeading(st.sessionReport) }
                item { DeviationSummary(report.tally) }
            }
            // only worth revealing when somebody actually deviated from GTO
            val reveal = report?.bots?.takeIf { bots -> bots.any { it.style != BotStyle.GTO } }?.associateBy { it.name }
            if (reveal != null) item { ReportHeading(st.whoTheyWere) }
            items(summary.standings) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (s.isHuman) Palette.AccentDim else Palette.PanelHi)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#${s.place}", color = Palette.Text, fontSize = 20.sp, fontFamily = Numeric, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    Text(if (s.isHuman) st.you else s.name, color = Palette.Text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    val bot = if (s.isHuman) null else reveal?.get(s.name)
                    if (bot != null) BotRevealCells(bot)
                    Text(
                        if (s.stack > 0) Format.amount(s.stack, 100, vm.showInBB && summary.mode == GameMode.CASH) else st.busted,
                        color = Palette.TextDim, fontSize = 14.sp, maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportHeading(text: String) {
    Text(text, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/** Green for few deviations, gold for some, red for many. */
private fun deviationColor(rate: Double): Color = when {
    rate <= 0.2 -> Palette.Green
    rate <= 0.4 -> Palette.Gold
    else -> Palette.Red
}

@Composable
private fun DeviationSummary(t: DeviationTally) {
    val st = LocalStrings.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.PanelHi).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (t.decisions == 0) {
            Text(st.noDecisions, color = Palette.TextDim, fontSize = 13.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.pct(t.rate), color = deviationColor(t.rate), fontSize = 28.sp, fontFamily = Numeric, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(st.decisionsLine(t.decisions, t.deviations), color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        val streets = listOf(Street.PREFLOP to st.preflop, Street.FLOP to st.flop, Street.TURN to st.turn, Street.RIVER to st.river)
        Text(st.byStreet, color = Palette.TextDim, fontSize = 12.sp)
        DeviationBars(streets.mapNotNull { (s, name) -> t.byStreet[s]?.let { name to it } })
        Text(st.devByPosition, color = Palette.TextDim, fontSize = 12.sp)
        DeviationBars(t.byPosition.entries.sortedByDescending { it.value.first }.map { it.key to it.value })
    }
}

/** One bar per row: share of decisions that deviated, with "deviations/decisions". */
@Composable
private fun DeviationBars(rows: List<Pair<String, Pair<Int, Int>>>) {
    for ((label, counts) in rows) {
        val (decisions, off) = counts
        val rate = if (decisions == 0) 0.0 else off.toDouble() / decisions
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.PanelHi2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(rate.toFloat()).background(deviationColor(rate)))
            }
            Text(
                "$off/$decisions", color = Palette.TextDim, fontSize = 12.sp, fontFamily = Numeric,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(48.dp),
            )
        }
    }
}

/** A bot's session stats and true style, inside its standings row. */
@Composable
private fun BotRevealCells(b: BotReveal) {
    val st = LocalStrings.current
    val s = b.stats
    Text(
        if (s.hands == 0) "—"
        else st.hudLine((s.vpip * 100).roundToInt(), (s.pfr * 100).roundToInt(), s.hands) +
            (s.af?.let { " · AF " + Fmt.fixed(it, 1) } ?: ""),
        color = Palette.TextDim, fontSize = 12.sp, fontFamily = Numeric, maxLines = 1,
    )
    Text(
        st.styleName(b.style), color = if (b.style == BotStyle.GTO) Palette.TextDim else Palette.Gold,
        fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(60.dp),
    )
}

// ============================================================================ History

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val st = LocalStrings.current
    PlatformBackHandler { vm.screen = Screen.Home }
    var favOnly by remember { mutableStateOf(false) }
    val records = remember { mutableStateListOf<HandRecord>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val all = withContext(IoDispatcher) { vm.history.all() }
        records.clear(); records.addAll(all.asReversed())
        loaded = true
    }
    val shown = if (favOnly) records.filter { it.favorite } else records

    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("← " + st.back, { vm.screen = Screen.Home })
            Text(st.history, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Pill(st.all, !favOnly, { favOnly = false })
            Pill("★ " + st.favorites, favOnly, { favOnly = true })
            SmallButton(st.exportAll, { vm.exportHistory(false) })
            SmallButton(st.exportStarred, { vm.exportHistory(true) })
        }
        Text(st.keepHandsNote, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        Text(st.exportLimit, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        ExportDialog(vm)
        if (loaded && shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(st.noHandsHint, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.width(360.dp))
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { r ->
                val hero = r.hero
                val net = r.heroNet.toDouble() / r.bigBlind
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Panel)
                        .clickable { vm.openReplay(r) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.width(92.dp)) {
                        Text(Fmt.date(r.time, withTime = true), color = Palette.TextDim, fontSize = 11.sp)
                        Text(
                            (if (r.mode == GameMode.CASH) st.cash else st.tournament) + " #${r.handNumber}",
                            color = Palette.Text, fontSize = 12.sp,
                        )
                        Text(Stats.positionOf(r), color = Palette.TextDim, fontSize = 11.sp)
                    }
                    if (hero != null) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        PlayingCard(hero.hole[0], 26.dp); PlayingCard(hero.hole[1], 26.dp)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in r.board) PlayingCard(c, 22.dp)
                    }
                    Text(
                        (if (net > 0) "+" else "") + Fmt.fixed(net, 1) + " BB",
                        color = if (net > 0) Palette.Green else if (net < 0) Palette.Red else Palette.TextDim,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp),
                    )
                    Text(
                        if (r.favorite) "★" else "☆", color = Palette.Gold, fontSize = 22.sp,
                        modifier = Modifier.clickable {
                            val upd = vm.toggleFavorite(r.id)
                            if (upd != null) {
                                val i = records.indexOfFirst { it.id == r.id }
                                if (i >= 0) records[i] = upd
                            }
                        }.padding(6.dp),
                    )
                }
            }
        }
    }
}

// ============================================================================ Stats

@Composable
fun StatsScreen(vm: AppViewModel) {
    val st = LocalStrings.current
    PlatformBackHandler { vm.screen = Screen.Home }
    var stats by remember { mutableStateOf<HeroStats?>(null) }
    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.Default) { Stats.compute(vm.history.all()) }
    }
    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("← " + st.back, { vm.screen = Screen.Home })
            Text(st.stats, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val s = stats
                SectionTitle(st.heroStatsTitle + (if (s != null) " (${s.hands})" else ""))
                if (s == null) Text(st.computing, color = Palette.TextDim)
                else {
                    StatLine(st.vpip, Format.pct(s.vpip))
                    StatLine(st.pfr, Format.pct(s.pfr))
                    StatLine(st.threeBet, Format.pct(s.threeBet))
                    StatLine(st.wtsd, Format.pct(s.wtsd))
                    StatLine(st.wsd, Format.pct(s.wsd))
                    StatLine(st.bbPer100, Fmt.fixed(s.bbPer100, 1), s.bbPer100)
                    StatLine(st.netBB, Fmt.fixed(s.netBB, 1), s.netBB)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionTitle(st.byPosition)
                val s = stats
                if (s != null) {
                    val order = listOf("UTG", "UTG+1", "UTG+2", "HJ", "CO", "BTN", "SB", "BB")
                    for (pos in order) s.byPosition[pos]?.let { StatLine(pos, Fmt.fixed(it, 1), it) }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val pr = vm.profile
                fun c(k: String) = pr.counter(k)
                val cashBB = c(Progress.CASH_PROFIT_CENTI_BB) / 100.0
                SectionTitle(st.careerTitleFor(pr.name))
                StatLine(st.handsLabel, Fmt.grouped(c(Progress.HANDS)))
                StatLine(st.sessions, "${c(Progress.CASH_SESSIONS)}")
                StatLine(st.cashProfitBB, Fmt.fixed(cashBB, 1), cashBB)
                StatLine(st.tournamentsPlayed, "${c(Progress.TOURNEYS)}")
                StatLine(st.tournamentWins, "${c(Progress.TOURNEY_WINS)}")
                StatLine(st.itm, "${c(Progress.TOURNEY_ITM)}")
                StatLine(st.tourneyPoints, "${c(Progress.TOURNEY_POINTS)}", c(Progress.TOURNEY_POINTS).toDouble())
                StatLine(st.achievements, "${pr.unlocked.size} / ${Achievements.all.size}")
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, signed: Double? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = when { signed == null -> Palette.Text; signed > 0 -> Palette.Green; signed < 0 -> Palette.Red; else -> Palette.Text },
        )
    }
}

// ============================================================================ Settings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val st = LocalStrings.current
    PlatformBackHandler { vm.screen = Screen.Home }
    var practice by remember { mutableStateOf(vm.prefs.practiceDefault) }
    var speed by remember { mutableIntStateOf(vm.prefs.speed) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("← " + st.back, { vm.screen = Screen.Home })
            Text(st.settings, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OptionRow(st.language) {
                    Pill("中文", st.isZh, { vm.setChinese(true) })
                    Pill("English", !st.isZh, { vm.setChinese(false) })
                }
                OptionRow(st.deckStyle) {
                    Pill(st.deckFour, vm.deckStyle == DeckStyle.FOUR_COLOR, { vm.setDeck(DeckStyle.FOUR_COLOR) })
                    Pill(st.deckFilled, vm.deckStyle == DeckStyle.FOUR_COLOR_FILLED, { vm.setDeck(DeckStyle.FOUR_COLOR_FILLED) })
                    Pill(st.deckClassic, vm.deckStyle == DeckStyle.CLASSIC, { vm.setDeck(DeckStyle.CLASSIC) })
                }
                Row(Modifier.padding(start = 92.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in listOf("As", "Kh", "Qd", "Jc")) PlayingCard(Cards.parse(c), 40.dp)
                }
                OptionRow(st.speed) {
                    Pill(st.slow, speed == 0, { speed = 0; vm.prefs.speed = 0 })
                    Pill(st.normal, speed == 1, { speed = 1; vm.prefs.speed = 1 })
                    Pill(st.fast, speed == 2, { speed = 2; vm.prefs.speed = 2 })
                }
                ToggleRow(st.practiceDefault, practice) { practice = it; vm.prefs.practiceDefault = it }
                ToggleRow(st.showInBB, vm.showInBB) { vm.setShowBB(it) }
                ToggleRow(st.sound, vm.soundOn) { vm.setSound(it) }
                var squid by remember { mutableStateOf(vm.prefs.squidEnabled) }
                ToggleRow(st.squidMode, squid) { squid = it; vm.prefs.squidEnabled = it }
                Text(st.squidHint, color = Palette.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
                ActionButton(st.clearHistory, Palette.FoldBtn, modifier = Modifier.fillMaxWidth()) { confirmClear = true }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(14.dp),
            ) {
                SectionTitle(st.about)
                Text(st.aboutText, color = Palette.Text, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(8.dp))
                Text("github.com/shunzhe1121/river-runner", color = Palette.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                val version = vm.platform.appVersion
                Text(st.currentVersion(version), color = Palette.TextDim, fontSize = 12.sp, fontFamily = Numeric)
                Spacer(Modifier.height(6.dp))
                SmallButton(st.whatsNew, { vm.showChangelog() })
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton({ confirmClear = false; vm.history.clearNonFavorites() }) { Text(st.clearAction, color = Palette.Red) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(st.keep) } },
            text = { Text(st.clearHistoryConfirm) },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.AccentDim, checkedThumbColor = Palette.Accent, uncheckedTrackColor = Palette.PanelHi2),
        )
    }
}

@Suppress("unused")
private val unusedColor = Color.Unspecified
