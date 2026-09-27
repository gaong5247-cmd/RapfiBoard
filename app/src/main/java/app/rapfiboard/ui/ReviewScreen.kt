package app.rapfiboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rapfiboard.review.*
import kotlin.math.roundToInt

private fun qualityColor(q:Quality)=when(q) {
    Quality.BRILLIANT -> Color(0xFF53E0C1)
    Quality.GREAT -> Color(0xFF4D8DFF)
    Quality.BEST -> Color(0xFF55B96B)
    Quality.EXCELLENT -> Color(0xFF75C96A)
    Quality.GOOD -> Color(0xFFA4D65E)
    Quality.THEORY,Quality.FORCED -> Color(0xFF69BF77)
    Quality.INACCURACY -> Color(0xFFF2B84B)
    Quality.MISTAKE -> Color(0xFFF28C3C)
    Quality.BLUNDER -> Color(0xFFEF5350)
    Quality.MISSED_WIN -> Color(0xFFFF7043)
    Quality.FORBIDDEN -> Color(0xFFC62828)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReviewScreen(vm:AppViewModel,s:AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionLabel("A better move, next time.","Rapfi Game Review · engine-backed explanations")
        StudioCard {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Fast","Balanced","Deep").forEachIndexed { i,label -> FilterChip(selected=s.reviewMode==i,onClick={vm.reviewMode(i)},label={Text(label)}) } }
            Button(onClick={if(s.busy) vm.stop() else vm.review()},enabled=s.position.stones.isNotEmpty() || s.reviewGame!=null,modifier=Modifier.fillMaxWidth()) { Text(if(s.busy) "Pause review · ${s.review.size} moves" else if(s.review.isNotEmpty()) "Resume / Reanalyze" else "Review this game") }
            if(s.reviewGame!=null || s.review.isNotEmpty()) {
                OutlinedButton(onClick=vm::stopReview,modifier=Modifier.fillMaxWidth()) { Text("리뷰 그만하기") }
            } else if(s.hasSavedReview) {
                OutlinedButton(onClick=vm::resumeLastReview,modifier=Modifier.fillMaxWidth()) { Text("지난 리뷰 이어보기") }
            }
            Text("완료된 각 수의 결과를 저장합니다. 리뷰를 그만해도 나중에 같은 대국을 이어서 분석할 수 있어요.",style=MaterialTheme.typography.bodySmall)
        }
        if(s.review.isNotEmpty()) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(1 to "BLACK",2 to "WHITE").forEach { (color,name) -> StudioCard(Modifier.weight(1f)) {
                    Text(name,style=MaterialTheme.typography.labelMedium)
                    val moves=s.review.filter { it.color==color }; val accuracy=AccuracyPolicy().accuracy(moves.mapNotNull { it.loss })
                    Text(accuracy?.let { "%.1f".format(it) } ?: "—",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)
                    Text("Accuracy · 앱 기준",style=MaterialTheme.typography.labelSmall)
                    moves.groupingBy { it.quality }.eachCount().forEach { (q,n) -> Text("${q.symbol} ${q.label}  $n",style=MaterialTheme.typography.bodySmall,color=qualityColor(q)) }
                } }
            }
            StudioCard {
                var raw by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Evaluation journey",fontWeight=FontWeight.SemiBold); TextButton(onClick={raw=!raw}){Text(if(raw) "Raw eval" else "Score probability")} }
                val points=s.review.map { r -> if(raw) r.before.candidates.firstOrNull()?.score?.numeric?.let { (if(r.color==1) it else -it).coerceIn(-2000,2000)/4000.0+.5 } else r.before.candidates.firstOrNull()?.winRate?.let { if(r.color==1) it else 1-it } }
                Canvas(Modifier.fillMaxWidth().height(130.dp).pointerInput(points) { detectTapGestures { p -> vm.reviewSelect(((p.x/size.width)*(points.size-1)).roundToInt().coerceIn(points.indices)) } }) {
                    drawLine(Color.Gray.copy(alpha=.4f),Offset(0f,size.height/2),Offset(size.width,size.height/2),1f)
                    for(i in 1 until points.size) { val a=points[i-1];val b=points[i];if(a!=null && b!=null) drawLine(Mint,Offset((i-1)*size.width/(points.size-1).coerceAtLeast(1),(1-a).toFloat()*size.height),Offset(i*size.width/(points.size-1).coerceAtLeast(1),(1-b).toFloat()*size.height),4f) }
                    s.review.forEachIndexed { i,r -> if((r.loss?:0.0)>.18 && points[i]!=null) drawCircle(Color(0xFFF27B65),5f,Offset(i*size.width/(points.size-1).coerceAtLeast(1),(1-points[i]!!).toFloat()*size.height)) }
                }
                val worst=s.review.indices.maxByOrNull { s.review[it].loss ?: 0.0 }; val best=s.review.indices.minByOrNull { s.review[it].loss ?: 1.0 }
                Row { TextButton(onClick={worst?.let(vm::reviewSelect)}){Text("Biggest loss")};TextButton(onClick={best?.let(vm::reviewSelect)}){Text("Lowest loss")} }
            }
            val row=s.review.getOrNull(s.reviewIndex) ?: s.review.first()
            StudioCard {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("◈ RAPFI",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary);Text("MOVE ${row.ply+1}",style=MaterialTheme.typography.labelMedium) }
                Text("${row.quality.symbol} ${row.quality.label}",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=qualityColor(row.quality))
                Text(row.explanation)
                Text("Best ${percent(row.before.candidates.firstOrNull()?.winRate)} → Played ${percent(row.playedValue)}",style=MaterialTheme.typography.bodyMedium)
                row.drawConfidence?.let { confidence ->
                    Surface(color=Color(0xFF5E6B78).copy(alpha=.16f),shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) {
                            Text("= Strong draw signal · ${(confidence*100).roundToInt()}%",fontWeight=FontWeight.SemiBold)
                            Text(row.drawReason ?: "Rapfi evaluation is tightly balanced.",style=MaterialTheme.typography.bodySmall)
                            Text("확정 무승부 선언이 아니라, 엔진 출력이 매우 무승부 쪽에 모인 위치예요.",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row { Button(onClick=vm::showLine){Text("WHY? · Show line")};Spacer(Modifier.width(8.dp));OutlinedButton(onClick=vm::train){Text("Find the move")} }
                if(s.training) {
                    Text(when(s.hint){0->"추천 수를 판에서 찾아보세요.";1->row.before.best?.let { "${if(it.x<s.position.size/2) "왼쪽" else "오른쪽"} ${if(it.y<s.position.size/2) "위쪽" else "아래쪽"}을 살펴보세요." } ?: "중앙 주변 후보를 살펴보세요.";2->"후보: "+row.before.candidates.joinToString { it.pv.firstOrNull()?.label(s.position.size) ?: "—" };else->"추천 수: ${row.before.candidates.firstOrNull()?.pv?.firstOrNull()?.label(s.position.size)}"})
                    TextButton(onClick=vm::hint){Text("Next hint · ${s.hint}/4")}
                }
                Text("${if(row.before.engine.classical) "Classical" else "NNUE"} · ${row.before.candidates.firstOrNull()?.nodes ?: 0} nodes · MultiPV ${row.before.config.multiPv}",style=MaterialTheme.typography.labelSmall)
            }
            GomokuBoard(s.position,if(s.training) emptyList() else s.analysis?.candidates ?: emptyList(),s.preview.take(s.previewCount),emptySet(),s.numbers,emptyList(),if(s.training) 0 else 3,vm::place,reviewMove=row.played,reviewStoneColor=row.color,reviewSymbol=row.quality.symbol,reviewColor=qualityColor(row.quality))
            if(!s.busy) {
                StudioCard {
                    Text("Review navigation · ${s.reviewIndex+1}/${s.review.size}",style=MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        FilledTonalButton(onClick=vm::reviewFirst,enabled=s.reviewIndex>0) { Text("<<") }
                        FilledTonalButton(onClick=vm::reviewPrevious,enabled=s.reviewIndex>0) { Text("<") }
                        FilledTonalButton(onClick=vm::reviewNext,enabled=s.reviewIndex<s.review.lastIndex) { Text(">") }
                        FilledTonalButton(onClick=vm::reviewLast,enabled=s.reviewIndex<s.review.lastIndex) { Text(">>") }
                    }
                    Text("수 이동 때마다 돌 강조와 등급 배지 애니메이션이 다시 재생됩니다.",style=MaterialTheme.typography.labelSmall)
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { s.review.forEachIndexed { i,r -> FilterChip(selected=i==s.reviewIndex,onClick={vm.reviewSelect(i)},label={Text("${i+1}. ${r.played.label(s.position.size)} ${r.quality.symbol}",color=qualityColor(r.quality))}) } }
        }
    }
}
