package app.rapfiboard

import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import app.rapfiboard.review.*
import org.junit.Test
import org.junit.Assert.*

class CoreTests {
    private fun candidate(p:Double=.7,m:Move=Move(7,7),depth:Int=16)=Candidate(0,Score("200"),p,null,depth,10000,5000,2000,listOf(m))
    @Test fun coordinatesAreZeroBased() { assertEquals("H8",Move(7,7).label());assertEquals("A15",Move(0,0).label());assertEquals(Move(14,14),Move.parse("14,14")) }
    @Test fun rejectsInvalidCoordinates() { listOf("15,0","-1,-1","2","7,x","0,999").forEach { assertNull(Move.parse(it)) } }
    @Test fun serializationRoundTrip() { val p=Position().play(Move(7,7)).play(Move(8,8));assertEquals(p,Position.decode(p.encode())) }
    @Test fun keysIncludeRulesAndTurn() { val p=Position();assertNotEquals(p.key(),p.copy(rule=Rule.RENJU).key());assertNotEquals(p.key(),p.copy(side=2).key()) }
    @Test fun keysIgnoreHistoryOrderButKeepColors() { val p=Position(stones=listOf(Stone(Move(1,2),1),Stone(Move(2,2),2)));assertEquals(p.key(),p.copy(stones=p.stones.reversed()).key()) }
    @Test(expected=IllegalArgumentException::class) fun duplicateStoneRejected() { Position(stones=listOf(Stone(Move(0,0),1),Stone(Move(0,0),2))) }
    @Test fun undoRestoresPlayer() { val p=Position().play(Move(7,7));assertEquals(Position(),p.undo()) }
    @Test fun fiveIsWin() { val p=Position(stones=(0..4).map{Stone(Move(it,0),1)});assertEquals(1,p.winner()) }
    @Test fun standardOverlineIsNotWin() { val p=Position(rule=Rule.STANDARD,stones=(0..5).map{Stone(Move(it,0),1)});assertEquals(0,p.winner());assertEquals(1,p.copy(rule=Rule.FREESTYLE).winner()) }
    @Test fun renjuWhiteOverlineWins() { val p=Position(rule=Rule.RENJU,stones=(0..5).map{Stone(Move(it,0),2)});assertEquals(2,p.winner()) }
    @Test fun parseRealPvBlock() {
        val parser=ProtocolParser(15)
        val lines=listOf("INFO PV 0","INFO NUMPV 3","INFO DEPTH 12","INFO TOTALNODES 4129","INFO TOTALTIME 30","INFO SPEED 137633","INFO EVAL -134","INFO WINRATE 0.4421","INFO BESTLINE 7,7 8,8 6,8")
        lines.forEach{assertNull(parser.parse(it))}
        val p=(parser.parse("INFO PV DONE") as EngineEvent.Pv).candidate
        assertEquals(12,p.depth);assertEquals(4129L,p.nodes);assertEquals(3,p.pv.size);assertNull(p.drawRate)
    }
    @Test fun pvBlocksDoNotLeakFields() { val p=ProtocolParser(15);p.parse("INFO PV 0");p.parse("INFO EVAL 123");p.parse("INFO PV 1");p.parse("INFO BESTLINE 7,7");assertNull(p.parse("INFO PV DONE")) }
    @Test fun malformedPvDiscarded() { val p=ProtocolParser(15);p.parse("INFO PV 0");p.parse("INFO EVAL 5");p.parse("INFO BESTLINE 7,7 99,4");assertNull(p.parse("INFO PV DONE")) }
    @Test fun forbidCoordinatesFixedWidth() { val p=ProtocolParser(15);assertEquals(setOf(Move(7,8),Move(10,11)),(p.parse("FORBID 07081011.") as EngineEvent.Forbidden).moves) }
    @Test fun emptyForbidden() { assertEquals(emptySet<Move>(),(ProtocolParser(15).parse("FORBID .") as EngineEvent.Forbidden).moves) }
    @Test fun mateScoreNotCentipawns() { assertEquals(Score("-M9"),Score("+M9").negated());assertEquals(9,Score("+M9").mate);assertNull(Score("+M9").numeric) }
    @Test fun logisticDoesNotInventDraw() { val p=LogisticModel(200.0).predict(Score("0"),Rule.FREESTYLE,0);assertEquals(.5,p.expected!!,.00001);assertNull(p.wdl) }
    @Test fun calibratedSumsToOne() { val m=CalibratedWdl("fixture",mapOf(Rule.FREESTYLE to Triple(200.0,100.0,0.0)));val p=m.predict(Score("300"),Rule.FREESTYLE,10).wdl!!;assertEquals(1.0,p.win+p.draw+p.loss,1e-8);assertTrue(p.draw>0) }
    @Test fun bestIsNotAutomaticallyBrilliant() { assertEquals(Quality.BEST,ReviewClassifier().classify(ReviewEvidence(candidate(),Move(7,7),.7,emptyList()))) }
    @Test fun nearEquivalentMoveHasLittlePenalty() { val score=AccuracyPolicy().accuracy(listOf(.004))!!;assertTrue(score>98) }
    @Test fun blunderPenalized() { assertTrue(AccuracyPolicy().accuracy(listOf(.63))!!<10) }
    @Test fun missedWinDetected() { assertEquals(Quality.MISSED_WIN,ReviewClassifier().classify(ReviewEvidence(candidate(.99),Move(1,1),.3,emptyList()))) }
    @Test fun forbiddenOverridesEverything() { assertEquals(Quality.FORBIDDEN,ReviewClassifier().classify(ReviewEvidence(candidate(),Move(7,7),1.0,emptyList(),forbidden=true))) }
    @Test fun rawModelHasNoProbability() { assertNull(RawModel().predict(Score("42"),Rule.FREESTYLE,0).expected) }
}
