package app.rapfiboard.review

import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import kotlin.math.*

data class Wdl(val win:Double,val draw:Double,val loss:Double,val source:String) {
    init { require(listOf(win,draw,loss).all { it.isFinite() && it in 0.0..1.0 }); require(abs(win+draw+loss-1)<0.00001) }
}
data class Probability(val expected:Double?,val wdl:Wdl?,val source:String)
interface WdlModel { fun predict(score:Score, rule:Rule, ply:Int):Probability }
/** Calibration coefficients must come from a supplied fitted data set. Never populated with invented coefficients. */
class CalibratedWdl(private val modelId:String, private val coefficients:Map<Rule,Triple<Double,Double,Double>>):WdlModel {
    override fun predict(score:Score,rule:Rule,ply:Int):Probability {
        val e=score.numeric ?: return Probability(null,null,"raw")
        val (scale,drawMargin,plySlope)=coefficients[rule] ?: return Probability(null,null,"missing calibration")
        require(scale>0 && drawMargin>=0)
        val margin=(drawMargin+plySlope*ply).coerceAtLeast(0.0)
        val w=1/(1+exp(-(e-margin)/scale)); val l=1/(1+exp(-(-e-margin)/scale))
        return Probability(w+(1-w-l)/2,Wdl(w,1-w-l,l,modelId),modelId)
    }
}
class RawModel:WdlModel { override fun predict(score:Score,rule:Rule,ply:Int)=Probability(null,null,"Raw evaluation") }
/** Rapfi fallback is a binary logistic score proxy. No calibrated draw probability is implied. */
class LogisticModel(private val scale:Double):WdlModel {
    init { require(scale>0) }
    override fun predict(score:Score,rule:Rule,ply:Int):Probability {
        val p=score.numeric?.let { 1/(1+exp(-it/scale)) } ?: score.mate?.let { if(it>=0) 1.0 else 0.0 }
        return Probability(p,null,"Logistic estimate · uncalibrated")
    }
}
fun Candidate.probability() = Probability(winRate,drawRate?.let { d -> winRate?.takeIf { it+d<=1.000001 }?.let { w -> Wdl(w,d,(1-w-d).coerceAtLeast(0.0),"Engine reported") } },"Rapfi score conversion · not calibrated")

enum class Quality(val label:String,val symbol:String) {
    BRILLIANT("Brilliant","!!"), GREAT("Great","!"), BEST("Best","★"), EXCELLENT("Excellent","✓"), GOOD("Good","•"), THEORY("Database / Theory","▤"), INACCURACY("Inaccuracy","?!"), MISTAKE("Mistake","?"), BLUNDER("Blunder","??"), FORCED("Forced","→"), MISSED_WIN("Missed Win","!↘"), FORBIDDEN("Forbidden","×")
}
data class ReviewPolicy(val excellentLoss:Double=.01,val goodLoss:Double=.03,val inaccuracyLoss:Double=.08,val mistakeLoss:Double=.18,val winning:Double=.95,val missedWin:Double=.60,val greatGap:Double=.25,val minimumDepth:Int=12,val accuracyScale:Double=4.0)
data class ReviewEvidence(val best:Candidate,val played:Move,val playedValue:Double?,val alternatives:List<Candidate>,val forbidden:Boolean=false,val legalMoves:Int?=null,val theory:Boolean=false,val independentTacticalEvidence:Boolean=false,val stable:Boolean=false)
class ReviewClassifier(private val p:ReviewPolicy=ReviewPolicy()) {
    fun classify(e:ReviewEvidence):Quality {
        if(e.forbidden) return Quality.FORBIDDEN
        if(e.legalMoves==1) return Quality.FORCED
        val b=e.best.winRate ?: return Quality.GOOD
        val a=e.playedValue ?: return Quality.GOOD
        val loss=(b-a).coerceAtLeast(0.0)
        if(b>=p.winning && a<p.missedWin) return Quality.MISSED_WIN
        if(loss>p.mistakeLoss) return Quality.BLUNDER
        if(loss>p.inaccuracyLoss) return Quality.MISTAKE
        if(loss>p.goodLoss) return Quality.INACCURACY
        val second=e.alternatives.filter { it.pv.firstOrNull()!=e.best.pv.firstOrNull() }.maxOfOrNull { it.winRate ?: 0.0 }
        val bestMove=e.played==e.best.pv.firstOrNull()
        if(bestMove && second!=null && b-second>=p.greatGap && e.best.depth>=p.minimumDepth && e.stable) {
            if(e.independentTacticalEvidence && a>=p.winning) return Quality.BRILLIANT
            return Quality.GREAT
        }
        if(e.theory && loss<=p.excellentLoss) return Quality.THEORY
        if(bestMove && loss<=p.excellentLoss) return Quality.BEST
        if(loss<=p.excellentLoss) return Quality.EXCELLENT
        return Quality.GOOD
    }
}
class AccuracyPolicy(private val policy:ReviewPolicy=ReviewPolicy()) {
    fun accuracy(losses:List<Double>):Double? = if(losses.isEmpty()) null else losses.map { 100*exp(-policy.accuracyScale*it.coerceIn(0.0,1.0)) }.average()
}
data class ReviewMove(val ply:Int,val color:Int,val played:Move,val before:Analysis,val after:Analysis?,val playedValue:Double?,val loss:Double?,val quality:Quality,val explanation:String)
object Explanation {
    fun explain(q:Quality,move:Move,best:Candidate,played:Double?,size:Int):String {
        val recommendation=best.pv.firstOrNull()?.label(size) ?: "—"
        val loss=if(played!=null && best.winRate!=null) "평가 변환값 손실 %.1f%%p.".format(100*(best.winRate-played).coerceAtLeast(0.0)) else "확률 비교 자료가 충분하지 않아요."
        return when(q) {
            Quality.FORBIDDEN -> "Rapfi가 이 위치를 금수로 판정했어요."
            Quality.BRILLIANT -> "충분한 탐색과 별도 전술 근거를 만족한 특별한 수예요."
            Quality.BEST -> "${move.label(size)}는 이 탐색에서 가장 높게 평가된 수예요."
            Quality.MISSED_WIN -> "유리한 평가를 유지할 기회를 놓쳤어요. $recommendation 변화를 확인해보세요. $loss"
            Quality.BLUNDER,Quality.MISTAKE,Quality.INACCURACY -> "${move.label(size)}보다 ${recommendation}가 높게 평가됐어요. $loss"
            else -> "$loss 추천 변화는 ${recommendation}에서 시작해요."
        } + "\n앱이 엔진 분석을 바탕으로 생성한 설명입니다."
    }
}
