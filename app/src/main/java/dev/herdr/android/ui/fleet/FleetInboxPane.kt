package dev.herdr.android.ui.fleet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.connection.ConnectionState
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.ui.AgentTreeNode
import dev.herdr.android.ui.DispatchedReceipt
import dev.herdr.android.ui.components.HRow
import dev.herdr.android.ui.components.HerdrButton
import dev.herdr.android.ui.components.HerdrButtonKind
import dev.herdr.android.ui.components.HerdrCard
import dev.herdr.android.ui.components.HerdrDivider
import dev.herdr.android.ui.components.HerdrFilterTab
import dev.herdr.android.ui.components.HerdrGroupedList
import dev.herdr.android.ui.components.HerdrIconButton
import dev.herdr.android.ui.components.HerdrShapes
import dev.herdr.android.ui.components.OneLine
import dev.herdr.android.ui.components.SectionEyebrow
import dev.herdr.android.ui.components.StatusDot
import dev.herdr.android.ui.components.StatusLabel
import dev.herdr.android.ui.components.agentAppLabel
import dev.herdr.android.ui.components.statusToneOf
import dev.herdr.android.ui.terminal.StructuredTerminalReader
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType

/** Primary title for an agent: the workspace it works on, falling back to its name / address. */
internal fun agentTitleOf(item: TerminalSummary): String {
    val ws = item.workspaceSubtitle.takeIf { it.isNotBlank() && it != "~" }
    return ws ?: item.name?.takeIf { it.isNotBlank() } ?: item.address
}

/** Compact metadata: "t19 · worker · Antigravity · 328 轮". */
internal fun agentMetaOf(item: TerminalSummary, includeName: Boolean = true): String = buildList {
    add(item.address.substringAfterLast('/').ifBlank { item.address })
    val ws = item.workspaceSubtitle.takeIf { it.isNotBlank() && it != "~" }
    if (includeName && ws != null) item.name?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(agentAppLabel(item.agentApp))
    if (item.turn > 0) add("${item.turn} 轮")
}.joinToString(" · ")

@Composable
fun FleetInboxPane(
    nodes: List<AgentTreeNode>,
    allTerminals: List<TerminalSummary>,
    totalLiveCount: Int,
    connectionState: ConnectionState,
    hostLabel: String,
    activeTerminalAddress: String?,
    currentFilter: AgentStateWord?,
    acknowledgedDoneTurns: Map<String, Int>,
    dispatchedReceipts: Map<String, DispatchedReceipt>,
    onSelectFilter: (AgentStateWord?) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit,
    onCardClick: (TerminalSummary) -> Unit,
    onWriteNextInstruction: (TerminalSummary) -> Unit,
    onQuickDispatch: (TerminalSummary, String, Boolean) -> Unit,
    onAcknowledgeDone: (TerminalSummary) -> Unit,
    onConnectNow: () -> Unit,
    onEnterDemoMode: () -> Unit
) {
    val liveTerminals = remember(allTerminals) { allTerminals.filter { it.state == "live" } }
    val childrenByParent = remember(liveTerminals) {
        val byAddress = liveTerminals.associateBy { it.address }
        val byShortId = liveTerminals.associateBy { it.address.substringAfterLast('/') }
        val result = mutableMapOf<String, MutableList<TerminalSummary>>()
        for (term in liveTerminals) {
            val rawParent = term.parent?.trim().orEmpty()
            if (rawParent.isEmpty()) continue
            val parent = byAddress[rawParent] ?: byShortId[rawParent.substringAfterLast('/')]
            if (parent != null && parent.address != term.address) {
                result.getOrPut(parent.address) { mutableListOf() }.add(term)
            }
        }
        result
    }

    fun isUrgent(t: TerminalSummary) = t.agentStatus == AgentStateWord.BLOCKED || t.agentStatus == AgentStateWord.ERROR
    fun isReadyDone(t: TerminalSummary) = t.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[t.address] != t.turn
    fun isQuiet(t: TerminalSummary) = t.agentStatus == AgentStateWord.IDLE || t.agentStatus == AgentStateWord.NONE ||
        (t.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[t.address] == t.turn)

    val urgentCount = liveTerminals.count(::isUrgent)
    val readyCount = liveTerminals.count(::isReadyDone)
    val workingCount = liveTerminals.count { it.agentStatus == AgentStateWord.WORKING }
    val quietCount = liveTerminals.count(::isQuiet)

    val urgentNodes = nodes.filter { isUrgent(it.summary) }
    val readyNodes = nodes.filter { isReadyDone(it.summary) }
    val workingNodes = nodes.filter { it.summary.agentStatus == AgentStateWord.WORKING }
    val quietNodes = nodes.filter { isQuiet(it.summary) }

    var archiveExpanded by remember(currentFilter) {
        mutableStateOf(currentFilter == AgentStateWord.IDLE)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") {
            InboxHeader(
                connectionState = connectionState,
                hostLabel = hostLabel,
                totalLiveCount = totalLiveCount,
                urgentCount = urgentCount,
                readyCount = readyCount,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onOpenConnection = onOpenConnection
            )
        }

        if (connectionState !is ConnectionState.Connected) {
            item(key = "connect_banner") {
                ConnectionBanner(
                    connectionState = connectionState,
                    onConnectNow = onConnectNow,
                    onEnterDemoMode = onEnterDemoMode,
                    onOpenConnection = onOpenConnection
                )
            }
        }

        if (totalLiveCount > 0) {
            item(key = "filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HerdrFilterTab("全部", totalLiveCount, currentFilter == null, { onSelectFilter(null) })
                    HerdrFilterTab("待处理", urgentCount, currentFilter == AgentStateWord.BLOCKED, { onSelectFilter(AgentStateWord.BLOCKED) },
                        dotColor = if (urgentCount > 0) HerdrColors.Blocked else null)
                    HerdrFilterTab("待派活", readyCount, currentFilter == AgentStateWord.DONE, { onSelectFilter(AgentStateWord.DONE) },
                        dotColor = if (readyCount > 0) HerdrColors.Done else null)
                    HerdrFilterTab("运行中", workingCount, currentFilter == AgentStateWord.WORKING, { onSelectFilter(AgentStateWord.WORKING) })
                    HerdrFilterTab("归档", quietCount, currentFilter == AgentStateWord.IDLE, { onSelectFilter(AgentStateWord.IDLE) })
                }
            }
        }

        if (nodes.isEmpty()) {
            item(key = "empty") {
                EmptyInbox(
                    connected = connectionState is ConnectionState.Connected,
                    filtered = currentFilter != null && totalLiveCount > 0,
                    totalLiveCount = totalLiveCount,
                    onClearFilter = { onSelectFilter(null) }
                )
            }
            return@LazyColumn
        }

        if (urgentNodes.isNotEmpty()) {
            item(key = "h_urgent") { SectionEyebrow("需要你处理", urgentNodes.size, HerdrColors.Blocked) }
            items(urgentNodes, key = { "u_${it.summary.address}" }) { node ->
                AgentActionCard(
                    item = node.summary,
                    acknowledged = false,
                    children = childrenByParent[node.summary.address].orEmpty(),
                    isAttached = node.summary.address == activeTerminalAddress,
                    receipt = dispatchedReceipts[node.summary.address],
                    onOpen = { onCardClick(node.summary) },
                    onSelectChild = onCardClick
                ) {
                    val actions = remember(node.summary) {
                        StructuredTerminalReader.extractQuickActions(null, node.summary).take(2)
                    }
                    actions.forEachIndexed { idx, action ->
                        HerdrButton(
                            text = action.label,
                            onClick = { onQuickDispatch(node.summary, action.payload, action.isSpecialKey) },
                            kind = if (idx == 0) HerdrButtonKind.Primary else HerdrButtonKind.Neutral,
                            compact = true
                        )
                    }
                    HerdrButton(text = "打开", onClick = { onCardClick(node.summary) }, kind = HerdrButtonKind.Ghost, compact = true)
                }
            }
        }

        if (readyNodes.isNotEmpty()) {
            item(key = "h_ready") { SectionEyebrow("刚完成 · 等你下一步", readyNodes.size, HerdrColors.Done) }
            items(readyNodes, key = { "d_${it.summary.address}" }) { node ->
                AgentActionCard(
                    item = node.summary,
                    acknowledged = false,
                    children = childrenByParent[node.summary.address].orEmpty(),
                    isAttached = node.summary.address == activeTerminalAddress,
                    receipt = dispatchedReceipts[node.summary.address],
                    onOpen = { onCardClick(node.summary) },
                    onSelectChild = onCardClick,
                    trailing = {
                        HerdrIconButton(
                            icon = Icons.Default.Check,
                            contentDescription = "标记已阅",
                            onClick = { onAcknowledgeDone(node.summary) },
                            size = 36.dp,
                            iconSize = 18.dp,
                            tint = HerdrColors.Dim
                        )
                    }
                ) {
                    HerdrButton(
                        text = "写下一轮",
                        onClick = { onWriteNextInstruction(node.summary) },
                        kind = HerdrButtonKind.Tonal,
                        icon = Icons.Default.Edit,
                        compact = true
                    )
                    HerdrButton(
                        text = "继续",
                        onClick = { onQuickDispatch(node.summary, "继续", false) },
                        kind = HerdrButtonKind.Neutral,
                        compact = true
                    )
                }
            }
        }

        if (workingNodes.isNotEmpty()) {
            item(key = "h_working") { SectionEyebrow("运行中", workingNodes.size) }
            item(key = "g_working") {
                HerdrGroupedList {
                    workingNodes.forEachIndexed { idx, node ->
                        if (idx > 0) HerdrDivider(startIndent = 36.dp)
                        AgentCompactRow(
                            item = node.summary,
                            childCount = childrenByParent[node.summary.address]?.size ?: 0,
                            isAttached = node.summary.address == activeTerminalAddress,
                            receipt = dispatchedReceipts[node.summary.address],
                            acknowledged = false,
                            onClick = { onCardClick(node.summary) }
                        )
                    }
                }
            }
        }

        if (quietNodes.isNotEmpty()) {
            item(key = "h_quiet") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HerdrShapes.Control)
                        .clickable { archiveExpanded = !archiveExpanded }
                        .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("空闲与已阅", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${quietNodes.size}", style = HerdrType.Caption, color = HerdrColors.Dim)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (archiveExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (archiveExpanded) "收起" else "展开",
                        tint = HerdrColors.Dim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (archiveExpanded) {
                item(key = "g_quiet") {
                    HerdrGroupedList {
                        quietNodes.forEachIndexed { idx, node ->
                            if (idx > 0) HerdrDivider(startIndent = 36.dp)
                            AgentCompactRow(
                                item = node.summary,
                                childCount = childrenByParent[node.summary.address]?.size ?: 0,
                                isAttached = node.summary.address == activeTerminalAddress,
                                receipt = null,
                                acknowledged = node.summary.agentStatus == AgentStateWord.DONE,
                                onClick = { onCardClick(node.summary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxHeader(
    connectionState: ConnectionState,
    hostLabel: String,
    totalLiveCount: Int,
    urgentCount: Int,
    readyCount: Int,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text("牧群", style = HerdrType.Title, color = HerdrColors.Foreground)
                Spacer(Modifier.height(2.dp))
                val summaryLine = when {
                    connectionState !is ConnectionState.Connected -> "未连接"
                    totalLiveCount == 0 -> "暂无 Agent"
                    urgentCount + readyCount == 0 -> "$totalLiveCount 个 Agent · 都在正常推进"
                    else -> buildList {
                        if (urgentCount > 0) add("$urgentCount 个等你处理")
                        if (readyCount > 0) add("$readyCount 个等下一步")
                    }.joinToString("，")
                }
                Text(summaryLine, style = HerdrType.Label, color = HerdrColors.Muted, fontWeight = FontWeight.Normal)
            }
            HerdrIconButton(Icons.Default.Refresh, "刷新", onRefresh)
            HerdrIconButton(Icons.Default.Settings, "设置", onOpenSettings)
        }
        Spacer(Modifier.height(10.dp))
        // Connection status line: quiet when healthy, tappable to open diagnostics.
        val (dot, text) = when (connectionState) {
            is ConnectionState.Connected -> HerdrColors.Done to "已连接 · $hostLabel"
            is ConnectionState.Connecting -> HerdrColors.Working to "正在连接 · ${connectionState.activeStage.title}"
            is ConnectionState.Failed -> HerdrColors.Blocked to "连接受阻 · ${connectionState.failedStage.title}"
            ConnectionState.Disconnected -> HerdrColors.Idle to "未连接 · $hostLabel"
        }
        Row(
            modifier = Modifier
                .clip(HerdrShapes.Pill)
                .clickable(onClick = onOpenConnection)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(dot, 7.dp, pulsing = connectionState is ConnectionState.Connecting)
            Spacer(Modifier.width(8.dp))
            OneLine(text, HerdrType.Caption, HerdrColors.Muted)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = HerdrColors.Dim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ConnectionBanner(
    connectionState: ConnectionState,
    onConnectNow: () -> Unit,
    onEnterDemoMode: () -> Unit,
    onOpenConnection: () -> Unit
) {
    HerdrCard {
        val (title, detail) = when (connectionState) {
            is ConnectionState.Connecting ->
                "正在连接工作站" to "第 ${connectionState.activeStage.stepNumber} 步 · ${connectionState.activeStage.title}"
            is ConnectionState.Failed ->
                "连接没有成功" to connectionState.message
            else -> "连接你的工作站" to "连上后，所有 Agent 会按「需要你处理 / 刚完成 / 运行中」自动分组。"
        }
        Text(title, style = HerdrType.Heading, color = HerdrColors.Foreground)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = HerdrType.Label, color = HerdrColors.Muted, fontWeight = FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        HRow {
            HerdrButton(
                text = if (connectionState is ConnectionState.Connecting) "重新连接" else "连接",
                onClick = onConnectNow,
                kind = HerdrButtonKind.Primary
            )
            HerdrButton(text = "离线演示", onClick = onEnterDemoMode, kind = HerdrButtonKind.Neutral)
            HerdrButton(text = "诊断", onClick = onOpenConnection, kind = HerdrButtonKind.Ghost)
        }
    }
}

@Composable
private fun EmptyInbox(connected: Boolean, filtered: Boolean, totalLiveCount: Int, onClearFilter: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                filtered -> "这个分组里没有 Agent"
                connected -> "工作站上还没有 Agent"
                else -> "连接后在这里分诊"
            },
            style = HerdrType.Heading,
            color = HerdrColors.Foreground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                filtered -> "共 $totalLiveCount 个 Agent，切回「全部」查看。"
                connected -> "在电脑上启动 Agent 后会自动出现。"
                else -> "需要你处理的 Agent 会排在最前面。"
            },
            style = HerdrType.Label,
            color = HerdrColors.Muted,
            fontWeight = FontWeight.Normal
        )
        if (filtered) {
            Spacer(Modifier.height(16.dp))
            HerdrButton(text = "查看全部", onClick = onClearFilter, kind = HerdrButtonKind.Tonal)
        }
    }
}

/** Full card for agents that need a decision or a next instruction. */
@Composable
private fun AgentActionCard(
    item: TerminalSummary,
    acknowledged: Boolean,
    children: List<TerminalSummary>,
    isAttached: Boolean,
    receipt: DispatchedReceipt?,
    onOpen: () -> Unit,
    onSelectChild: (TerminalSummary) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit
) {
    val tone = statusToneOf(item.agentStatus, acknowledged)
    val headline = remember(item) { StructuredTerminalReader.extractLatestHeadline(null, item) }
    val isUrgent = item.agentStatus == AgentStateWord.BLOCKED || item.agentStatus == AgentStateWord.ERROR

    HerdrCard(onClick = onOpen, contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(tone)
            if (isAttached) {
                Spacer(Modifier.width(10.dp))
                Text("正在查看", style = HerdrType.Caption, color = HerdrColors.Accent, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            OneLine(agentMetaOf(item), HerdrType.Caption, HerdrColors.Dim, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = agentTitleOf(item),
            style = HerdrType.Heading,
            color = HerdrColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp)
        )
        if (headline.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            if (isUrgent) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tone.tint)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(headline, style = HerdrType.Body, color = HerdrColors.Foreground, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(
                    headline,
                    style = HerdrType.Body,
                    color = HerdrColors.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        if (children.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ChildAgentsLine(children, onSelectChild)
        }
        if (receipt != null) {
            Spacer(Modifier.height(10.dp))
            ReceiptLine(receipt)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { actions() }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun ChildAgentsLine(children: List<TerminalSummary>, onSelectChild: (TerminalSummary) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("子 Agent", style = HerdrType.Caption, color = HerdrColors.Dim)
        children.forEach { child ->
            val tone = statusToneOf(child.agentStatus)
            Row(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Raised)
                    .clickable { onSelectChild(child) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(tone.color, 6.dp, tone.pulsing)
                Spacer(Modifier.width(6.dp))
                val label = buildString {
                    append(child.address.substringAfterLast('/'))
                    child.name?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                }
                Text(label, style = HerdrType.Caption, color = HerdrColors.Foreground, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReceiptLine(receipt: DispatchedReceipt) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Check, contentDescription = null, tint = HerdrColors.Done, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        OneLine("已发送「${receipt.text}」", HerdrType.Caption, HerdrColors.Done)
    }
}

/** One-line row inside a grouped list, for agents that need no attention right now. */
@Composable
private fun AgentCompactRow(
    item: TerminalSummary,
    childCount: Int,
    isAttached: Boolean,
    receipt: DispatchedReceipt?,
    acknowledged: Boolean,
    onClick: () -> Unit
) {
    val tone = statusToneOf(item.agentStatus, acknowledged)
    val headline = remember(item) { StructuredTerminalReader.extractLatestHeadline(null, item) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
            StatusDot(tone.color, 8.dp, tone.pulsing)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(agentTitleOf(item), HerdrType.Label, HerdrColors.Foreground, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                if (isAttached) {
                    Spacer(Modifier.width(8.dp))
                    Text("正在查看", style = HerdrType.Caption, color = HerdrColors.Accent)
                }
            }
            Spacer(Modifier.height(2.dp))
            val sub = when {
                receipt != null -> "已发送「${receipt.text}」"
                headline.isNotBlank() -> headline
                else -> tone.label
            }
            OneLine(sub, HerdrType.Caption, if (receipt != null) HerdrColors.Done else HerdrColors.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.address.substringAfterLast('/'), style = HerdrType.Caption, color = HerdrColors.Dim, maxLines = 1)
            if (childCount > 0) {
                Text("+$childCount 子", style = HerdrType.Caption, color = HerdrColors.Dim, maxLines = 1)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = HerdrColors.Dim,
            modifier = Modifier.size(18.dp)
        )
    }
}
