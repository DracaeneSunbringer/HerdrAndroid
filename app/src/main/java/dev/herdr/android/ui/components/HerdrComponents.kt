package dev.herdr.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType

/** Shared corner radii. */
object HerdrShapes {
    val Card = RoundedCornerShape(18.dp)
    val Block = RoundedCornerShape(14.dp)
    val Control = RoundedCornerShape(12.dp)
    val Small = RoundedCornerShape(8.dp)
    val Pill = RoundedCornerShape(999.dp)
}

/** Semantic presentation of an agent state: one color + one short Chinese label. */
data class StatusTone(val color: Color, val tint: Color, val label: String, val pulsing: Boolean)

fun statusToneOf(word: AgentStateWord, acknowledged: Boolean = false): StatusTone = when (word) {
    AgentStateWord.BLOCKED -> StatusTone(HerdrColors.Blocked, HerdrColors.BlockedSubtle, "等你处理", false)
    AgentStateWord.ERROR -> StatusTone(HerdrColors.Blocked, HerdrColors.BlockedSubtle, "出错", false)
    AgentStateWord.DONE -> if (acknowledged) {
        StatusTone(HerdrColors.Idle, HerdrColors.Raised, "已阅", false)
    } else {
        StatusTone(HerdrColors.Done, HerdrColors.DoneSubtle, "已完成", false)
    }
    AgentStateWord.WORKING -> StatusTone(HerdrColors.Working, HerdrColors.WorkingSubtle, "运行中", true)
    AgentStateWord.IDLE -> StatusTone(HerdrColors.Idle, HerdrColors.Raised, "空闲", false)
    AgentStateWord.NONE -> StatusTone(HerdrColors.Idle, HerdrColors.Raised, "未知", false)
}

/** Short, human app label instead of shouting the raw wire name. */
fun agentAppLabel(raw: String?): String = when (raw?.lowercase()?.trim()) {
    null, "" -> "Shell"
    "antigravity", "agy" -> "Antigravity"
    "codex" -> "Codex"
    "claude", "claude-code", "claude_code" -> "Claude"
    "pi" -> "Pi"
    "gemini" -> "Gemini"
    "shell", "bash", "pwsh", "powershell" -> "Shell"
    else -> raw.replaceFirstChar { it.uppercase() }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status-pulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "status-pulse-alpha"
        )
        a
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/** Dot + short label, e.g. "● 运行中". No pill background. */
@Composable
fun StatusLabel(tone: StatusTone, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone.color, 7.dp, tone.pulsing)
        Spacer(Modifier.width(6.dp))
        Text(text = tone.label, style = HerdrType.Caption, color = tone.color, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

enum class HerdrButtonKind { Primary, Tonal, Neutral, Ghost, Danger }

@Composable
fun HerdrButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HerdrButtonKind = HerdrButtonKind.Neutral,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val (bg, fg) = when (kind) {
        HerdrButtonKind.Primary -> HerdrColors.Accent to HerdrColors.OnAccent
        HerdrButtonKind.Tonal -> HerdrColors.AccentSubtle to HerdrColors.Accent
        HerdrButtonKind.Neutral -> HerdrColors.Raised to HerdrColors.Foreground
        HerdrButtonKind.Ghost -> Color.Transparent to HerdrColors.Muted
        HerdrButtonKind.Danger -> HerdrColors.BlockedSubtle to HerdrColors.Blocked
    }
    Row(
        modifier = modifier
            .heightIn(min = if (compact) 34.dp else 40.dp)
            .clip(HerdrShapes.Control)
            .background(if (enabled) bg else HerdrColors.Raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) fg else HerdrColors.Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = HerdrType.Label,
            color = if (enabled) fg else HerdrColors.Dim,
            fontWeight = if (kind == HerdrButtonKind.Primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun HerdrIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HerdrColors.Muted,
    background: Color = Color.Transparent,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Elevated container. No border in dark themes; hairline in light themes where white-on-gray needs a hint. */
@Composable
fun HerdrCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Card)
            .background(HerdrColors.Surface)
            .then(if (HerdrColors.IsLight) Modifier.border(1.dp, HerdrColors.Border, HerdrShapes.Card) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

/** Small uppercase-ish section label with an optional muted count: "需要处理  2". */
@Composable
fun SectionEyebrow(text: String, count: Int? = null, color: Color = HerdrColors.Muted, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = HerdrType.Caption, color = color, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = "$count", style = HerdrType.Caption, color = HerdrColors.Dim, fontWeight = FontWeight.Medium)
        }
    }
}

/** A text tab used for filters: selected = foreground text on raised capsule, others = muted text. */
@Composable
fun HerdrFilterTab(label: String, count: Int?, selected: Boolean, onClick: () -> Unit, dotColor: Color? = null) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(HerdrShapes.Pill)
            .background(if (selected) HerdrColors.Raised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            StatusDot(dotColor, 6.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = HerdrType.Label,
            color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(text = "$count", style = HerdrType.Caption, color = if (selected) HerdrColors.Muted else HerdrColors.Dim, maxLines = 1)
        }
    }
}

/** Two-option segmented control (e.g. 阅读 | 终端). */
@Composable
fun HerdrSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(HerdrShapes.Control)
            .background(HerdrColors.Raised)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) HerdrColors.Surface else Color.Transparent)
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = HerdrType.Caption,
                    color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Grouped list container (iOS inset-grouped style) with hairline dividers between rows. */
@Composable
fun HerdrGroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Card)
            .background(HerdrColors.Surface)
            .then(if (HerdrColors.IsLight) Modifier.border(1.dp, HerdrColors.Border, HerdrShapes.Card) else Modifier),
        content = content
    )
}

@Composable
fun HerdrDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(HerdrColors.Border)
    )
}

/** On/off switch in theme colors. */
@Composable
fun HerdrToggle(checked: Boolean, onClick: () -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = { onClick() },
        colors = SwitchDefaults.colors(
            checkedThumbColor = HerdrColors.OnAccent,
            checkedTrackColor = HerdrColors.Accent,
            checkedBorderColor = HerdrColors.Accent,
            uncheckedThumbColor = HerdrColors.Dim,
            uncheckedTrackColor = HerdrColors.Raised,
            uncheckedBorderColor = HerdrColors.InputBorder
        )
    )
}

/** Single-line text with ellipsis, the default for metadata rows. */
@Composable
fun OneLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, fontWeight: FontWeight? = null) {
    Text(
        text = text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Row that spaces its children with the standard 8dp gap and centers them vertically. */
@Composable
fun HRow(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
