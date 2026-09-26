package app.rapfiboard.core

import java.security.MessageDigest

enum class Rule(val protocol: Int) { FREESTYLE(0), STANDARD(1), RENJU(4) }
data class Move(val x: Int, val y: Int) {
    fun label(size: Int = 15) = "${('A'.code + x).toChar()}${size - y}"
    fun wire() = "$x,$y"
    companion object {
        fun parse(s: String, size: Int = 15): Move? {
            val parts = s.trim().split(','); if (parts.size != 2) return null
            val x = parts[0].toIntOrNull() ?: return null; val y = parts[1].toIntOrNull() ?: return null
            return Move(x,y).takeIf { x in 0 until size && y in 0 until size }
        }
    }
}
data class Stone(val move: Move, val color: Int)
data class Position(val size: Int = 15, val rule: Rule = Rule.FREESTYLE, val stones: List<Stone> = emptyList(), val side: Int = 1) {
    init {
        require(size in 5..22 && side in 1..2)
        require(stones.all { it.color in 1..2 && it.move.x in 0 until size && it.move.y in 0 until size })
        require(stones.map { it.move }.distinct().size == stones.size)
    }
    fun at(m: Move) = stones.firstOrNull { it.move == m }?.color ?: 0
    fun play(m: Move): Position {
        require(at(m) == 0 && m.x in 0 until size && m.y in 0 until size) { "이미 돌이 있거나 판 밖인 위치예요." }
        require(winner() == 0) { "이미 종료된 대국이에요." }
        return copy(stones = stones + Stone(m,side), side = 3-side)
    }
    fun undo() = if (stones.isEmpty()) this else copy(stones = stones.dropLast(1), side = stones.last().color)
    fun prefix(ply: Int) = copy(stones = stones.take(ply), side = if(ply < stones.size) stones[ply].color else side)
    fun winner(): Int {
        val cells = IntArray(size*size); stones.forEach { cells[it.move.y*size+it.move.x]=it.color }
        for(s in stones) for((dx,dy) in listOf(1 to 0,0 to 1,1 to 1,1 to -1)) {
            fun cell(x:Int,y:Int) = if(x in 0 until size && y in 0 until size) cells[y*size+x] else 0
            if(cell(s.move.x-dx,s.move.y-dy)==s.color) continue
            var n=0; var x=s.move.x; var y=s.move.y
            while(cell(x,y)==s.color) { n++; x+=dx; y+=dy }
            if(n==5 || n>5 && (rule==Rule.FREESTYLE || rule==Rule.RENJU && s.color==2)) return s.color
        }
        return 0
    }
    fun key(): String = sha256("$size|$rule|$side|" + stones.sortedWith(compareBy({it.move.y},{it.move.x})).joinToString(";") { "${it.move.wire()},${it.color}" })
    fun encode() = "RBF1|$size|${rule.name}|$side|" + stones.joinToString(";") { "${it.move.wire()},${it.color}" }
    fun isAlternating() = stones.withIndex().all { (i,s) -> s.color==1+i%2 } && side==1+stones.size%2
    companion object {
        fun decode(text:String):Position {
            require(text.length < 100_000)
            val p=text.trim().split('|'); require(p.size==5 && p[0]=="RBF1") { "RapfiBoard RBF1 형식이 아니에요." }
            val stones=if(p[4].isBlank()) emptyList() else p[4].split(';').map {
                val a=it.split(','); require(a.size==3); Stone(Move(a[0].toInt(),a[1].toInt()),a[2].toInt())
            }
            return Position(p[1].toInt(),Rule.valueOf(p[2]),stones,p[3].toInt())
        }
    }
}
fun sha256(s: String) = sha256(s.toByteArray())
fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
