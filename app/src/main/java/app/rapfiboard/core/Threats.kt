package app.rapfiboard.core

/** GUI-derived contiguous patterns, NOT Renju legality or engine search claims. */
data class Threat(val label:String,val color:Int,val stones:List<Move>)
object ThreatDetector {
    fun detect(p:Position):List<Threat> {
        val result=mutableListOf<Threat>()
        fun cell(m:Move)=if(m.x in 0 until p.size && m.y in 0 until p.size) p.at(m) else -1
        for(s in p.stones) for((dx,dy) in listOf(1 to 0,0 to 1,1 to 1,1 to -1)) {
            val before=Move(s.move.x-dx,s.move.y-dy);if(cell(before)==s.color) continue
            val line=mutableListOf<Move>();var m=s.move
            while(cell(m)==s.color) { line+=m;m=Move(m.x+dx,m.y+dy) }
            val open=(if(cell(before)==0) 1 else 0)+(if(cell(m)==0) 1 else 0)
            val label=when { line.size==5 -> "Five";line.size==4&&open==2 -> "Open four";line.size==4&&open==1 -> "Four";line.size==3&&open==2 -> "Open contiguous three";else -> null }
            if(label!=null) result+=Threat(label,s.color,line)
        }
        return result
    }
}
