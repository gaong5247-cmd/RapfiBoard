package app.rapfiboard.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import java.util.Locale

fun percent(value:Double?)=value?.let { "%.1f%%".format(Locale.US,it*100) } ?: "—"
@Composable
private fun PositionStatus(s:AppState,modifier:Modifier=Modifier) {
    val winner=s.position.winner()
    val last=s.position.stones.lastOrNull()?.move?.label(s.position.size)
    Surface(
        modifier.fillMaxWidth(),
        color=MaterialTheme.colorScheme.surface.copy(alpha=.82f),
        shape=MaterialTheme.shapes.small
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text(
                if(winner>0) "${if(winner==1) "● Black wins" else "○ White wins"}"
                else if(s.position.side==1) "● Black" else "○ White",
                style=MaterialTheme.typography.bodyMedium,
                fontWeight=FontWeight.SemiBold
            )
            Text(
                listOfNotNull(last?.let { "Last $it" },s.position.rule.name.lowercase()).joinToString(" · "),
                modifier=Modifier.weight(1f).padding(horizontal=10.dp),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "MOVE ${s.position.stones.size}",
                style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.primary,
                fontWeight=FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BoardAction(
    icon:ImageVector,
    label:String,
    modifier:Modifier=Modifier,
    enabled:Boolean=true,
    onClick:()->Unit
) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(40.dp)) {
            Icon(icon,label,modifier=Modifier.size(20.dp))
        }
        Text(label,style=MaterialTheme.typography.labelSmall,color=if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun BoardScreen(vm:AppViewModel,s:AppState) {
    var toolsExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=720.dp
        val board:@Composable ()->Unit={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                GomokuBoard(
                    s.position,
                    s.analysis?.candidates ?: emptyList(),
                    s.preview.take(s.previewCount),
                    s.analysis?.forbidden ?: emptySet(),
                    s.numbers,
                    s.stats,
                    s.overlay,
                    vm::place
                )
                Surface(
                    Modifier.fillMaxWidth(),
                    tonalElevation=2.dp,
                    shape=MaterialTheme.shapes.medium
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=6.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        BoardAction(Icons.Outlined.Undo,"Undo",Modifier.weight(1f),!s.busy,vm::undo)
                        BoardAction(
                            if(s.busy) Icons.Outlined.Stop else Icons.Outlined.Analytics,
                            if(s.busy) "Stop" else "Analyze",
                            Modifier.weight(1f),
                            true,
                            { if(s.busy) vm.stop() else vm.analyze() }
                        )
                        BoardAction(Icons.Outlined.PlayArrow,"Engine",Modifier.weight(1f),!s.busy,{vm.analyze(play=true)})
                        BoardAction(Icons.Outlined.Refresh,"New",Modifier.weight(1f),!s.busy,{vm.newGame()})
                        BoardAction(Icons.Outlined.Tune,"Tools",Modifier.weight(1f),true,{toolsExpanded=!toolsExpanded})
                    }
                }
            }
        }
        val tools:@Composable ()->Unit={
            StudioCard {
                Text("BOARD TOOLS",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=s.numbers,onClick=vm::numbers,label={Text("Move #")})
                    listOf(1,3,5,10,99,0).forEach { n ->
                        FilterChip(
                            selected=s.overlay==n,
                            onClick={vm.overlay(n)},
                            label={Text(when(n){99->"Heat";0->"DB";else->"Top $n"})}
                        )
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Rule.entries.forEach { r -> OutlinedButton(onClick={vm.newGame(r)},enabled=!s.busy) { Text(r.name.lowercase()) } }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={vm.editor(if(s.editor<0) 1 else -1)}) { Text(if(s.editor<0) "Edit" else "Exit edit") }
                    TextButton(onClick={vm.bookmark("${s.position.rule} · ${s.position.stones.size} moves")}) { Text("Bookmark") }
                    TextButton(onClick=vm::review,enabled=s.position.stones.isNotEmpty()&&!s.busy) { Text("Review") }
                }
                if(s.editor>=0) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "Black",2 to "White",0 to "Erase").forEach { (mode,label) ->
                            FilterChip(selected=s.editor==mode,onClick={vm.editor(mode)},label={Text(label)})
                        }
                    }
                    FlowRow {
                        TextButton(onClick=vm::side){Text("Turn")}
                        TextButton(onClick={vm.transform(false)}){Text("Rotate")}
                        TextButton(onClick={vm.transform(true)}){Text("Mirror")}
                        TextButton(onClick={vm.newGame(size=if(s.position.size==15) 19 else 15)}){Text("15 / 19")}
                    }
                }
                var threats by remember { mutableStateOf(false) }
                TextButton(onClick={threats=!threats}) { Text(if(threats) "Hide threats" else "Threat patterns") }
                if(threats) {
                    val patterns=ThreatDetector.detect(s.position)
                    if(patterns.isEmpty()) Text("현재 연속형 위협 패턴 없음",style=MaterialTheme.typography.bodySmall)
                    patterns.forEach { t ->
                        TextButton(onClick={vm.preview(t.stones,t.stones.size)}) {
                            Text("${if(t.color==1) "Black" else "White"} ${t.label}: ${t.stones.joinToString { it.label(s.position.size) }}")
                        }
                    }
                }
                var coord by remember { mutableStateOf("") }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(coord,{coord=it},label={Text("H8")},modifier=Modifier.weight(1f),singleLine=true)
                    Button(onClick={
                        val x=coord.firstOrNull()?.uppercaseChar()?.minus('A')
                        val y=coord.drop(1).toIntOrNull()
                        if(x!=null && y!=null && x in 0 until s.position.size && y in 1..s.position.size) vm.place(Move(x,s.position.size-y))
                        else vm.reportError("A1부터 판 크기 안의 좌표를 입력해 주세요.")
                    }) { Text("Place") }
                }
            }
        }
        val panel:@Composable ()->Unit={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                AnalysisPanel(vm,s)
                if(wide || toolsExpanded) tools()
            }
        }
        if(wide) {
            Row(Modifier.fillMaxSize().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(8.dp)
                ) { PositionStatus(s); board() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { panel() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PositionStatus(s,Modifier.padding(start=8.dp,end=8.dp,top=4.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=8.dp,vertical=6.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    board()
                    panel()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable fun AnalysisPanel(vm:AppViewModel,s:AppState) {
    val a=s.analysis
    val candidates=a?.candidates.orEmpty()
    val best=candidates.firstOrNull()
    val black=best?.winRate?.let { if(s.position.side==1) it else 1-it }
    var details by remember(a?.positionKey) { mutableStateOf(false) }

    StudioCard {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("${s.engine.name.uppercase()} · ${if(s.engine.classical) "CLASSICAL" else "NNUE"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
            Text(if(a?.cached==true) "CACHED" else s.status,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(percent(black),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("BLACK",style=MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                Text((a?.best ?: best?.pv?.firstOrNull())?.label(s.position.size) ?: "—",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("BEST",style=MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.End) {
                Text(best?.depth?.toString() ?: "—",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("DEPTH",style=MaterialTheme.typography.labelSmall)
            }
        }
        LinearProgressIndicator(progress={ (black ?: .5).toFloat() },modifier=Modifier.fillMaxWidth().height(4.dp))

        if(best==null) {
            Text("Analyze를 누르면 후보수와 PV가 여기에 바로 붙어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                itemsIndexed(candidates.take(5)) { i,c ->
                    Surface(
                        shape=MaterialTheme.shapes.small,
                        color=if(i==0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier=Modifier.clickable { vm.preview(c.pv,c.pv.size) }
                    ) {
                        Text(
                            "${i+1}. ${c.pv.firstOrNull()?.label(s.position.size) ?: "—"}  ${percent(c.winRate)}",
                            Modifier.padding(horizontal=9.dp,vertical=7.dp),
                            style=MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            val pv=if(s.preview.isNotEmpty()) s.preview else best.pv
            if(pv.isNotEmpty()) {
                Text("PV",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(pv) { i,m ->
                        Surface(
                            shape=MaterialTheme.shapes.small,
                            color=if(i<s.previewCount) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                m.label(s.position.size),
                                Modifier.combinedClickable(
                                    onClick={vm.preview(pv,i+1)},
                                    onDoubleClick={vm.applyPv(pv,i+1)}
                                ).padding(horizontal=9.dp,vertical=7.dp),
                                style=MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            TextButton(onClick={details=!details},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp)) {
                Text(if(details) "Less details" else "More details")
            }
            if(details) {
                val win=best.winRate
                val draw=best.drawRate
                val loss=if(win!=null && draw!=null) 1-win-draw else null
                HorizontalDivider()
                Text(
                    "Raw ${best.score.raw} · W/D/L ${percent(win)} / ${percent(draw)} / ${percent(loss)}",
                    style=MaterialTheme.typography.bodySmall
                )
                Text(
                    "Depth ${best.depth} · Nodes ${best.nodes} · NPS ${best.nps} · ${best.timeMs} ms",
                    style=MaterialTheme.typography.bodySmall
                )
                Text(
                    "Threads ${a?.config?.threads ?: "—"} · MultiPV ${a?.config?.multiPv ?: "—"} · Network ${s.engine.network}",
                    style=MaterialTheme.typography.bodySmall
                )
                if(pv.isNotEmpty()) {
                    Slider(
                        value=s.previewCount.toFloat().coerceAtMost(pv.size.toFloat()),
                        onValueChange={vm.preview(pv,it.toInt())},
                        valueRange=0f..pv.size.coerceAtLeast(1).toFloat(),
                        steps=(pv.size-1).coerceAtLeast(0)
                    )
                }
                candidates.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.preview(c.pv,c.pv.size) }.padding(vertical=4.dp),
                        horizontalArrangement=Arrangement.SpaceBetween
                    ) {
                        Text("${c.index+1}. ${c.pv.firstOrNull()?.label(s.position.size) ?: "—"}",fontWeight=FontWeight.SemiBold)
                        Text("${percent(c.winRate)} · ${c.score.raw}",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "DB draw ${s.stats.sumOf { it.draws }} / ${s.stats.sumOf { it.wins+it.draws+it.losses }}",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

