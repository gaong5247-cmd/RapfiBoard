package app.rapfiboard.ui

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import app.rapfiboard.data.MoveStat
import kotlin.math.*

@Composable fun GomokuBoard(position:Position,candidates:List<Candidate>,preview:List<Move>,forbidden:Set<Move>,numbers:Boolean,stats:List<MoveStat>,overlay:Int,onPlace:(Move)->Unit,modifier:Modifier=Modifier,reviewMove:Move?=null,reviewStoneColor:Int?=null,reviewSymbol:String?=null,reviewColor:Color=Color.Transparent) {
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    val reviewFlash=remember { Animatable(0f) }
    LaunchedEffect(reviewMove,reviewSymbol,reviewStoneColor) {
        if(reviewMove!=null) {
            reviewFlash.stop()
            reviewFlash.snapTo(1f)
            reviewFlash.animateTo(0f,animationSpec=tween(900))
        } else reviewFlash.snapTo(0f)
    }
    val haptic=LocalHapticFeedback.current
    val text=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-medium",0) } }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics {
            contentDescription="${position.size}×${position.size} 오목판, ${position.stones.size}수, ${if(position.side==1) "흑" else "백"} 차례"
            customActions=listOf(CustomAccessibilityAction("중앙에 착수") { onPlace(Move(position.size/2,position.size/2)); true })
        }.pointerInput(position,zoom,pan) {
            detectTapGestures(onTap={ tap ->
                val width=size.width.toFloat(); val padding=width*.067f; val step=(width-2*padding)/(position.size-1)
                val center=Offset(width/2,width/2); val local=(tap-center-pan)/zoom+center
                val x=((local.x-padding)/step).roundToInt(); val y=((local.y-padding)/step).roundToInt()
                if(x in 0 until position.size && y in 0 until position.size) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlace(Move(x,y)) }
            })
        }.pointerInput(Unit) { detectTransformGestures { _, delta, scale, _ -> zoom=(zoom*scale).coerceIn(1f,3f); val limit=size.width*(zoom-1)/2; pan=Offset((pan.x+delta.x).coerceIn(-limit,limit),(pan.y+delta.y).coerceIn(-limit,limit)) } }) {
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE2C59A),Color(0xFFCFAB76))),cornerRadius=androidx.compose.ui.geometry.CornerRadius(22f))
            clipRect {
                withTransform({ translate(pan.x,pan.y); scale(zoom,zoom,center) }) {
                    val padding=size.width*.067f; val step=(size.width-2*padding)/(position.size-1); val edge=size.width-padding
                    fun point(m:Move)=Offset(padding+m.x*step,padding+m.y*step)
                    for(i in 0 until position.size) {
                        val d=padding+i*step
                        drawLine(Color(0xFF775A38).copy(alpha=.65f),Offset(padding,d),Offset(edge,d),1.3f)
                        drawLine(Color(0xFF775A38).copy(alpha=.65f),Offset(d,padding),Offset(d,edge),1.3f)
                        text.textSize=step*.38f; text.color=android.graphics.Color.rgb(99,74,46)
                        drawContext.canvas.nativeCanvas.drawText(('A'.code+i).toChar().toString(),d,padding*.65f,text)
                        drawContext.canvas.nativeCanvas.drawText((position.size-i).toString(),padding*.36f,d+step*.12f,text)
                    }
                    if(position.size==15) listOf(Move(3,3),Move(11,3),Move(7,7),Move(3,11),Move(11,11)).forEach { drawCircle(Color(0xFF775A38),step*.11f,point(it)) }
                    forbidden.forEach { val o=point(it); drawLine(Color(0xFFC74646),o-Offset(step*.16f,step*.16f),o+Offset(step*.16f,step*.16f),2.5f); drawLine(Color(0xFFC74646),o+Offset(step*.16f,-step*.16f),o+Offset(-step*.16f,step*.16f),2.5f) }
                    position.stones.forEachIndexed { i,s ->
                        val o=point(s.move); drawCircle(Color.Black.copy(alpha=.18f),step*.46f,o+Offset(1.5f,3f))
                        drawCircle(Brush.radialGradient(if(s.color==1) listOf(Color(0xFF4D5155),Color(0xFF13181B)) else listOf(Color.White,Color(0xFFD9DCDD)),o-Offset(step*.13f,step*.14f),step*.7f),step*.44f,o)
                        if(numbers) { text.textSize=step*.42f; text.color=if(s.color==1) android.graphics.Color.WHITE else android.graphics.Color.BLACK; drawContext.canvas.nativeCanvas.drawText("${i+1}",o.x,o.y+step*.15f,text) }
                        else if(i==position.stones.lastIndex) drawCircle(Color(0xFFEF6B5C),step*.1f,o)
                    }
                    preview.forEachIndexed { i,m -> if(position.at(m)==0) { val o=point(m); val black=(position.side+i)%2==1; drawCircle(if(black) Color.Black.copy(alpha=.45f) else Color.White.copy(alpha=.7f),step*.43f,o); text.color=if(black) android.graphics.Color.WHITE else android.graphics.Color.BLACK; text.textSize=step*.4f; drawContext.canvas.nativeCanvas.drawText("${i+1}",o.x,o.y+step*.14f,text) } }
                    reviewMove?.let { m ->
                        val o=point(m)
                        if(position.at(m)==0 && reviewStoneColor!=null) {
                            val pulse=1f+0.12f*reviewFlash.value
                            drawCircle(Color.Black.copy(alpha=.18f),step*.46f*pulse,o+Offset(1.5f,3f))
                            val black=reviewStoneColor==1
                            drawCircle(Brush.radialGradient(if(black) listOf(Color(0xFF4D5155),Color(0xFF13181B)) else listOf(Color.White,Color(0xFFD9DCDD)),o-Offset(step*.13f,step*.14f),step*.7f*pulse),step*.44f*pulse,o)
                        }
                        if(reviewFlash.value>0.01f) {
                            val glowRadius=step*(.47f+.10f*reviewFlash.value)
                            drawCircle(Color(0xFF53E0C1).copy(alpha=.82f*reviewFlash.value),glowRadius,o)
                            drawCircle(Color.White.copy(alpha=.22f*reviewFlash.value),glowRadius*.72f,o)
                        }
                        reviewSymbol?.takeIf { it.isNotBlank() }?.let { symbol ->
                            val alpha=(1f-reviewFlash.value).coerceIn(.18f,1f)
                            val badge=o+Offset(step*.48f,-step*.48f)
                            drawRoundRect(reviewColor.copy(alpha=alpha),topLeft=badge-Offset(step*.35f,step*.27f),size=androidx.compose.ui.geometry.Size(step*.70f,step*.54f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(step*.16f))
                            text.textSize=step*.34f; text.color=android.graphics.Color.WHITE
                            drawContext.canvas.nativeCanvas.drawText(symbol,badge.x,badge.y+step*.12f,text)
                        }
                    }
                    candidates.take(if(overlay==99) 10 else overlay).forEachIndexed { i,c -> c.pv.firstOrNull()?.let { m -> if(position.at(m)==0 && m !in preview) {
                        val o=point(m); val color=if(i==0) Color(0xFFD44C43) else Color(0xFF1E7864)
                        drawCircle(color.copy(alpha=if(overlay==99) .25f+.6f*(c.winRate ?: .5).toFloat() else .93f),step*.43f,o)
                        text.color=android.graphics.Color.WHITE; text.textSize=step*.34f
                        drawContext.canvas.nativeCanvas.drawText(c.winRate?.let { "${(it*100).roundToInt()}" } ?: "${i+1}",o.x,o.y+step*.12f,text)
                    } } }
                    if(overlay==0) stats.take(5).forEach { st -> val m=Move(st.x,st.y); if(position.at(m)==0) { val o=point(m); drawCircle(Color(0xFF5968B0).copy(alpha=.85f),step*.42f,o); text.color=android.graphics.Color.WHITE;text.textSize=step*.34f;drawContext.canvas.nativeCanvas.drawText("${st.games}",o.x,o.y+step*.12f,text) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${position.size} × ${position.size}  ·  ${position.rule.name.lowercase()}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=12.dp))
            TextButton(onClick={ zoom=1f;pan=Offset.Zero }) { Text(if(zoom>1.01f) "Reset zoom" else "Pinch to zoom") }
        }
    }
}
