package app.rapfiboard.engine

import app.rapfiboard.core.*

data class Score(val raw: String) {
    val numeric get() = raw.toIntOrNull()
    val mate get() = Regex("([+-])M(\\d+|\\*)").matchEntire(raw)?.let { (if(it.groupValues[1]=="+") 1 else -1) * (it.groupValues[2].toIntOrNull() ?: 10000) }
    fun negated() = Score(numeric?.let { (-it).toString() } ?: when { raw.startsWith("+M") -> "-"+raw.drop(1); raw.startsWith("-M") -> "+"+raw.drop(1); else -> raw })
}
data class Candidate(val index: Int, val score: Score, val winRate: Double?, val drawRate: Double?, val depth: Int, val nodes: Long, val nps: Long, val timeMs: Long, val pv: List<Move>)
sealed interface EngineEvent {
    data class Pv(val candidate: Candidate): EngineEvent
    data class Best(val move: Move): EngineEvent
    data class Forbidden(val moves: Set<Move>): EngineEvent
    data class Error(val message: String): EngineEvent
    data class Message(val message: String): EngineEvent
    data object Ok: EngineEvent
}
/** Exact framing from Rapfi search/searchoutput.cpp + command/gomocup.cpp, pinned in UPSTREAM.md.
 * INFO is emitted directly, separate from MESSAGE. PV indices are zero based; PV DONE commits one block.
 */
class ProtocolParser(private val size: Int) {
    private var block: MutableMap<String,String>? = null
    fun parse(line: String): EngineEvent? {
        if(line.length>65536) return EngineEvent.Error("엔진 출력 한도가 초과됐어요.")
        val s=line.trim()
        if(s=="OK") return EngineEvent.Ok
        Move.parse(s,size)?.let { return EngineEvent.Best(it) }
        if(s.startsWith("ERROR ") || s.startsWith("UNKNOWN")) return EngineEvent.Error(s)
        if(s.startsWith("FORBID ")) {
            val b=s.removePrefix("FORBID ").removeSuffix(".")
            if(b.length%4!=0 || b.any { !it.isDigit() }) return EngineEvent.Error("금수 응답을 읽을 수 없어요.")
            return EngineEvent.Forbidden(b.chunked(4).mapNotNull { Move.parse("${it.take(2).toInt()},${it.drop(2).toInt()}",size) }.toSet())
        }
        if(s.startsWith("INFO ")) {
            val parts=s.removePrefix("INFO ").split(' ',limit=2)
            if(parts.size<2) return null
            val (key,value)=parts
            if(key=="PV" && value!="DONE") { block=mutableMapOf("PV" to value); return null }
            if(key=="PV" && value=="DONE") {
                val b=block ?: return null; block=null
                val score=b["EVAL"] ?: return null
                if(score.toIntOrNull()==null && !Regex("[+-]M(\\d+|\\*)").matches(score)) return null
                val tokens=b["BESTLINE"]?.split(' ')?.filter { it.isNotBlank() } ?: return null
                val pv=tokens.map { Move.parse(it,size) ?: return null }; if(pv.isEmpty()) return null
                fun rate(k:String) = b[k]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
                return EngineEvent.Pv(Candidate(b["PV"]?.toIntOrNull() ?: return null,Score(score),rate("WINRATE"),rate("DRAWRATE"),b["DEPTH"]?.toIntOrNull() ?: 0,b["TOTALNODES"]?.toLongOrNull() ?: 0,b["SPEED"]?.toLongOrNull() ?: 0,b["TOTALTIME"]?.toLongOrNull() ?: 0,pv))
            }
            block?.set(key,value); return null
        }
        return if(s.startsWith("MESSAGE ")) EngineEvent.Message(s.removePrefix("MESSAGE ")) else null
    }
}

data class EngineSpec(val id:String="rapfi", val name:String="Rapfi", val classical:Boolean=false, val network:String="default", val executable:String?=null, val rapfiExtensions:Boolean=true)
data class AnalysisConfig(val timeMs:Long=1500, val nodes:Long=200000, val depth:Int=32, val multiPv:Int=3, val threads:Int=2, val hashMb:Int=32) {
    init { require(timeMs in 100..120000 && nodes>=0 && depth in 1..128 && multiPv in 1..10 && threads in 1..16 && hashMb in 4..256) }
}
data class Analysis(val positionKey:String, val engine:EngineSpec, val config:AnalysisConfig, val candidates:List<Candidate>, val best:Move?, val forbidden:Set<Move> = emptySet(), val cached:Boolean=false, val completed:Boolean=true)
interface GomokuEngine {
    suspend fun analyze(position:Position, config:AnalysisConfig, spec:EngineSpec, onUpdate:(Analysis)->Unit = {}):Analysis
    fun stop()
    fun close()
}

/** Rapfi YXBOARD supports explicit pass (-1,-1); getPosition inserts passes for repeated colors.
 * Initial pass keeps black/white identity when an editor position starts with white.
 */
fun positionPacket(p:Position):List<String> {
    fun row(m:String,color:Int)="$m,${if(color==p.side) 1 else 2}"
    val packet=mutableListOf<String>()
    if(p.stones.firstOrNull()?.color==2) packet+=row("-1,-1",1)
    p.stones.forEach { packet+=row(it.move.wire(),it.color) }
    val next=p.stones.lastOrNull()?.let { 3-it.color } ?: 1
    if(next!=p.side) packet+=row("-1,-1",next)
    return packet
}
