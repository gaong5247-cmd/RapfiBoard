package app.rapfiboard.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import java.util.Locale

fun percent(value:Double?)=value?.let { "%.1f%%".format(Locale.US,it*100) } ?: "—"
@OptIn(ExperimentalLayoutApi::class)
@Composable fun BoardScreen(vm:AppViewModel,s:AppState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=720.dp
        val board:@Composable ()->Unit={ Column {
            Row(Modifier.fillMaxWidth().padding(bottom=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(s.position.winner()>0) "${if(s.position.winner()==1) "Black" else "White"} wins" else if(s.position.side==1) "● Black to move" else "○ White to move",fontWeight=FontWeight.SemiBold)
                Text("MOVE ${s.position.stones.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            }
            GomokuBoard(s.position,s.analysis?.candidates ?: emptyList(),s.preview.take(s.previewCount),s.analysis?.forbidden ?: emptySet(),s.numbers,s.stats,s.overlay,vm::place)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=vm::undo,enabled=!s.busy) { Icon(Icons.Outlined.Undo,"되돌리기") }
                Button(onClick={if(s.busy) vm.stop() else vm.analyze()},modifier=Modifier.weight(1f)) { Icon(if(s.busy) Icons.Outlined.Stop else Icons.Outlined.Analytics,null); Spacer(Modifier.width(8.dp)); Text(if(s.busy) "Stop" else "Analyze") }
                OutlinedButton(onClick={vm.analyze(play=true)},enabled=!s.busy) { Text("Engine move") }
            }
        } }
        val panel:@Composable ()->Unit={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AnalysisPanel(vm,s)
            StudioCard {
                SectionLabel("Board tools")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=s.numbers,onClick=vm::numbers,label={Text("Move numbers")})
                    listOf(1,3,5,10,99,0).forEach { n -> FilterChip(selected=s.overlay==n,onClick={vm.overlay(n)},label={Text(when(n){99->"Heatmap";0->"Database";else->"Top $n"})}) }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Rule.entries.forEach { r -> OutlinedButton(onClick={vm.newGame(r)},enabled=!s.busy) { Text(r.name.lowercase()) } } }
                Text("규칙 버튼은 새 대국을 시작합니다.",style=MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={vm.newGame()}) { Text("New game") }
                    TextButton(onClick={vm.editor(if(s.editor<0) 1 else -1)}) { Text(if(s.editor<0) "Position editor" else "Exit editor") }
                    TextButton(onClick={vm.bookmark("${s.position.rule} · ${s.position.stones.size} moves")}) { Text("Bookmark") }
                    TextButton(onClick=vm::review,enabled=s.position.stones.isNotEmpty()&&!s.busy) { Text("Review game") }
                }
                if(s.editor>=0) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(1 to "Black",2 to "White",0 to "Erase").forEach { (mode,label) -> FilterChip(selected=s.editor==mode,onClick={vm.editor(mode)},label={Text(label)}) } }
                    FlowRow { TextButton(onClick=vm::side){Text("Switch turn")};TextButton(onClick={vm.transform(false)}){Text("Rotate")};TextButton(onClick={vm.transform(true)}){Text("Mirror")};TextButton(onClick={vm.newGame(size=if(s.position.size==15) 19 else 15)}){Text("15 / 19 board")} }
                    Text(if(s.position.isAlternating()) "분석 가능한 교대 수순" else "편집 위치: Rapfi pass 확장으로 분석합니다. 게임 리뷰는 교대 수순만 지원해요.",style=MaterialTheme.typography.bodySmall)
                }
                var threats by remember { mutableStateOf(false) }
                TextButton(onClick={threats=!threats}) { Text("Threat patterns · GUI detector") }
                if(threats) {
                    val patterns=ThreatDetector.detect(s.position)
                    if(patterns.isEmpty()) Text("현재 연속형 위협 패턴 없음",style=MaterialTheme.typography.bodySmall)
                    patterns.forEach { t -> TextButton(onClick={vm.preview(t.stones,t.stones.size)}) { Text("${if(t.color==1) "Black" else "White"} ${t.label}: ${t.stones.joinToString { it.label(s.position.size) }}") } }
                    Text("GUI의 연속형 패턴 검사입니다. 렌주 금수 여부나 강제 승리를 뜻하지 않아요.",style=MaterialTheme.typography.labelSmall)
                }
                var coord by remember { mutableStateOf("") }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(coord,{coord=it},label={Text("좌표 (예: H8)")},modifier=Modifier.weight(1f),singleLine=true); Button(onClick={ val x=coord.firstOrNull()?.uppercaseChar()?.minus('A'); val y=coord.drop(1).toIntOrNull(); if(x!=null && y!=null && x in 0 until s.position.size && y in 1..s.position.size) vm.place(Move(x,s.position.size-y)) else vm.reportError("A1부터 판 크기 안의 좌표를 입력해 주세요.") }) { Text("Place") } }
            }
        } }
        if(wide) Row(Modifier.fillMaxSize().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) { Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState())) { board() }; Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { panel() } }
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) { board();panel() }
    }
}
@OptIn(ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable fun AnalysisPanel(vm:AppViewModel,s:AppState) {
    val a=s.analysis;val best=a?.candidates?.firstOrNull()
    StudioCard {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("${s.engine.name.uppercase()}  /  ${if(s.engine.classical) "CLASSICAL" else "NNUE"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary); Text(if(a?.cached==true) "CACHED" else s.status,style=MaterialTheme.typography.labelSmall) }
        val black=best?.winRate?.let { if(s.position.side==1) it else 1-it }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Column { Text("BLACK",style=MaterialTheme.typography.labelMedium);Text(percent(black),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold) }; Column { Text("BEST MOVE",style=MaterialTheme.typography.labelMedium);Text((a?.best ?: best?.pv?.firstOrNull())?.label(s.position.size) ?: "—",style=MaterialTheme.typography.displaySmall) } }
        LinearProgressIndicator(progress={ (black ?: .5).toFloat() },modifier=Modifier.fillMaxWidth().height(6.dp))
        Text("평가 변환값 · 실전 승률로 보정되지 않은 추정치",style=MaterialTheme.typography.labelSmall)
        Text("Raw ${best?.score?.raw ?: "—"}   ·   W / D / L ${if(best?.drawRate==null) "미제공" else "${percent(best.winRate)} / ${percent(best.drawRate)} / ${percent(1-(best.winRate?:0.0)-(best.drawRate?:0.0))}"}",style=MaterialTheme.typography.bodySmall)
        if(best!=null) {
            HorizontalDivider()
            a.candidates.forEach { c -> Row(Modifier.fillMaxWidth().clickable { vm.preview(c.pv,c.pv.size) }.padding(vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween) { Text("${c.index+1}.  ${c.pv.firstOrNull()?.label(s.position.size)}",fontWeight=FontWeight.SemiBold); Text("${percent(c.winRate)}  ·  ${c.score.raw}",style=MaterialTheme.typography.bodyMedium) } }
            Text("PRINCIPAL VARIATION",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            val pv=if(s.preview.isNotEmpty()) s.preview else best.pv
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { itemsIndexed(pv) { i,m -> Surface(shape=MaterialTheme.shapes.small,color=if(i<s.previewCount) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) { Text(m.label(s.position.size),Modifier.combinedClickable(onClick={vm.preview(pv,i+1)},onDoubleClick={vm.applyPv(pv,i+1)}).padding(14.dp)) } } }
            Slider(value=s.previewCount.toFloat().coerceAtMost(pv.size.toFloat()),onValueChange={vm.preview(pv,it.toInt())},valueRange=0f..pv.size.coerceAtLeast(1).toFloat(),steps=(pv.size-1).coerceAtLeast(0))
            Text("한 번 탭: 미리보기 · 두 번 탭: 적용 · Undo로 복귀",style=MaterialTheme.typography.labelSmall)
            var detail by remember { mutableStateOf(false) }; TextButton(onClick={detail=!detail}){Text(if(detail) "Hide engine details" else "Engine details")}
            if(detail) Text("Depth ${best.depth}  ·  Nodes ${best.nodes}\nNPS ${best.nps}  ·  ${best.timeMs} ms\nThreads ${a.config.threads} · MultiPV ${a.config.multiPv}\nNetwork ${s.engine.network}\n${s.position.rule} · ${if(a.completed) "Complete" else "Searching"}",style=MaterialTheme.typography.bodySmall)
        }
        var draw by remember { mutableStateOf(false) }; TextButton(onClick={draw=!draw}){Text("Draw information")}
        if(draw) Text("Proven draw: ${if(s.position.stones.size==s.position.size*s.position.size && s.position.winner()==0) "보드가 가득 참" else "확인되지 않음"}\nEngine draw: ${percent(best?.drawRate)}\nDatabase draw tendency: ${s.stats.sumOf { it.draws }} / ${s.stats.sumOf { it.wins+it.draws+it.losses }} 결과 있는 대국",style=MaterialTheme.typography.bodySmall)
    }
}
