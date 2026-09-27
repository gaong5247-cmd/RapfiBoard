package app.rapfiboard.review

import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import app.rapfiboard.data.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

class ReviewRepository(private val analysis:AnalysisRepository, private val dao:StoreDao) {
    suspend fun review(game:Position,config:AnalysisConfig,engine:EngineSpec,onMove:(List<ReviewMove>)->Unit):List<ReviewMove> {
        require(game.isAlternating()) { "리뷰는 교대로 둔 대국 기록이 필요해요." }
        val result=mutableListOf<ReviewMove>(); val classifier=ReviewClassifier()
        val configKey=sha256("review-v2|$config|$engine"); val gameKey=sha256(game.encode())
        val stored=dao.reviews(gameKey,configKey).associateBy { it.ply }
        for(i in game.stones.indices) {
            coroutineContext.ensureActive()
            stored[i]?.let { row ->
                result+=decode(row.json); onMove(result.toList())
            } ?: run {
                val p=game.prefix(i); val played=game.stones[i].move
                val before=analysis.analyze(p,config,engine)
                val best=before.candidates.firstOrNull() ?: before.best?.let { move ->
                    // Rapfi intentionally returns only the center move on an empty board, without search/PV/eval.
                    // Keep review running with an unscored synthetic candidate instead of treating that as an engine failure.
                    Candidate(0,Score("0"),null,null,0,0,0,0,listOf(move))
                } ?: error("이 엔진은 리뷰에 필요한 평가와 PV를 제공하지 않아요.")
                val forbidden=played in before.forbidden
                val next=game.prefix(i+1)
                var after:Analysis?=null
                val playedValue=when {
                    forbidden -> 0.0
                    next.winner()==p.side -> 1.0
                    next.stones.size==next.size*next.size -> .5
                    else -> {
                        after=analysis.analyze(next,config,engine)
                        after?.candidates?.firstOrNull()?.winRate?.let { 1-it }
                    }
                }
                val loss=best.winRate?.let { b -> playedValue?.let { (b-it).coerceAtLeast(0.0) } }
                val second=before.candidates.filter { it.pv.firstOrNull()!=best.pv.firstOrNull() }.mapNotNull { it.winRate }.maxOrNull()
                val gap=if(best.winRate!=null && second!=null) (best.winRate-second).coerceAtLeast(0.0) else null
                val stable=before.completed && best.depth>=12 && (before.candidates.size>=2 || best.score.mate!=null)
                val tactical=best.score.mate!=null || (gap ?: 0.0)>=.30
                val quality=if(before.candidates.isEmpty() && p.stones.isEmpty()) {
                    if(played==best.pv.firstOrNull()) Quality.BEST else Quality.GOOD
                } else classifier.classify(ReviewEvidence(best,played,playedValue,before.candidates,forbidden=forbidden,stable=stable,independentTacticalEvidence=tactical))
                val draw=assessDraw(before)
                val move=ReviewMove(i,p.side,played,before,after,playedValue,loss,quality,Explanation.explain(quality,played,best,playedValue,p.size),draw?.confidence,draw?.reason)
                dao.putReview(ReviewEntity(gameKey,configKey,i,encode(move)))
                result+=move; onMove(result.toList())
                if(forbidden) return result
            }
        }
        return result
    }
    private fun encode(m:ReviewMove)=JSONObject()
        .put("ply",m.ply).put("color",m.color).put("played",m.played.wire())
        .put("before",AnalysisCodec.encode(m.before)).put("after",m.after?.let { AnalysisCodec.encode(it) })
        .put("value",m.playedValue).put("loss",m.loss).put("quality",m.quality.name)
        .put("explanation",m.explanation).put("drawConfidence",m.drawConfidence).put("drawReason",m.drawReason).toString()
    private fun decode(s:String):ReviewMove {
        val o=JSONObject(s)
        return ReviewMove(
            o.getInt("ply"),o.getInt("color"),Move.parse(o.getString("played"),22)!!,
            AnalysisCodec.decode(o.getString("before")),
            if(o.has("after") && !o.isNull("after")) AnalysisCodec.decode(o.getString("after")) else null,
            if(o.has("value") && !o.isNull("value")) o.getDouble("value") else null,
            if(o.has("loss") && !o.isNull("loss")) o.getDouble("loss") else null,
            Quality.valueOf(o.getString("quality")),o.getString("explanation"),
            if(o.has("drawConfidence") && !o.isNull("drawConfidence")) o.getDouble("drawConfidence") else null,
            if(o.has("drawReason") && !o.isNull("drawReason")) o.getString("drawReason") else null
        )
    }
}
