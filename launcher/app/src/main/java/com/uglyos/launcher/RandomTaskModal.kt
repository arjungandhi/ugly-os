package com.uglyos.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uglyos.common.theme.ThemeColors
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Flicker ticks in one pull before the wheel lands. */
private const val FLICKER_STEPS = 14

/** Chance a flicker tick teases a prize instead of a task. */
private const val TEASE_ODDS = 0.25f

/** Odds of the work-avoidance prizes, per pull. Better rarity means less
 * work: a 15-minute breather is merely rare; the big outs stay genuinely so. */
private const val LEGENDARY_ODDS = 0.001f
private const val EPIC_ODDS = 0.01f
private const val RARE_ODDS = 0.05f

/** How lucky a prize pull is; each tier is a bigger escape from the list. */
private enum class PrizeTier { RARE, EPIC, LEGENDARY }

/** What a pull can land on: a task to do, or a prize instead of one. */
private sealed interface Pull {
    data class Work(val item: IndexedTask) : Pull
    data class Prize(val tier: PrizeTier, val text: String) : Pull
}

/** Rare: a 15-minute breather. */
private val RARE_PRIZES = listOf(
    "go stare out a window",
    "coffee",
    "lie on the floor",
    "educate the rat",
)

/** Epic: an hour-scale escape. */
private val EPIC_PRIZES = listOf(
    "nap time",
    "do some scope creep",
    "book book book",
)

/** Legendary: the day is over. */
private val LEGENDARY_PRIZES = listOf(
    "no more today",
    "the list will survive without you",
    "tasks are cancelled. all of them",
    "give up",
)

private fun PrizeTier.prize(): Pull.Prize = Pull.Prize(
    tier = this,
    text = when (this) {
        PrizeTier.RARE -> RARE_PRIZES
        PrizeTier.EPIC -> EPIC_PRIZES
        PrizeTier.LEGENDARY -> LEGENDARY_PRIZES
    }.random(),
)

/** One confetti dot: a direction, how far it flies (as a fraction of the
 * screen's short side), its size in dp, and which tint slot it draws from. */
private class Spark(val angle: Float, val reach: Float, val size: Float, val tint: Int)

/** The stamp a pull lands with. Rarity grades luck, not the task: a plain task
 * is common; the prizes go up from there, with bursts to match. */
private data class Rarity(val label: String, val color: Color, val sparkCount: Int)

private fun rarityOf(pull: Pull, colors: ThemeColors): Rarity = when (pull) {
    is Pull.Work -> Rarity("common", colors.mutedForeground, 26)
    is Pull.Prize -> when (pull.tier) {
        PrizeTier.RARE -> Rarity("rare", colors.accentMuted, 34)
        PrizeTier.EPIC -> Rarity("epic", colors.warning, 44)
        PrizeTier.LEGENDARY -> Rarity("legendary", colors.error, 70)
    }
}

/**
 * Land a pull: a 0.1% legendary, 1% epic, or 5% rare prize, otherwise a task
 * from [pool] — avoiding [exclude] (the last one shown) when there's a choice.
 */
private fun rollPull(pool: List<IndexedTask>, exclude: IndexedTask?): Pull {
    val r = Random.nextFloat()
    return when {
        r < LEGENDARY_ODDS -> PrizeTier.LEGENDARY.prize()
        r < LEGENDARY_ODDS + EPIC_ODDS -> PrizeTier.EPIC.prize()
        r < LEGENDARY_ODDS + EPIC_ODDS + RARE_ODDS -> PrizeTier.RARE.prize()
        else -> {
            val choices = if (exclude != null && pool.size > 1) pool.filter { it.index != exclude.index } else pool
            Pull.Work(choices.random())
        }
    }
}

/**
 * The hidden slot machine behind the "todo" title: a full-screen pull that
 * flickers through the mode's open [pool] with haptic ticks winding down like a
 * prize wheel, then slams the result home — spring overshoot, a slow ray burst,
 * dot confetti, and a rarity stamp. Rarity is a work-avoidance lottery, not the
 * task: a task is common; prizes are a rare 15-minute breather, an epic hour
 * off, or the legendary end of the day — each with a bigger burst. Deliberately
 * the loudest moment in the app — it's a reward, not chrome — but built from
 * the house parts: monospace, lowercase, aurora colors, and dots. Tap a task
 * card's dot
 * to complete it, "reroll" to pull again (rerolling away a jackpot is on you),
 * or dismiss (tap outside, back) and go do it.
 *
 * Rendered as an in-activity overlay rather than a Dialog: the launcher window
 * is edge-to-edge, while a dialog window is framed below the status bar and
 * can't be centered on the real screen.
 */
@Composable
internal fun RandomTaskModal(
    pool: List<IndexedTask>,
    hiddenContext: String?,
    hiddenProject: String?,
    onDismiss: () -> Unit,
    onComplete: (IndexedTask) -> Unit,
) {
    val colors = UglyTheme.colors
    val haptics = LocalHapticFeedback.current

    var roll by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Pull>(Pull.Work(pool.random())) }
    var jitter by remember { mutableStateOf(0f) }
    var sparks by remember { mutableStateOf(emptyList<Spark>()) }
    val burst = remember { Animatable(0f) }
    // The card's measured center (root coords): rays and confetti erupt from
    // the prize, not from wherever the layout happens to put its middle.
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    var overlayTopLeft by remember { mutableStateOf(Offset.Zero) }

    // One pull: flicker with ticks that slow like a wheel losing steam, then
    // land. Prize flashes mid-flicker are pure teases; rollPull decides.
    LaunchedEffect(roll) {
        revealed = false
        burst.snapTo(0f)
        val previous = (shown as? Pull.Work)?.item
        repeat(FLICKER_STEPS) { i ->
            shown = if (Random.nextFloat() < TEASE_ODDS) PrizeTier.entries.random().prize()
            else Pull.Work(pool.random())
            jitter = (Random.nextFloat() - 0.5f) * 12f
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val t = i / (FLICKER_STEPS - 1f)
            delay((45 + 130 * t * t).toLong())
        }
        val landed = rollPull(pool, exclude = previous)
        shown = landed
        jitter = 0f
        revealed = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val tier = rarityOf(landed, colors)
        sparks = List(tier.sparkCount) {
            Spark(
                angle = Random.nextFloat() * (2f * PI.toFloat()),
                reach = 0.18f + Random.nextFloat() * 0.32f,
                size = 2f + Random.nextFloat() * 3.5f,
                tint = it % 3,
            )
        }
        burst.animateTo(1f, tween(durationMillis = 900, easing = LinearOutSlowInEasing))
    }

    val rarity = rarityOf(shown, colors)
    val revealAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(300),
        label = "reveal",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.92f,
        animationSpec = if (revealed) spring(dampingRatio = 0.4f, stiffness = 500f) else snap(),
        label = "slam",
    )
    val borderColor by animateColorAsState(
        targetValue = if (revealed) rarity.color else colors.subtle,
        animationSpec = tween(300),
        label = "border",
    )
    val rays = rememberInfiniteTransition(label = "rays")
    val rayAngle by rays.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)),
        label = "rayAngle",
    )

    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayTopLeft = it.boundsInRoot().topLeft }
            .background(colors.background.copy(alpha = 0.94f))
            // Eat drags so the pager beneath can't swipe pages under the pull.
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> change.consume() }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (revealed) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = if (origin.isSpecified) origin - overlayTopLeft else center
            drawRays(c, rayAngle, rarity.color, revealAlpha)
            drawSparks(c, sparks, burst.value, rarity.color, colors.foreground)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = (if (revealed) rarity.label else "rolling").uppercase(),
                color = if (revealed) rarity.color else colors.mutedForeground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                fontFamily = FontFamily.Monospace,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { origin = it.boundsInRoot().center }
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        translationX = jitter
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surface)
                    .border(1.dp, borderColor, RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                when (val p = shown) {
                    is Pull.Work -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CompletionDot(completed = false) { if (revealed) onComplete(p.item) }
                        Text(
                            text = taskAnnotated(p.item.task, hiddenContext, hiddenProject),
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        DueBadge(p.item.task)
                    }
                    // Same geometry as a task row (empty dot gutter, left
                    // text) so the flicker doesn't jump between layouts.
                    is Pull.Prize -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.size(DOT_GUTTER))
                        Text(
                            text = p.text,
                            color = rarity.color,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = revealAlpha },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "reroll",
                    color = colors.mutedForeground,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = revealed) { roll++ }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
                Text(
                    text = when (val p = shown) {
                        is Pull.Work -> "let's go"
                        is Pull.Prize -> when (p.tier) {
                            PrizeTier.RARE -> "brb"
                            PrizeTier.EPIC -> "see ya"
                            PrizeTier.LEGENDARY -> "freedom"
                        }
                    },
                    color = colors.accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = revealed, onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Slow sunburst wedges around [origin], faded in by [alpha] on reveal. */
private fun DrawScope.drawRays(origin: Offset, angle: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val rayCount = 12
    val reach = size.maxDimension
    val half = (PI / 42).toFloat()
    for (i in 0 until rayCount) {
        val a = Math.toRadians(angle + i * 360.0 / rayCount).toFloat()
        val path = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(origin.x + reach * cos(a - half), origin.y + reach * sin(a - half))
            lineTo(origin.x + reach * cos(a + half), origin.y + reach * sin(a + half))
            close()
        }
        drawPath(path, color, alpha = 0.06f * alpha)
    }
}

/** Dot confetti — the house dot, thrown outward from [origin] and fading out. */
private fun DrawScope.drawSparks(origin: Offset, sparks: List<Spark>, progress: Float, rarity: Color, dot: Color) {
    if (progress <= 0f || progress >= 1f) return
    sparks.forEach { s ->
        val r = s.reach * size.minDimension * progress
        val pos = origin + Offset(cos(s.angle) * r, sin(s.angle) * r)
        drawCircle(
            color = if (s.tint == 0) dot else rarity,
            radius = s.size.dp.toPx() * (1f - 0.4f * progress),
            center = pos,
            alpha = 1f - progress,
        )
    }
}
