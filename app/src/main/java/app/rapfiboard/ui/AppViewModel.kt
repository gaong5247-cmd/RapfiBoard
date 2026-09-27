package app.rapfiboard.ui

import android.app.Application
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import app.rapfiboard.data.*
import app.rapfiboard.review.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class AppState(val position:Position=Position(),val tab:Int=0,val busy:Boolean=false,val status:String="Ready to explore",val error:String?=null,val analysis:Analysis?=null,val preview:List<Move> = emptyList(),val previewCount:Int=0,val engine:EngineSpec=EngineSpec(),val config:AnalysisConfig=AnalysisConfig(),val review:List<ReviewMove> = emptyList(),val reviewIndex:Int=0,val reviewMode:Int=1,val stats:List<MoveStat> = emptyList(),val rapfiDb:List<DatabaseMove> = emptyList(),val editor:Int=-1,val overlay:Int=3,val numbers:Boolean=true,val dark:Boolean=true,val training:Boolean=false,val hint:Int=0,val warm:Boolean=false,val networks:List<String> = emptyList(),val reviewGame:Position?=null,val hasSavedReview:Boolean=false)
class AppViewModel(application:Application):AndroidViewModel(application) {
    private val prefs=application.getSharedPreferences("session",0)
    private val engineManager=EngineManager(application)
    private val db=AppDatabase.open(application)
    val database=DatabaseRepository(db)
    private val repository=AnalysisRepository(engineManager,db.dao())
    private val reviewer=ReviewRepository(repository,db.dao())
    private val _state=MutableStateFlow(AppState())
    val state=_state.asStateFlow()
    val games=database.dao.games().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val bookmarks=database.dao.bookmarks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val benchmarks=database.dao.benchmarks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private var work:Job?=null
    private var databaseWork:Job?=null
    private val undo=ArrayDeque<Position>()
    init {
        val encoded=prefs.getString("position",null)
        try { if(encoded!=null) _state.update { it.copy(position=Position.decode(encoded),tab=prefs.getInt("tab",0).coerceIn(0,4),dark=prefs.getBoolean("dark",true),hasSavedReview=prefs.contains("lastReviewGame")) } else _state.update { it.copy(hasSavedReview=prefs.contains("lastReviewGame")) } }
        catch(e:Exception) { _state.update { it.copy(error="이전 세션을 복원하지 못했어요: ${e.message}") } }
        refreshNetworks()
    }
    private fun persist() { prefs.edit().putString("position",state.value.position.encode()).putInt("tab",state.value.tab).putBoolean("dark",state.value.dark).apply() }
    private fun engineForPosition(p:Position,current:EngineSpec=state.value.engine):EngineSpec {
        if(current.classical || current.executable!=null || !current.rapfiExtensions) return current
        return when(p.rule) {
            Rule.FREESTYLE -> current.copy(network=prefs.getString("freestyleNetwork",current.network) ?: "default",classical=false)
            Rule.STANDARD,Rule.RENJU -> current.copy(network="default",classical=false)
        }
    }
    private fun setPosition(p:Position,remember:Boolean=true) {
        stop(); if(remember) undo.addLast(state.value.position)
        val engine=engineForPosition(p)
        _state.update { it.copy(position=p,engine=engine,analysis=null,preview=emptyList(),previewCount=0,training=false,rapfiDb=emptyList()) }; persist()
        viewModelScope.launch { try { val stats=database.dao.stats(p.key()); if(state.value.position==p) _state.update { it.copy(stats=stats) } } catch(e:Exception) { reportError("Database: ${e.message}") } }
        if(state.value.overlay==0) refreshRapfiDatabase(p)
    }
    fun tab(index:Int) { _state.update { it.copy(tab=index) }; persist() }
    fun reportError(message:String?) { _state.update { it.copy(error=message) } }
    fun dark() { _state.update { it.copy(dark=!it.dark) }; persist() }
    fun numbers() { _state.update { it.copy(numbers=!it.numbers) } }
    fun overlay(n:Int) {
        _state.update { it.copy(overlay=n) }
        if(n==0) refreshRapfiDatabase(state.value.position)
    }
    private fun refreshRapfiDatabase(position:Position) {
        databaseWork?.cancel()
        val spec=state.value.engine
        databaseWork=viewModelScope.launch {
            try {
                val entries=engineManager.queryDatabase(position,spec)
                if(state.value.position==position && state.value.engine==spec) _state.update { it.copy(rapfiDb=entries) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(state.value.overlay==0) reportError(e.message ?: "Rapfi database query failed") }
        }
    }

    fun editor(mode:Int) { _state.update { it.copy(editor=mode) } }
    fun newGame(rule:Rule=state.value.position.rule,size:Int=15) { setPosition(Position(size,rule)); _state.update { it.copy(review=emptyList(),reviewGame=null) } }
    fun undo() { if(undo.isNotEmpty()) setPosition(undo.removeLast(),false) else setPosition(state.value.position.undo(),false) }
    fun load(p:Position) { setPosition(p); _state.update { it.copy(review=emptyList(),reviewGame=null) }; tab(1) }
    fun place(m:Move) {
        val s=state.value
        if(s.training) {
            val best=s.review.getOrNull(s.reviewIndex)?.before?.best ?: s.review.getOrNull(s.reviewIndex)?.before?.candidates?.firstOrNull()?.pv?.firstOrNull()
            if(m==best) { _state.update { it.copy(training=false,status="정답! 추천 변화를 확인해보세요.") }; showLine() } else reportError("다른 수를 찾아보자. 힌트를 한 단계 열어도 돼.")
            return
        }
        if(s.busy) return
        try {
            if(s.editor>=0) {
                val stones=s.position.stones.filter { it.move!=m } + if(s.editor==0) emptyList() else listOf(Stone(m,s.editor))
                setPosition(s.position.copy(stones=stones)); return
            }
            if(s.position.rule==Rule.RENJU && s.position.side==1) {
                launchWork("금수 확인 중") {
                    val checked=repository.analyze(s.position,AnalysisConfig(timeMs=100,nodes=1000,depth=2,multiPv=1,threads=1),s.engine.copy(classical=true))
                    if(m in checked.forbidden) reportError("Forbidden · Rapfi가 판정한 렌주 금수예요.")
                    else { val p=s.position.play(m); _state.update { it.copy(position=p,analysis=null,preview=emptyList()) }; undo.addLast(s.position); persist() }
                }
            } else setPosition(s.position.play(m))
        } catch(e:Exception) { reportError(e.message ?: "착수할 수 없어요.") }
    }
    fun side() { setPosition(state.value.position.copy(side=3-state.value.position.side)) }
    fun transform(mirror:Boolean) {
        val p=state.value.position; setPosition(p.copy(stones=p.stones.map { s -> s.copy(move=if(mirror) Move(p.size-1-s.move.x,s.move.y) else Move(p.size-1-s.move.y,s.move.x)) }))
    }
    private fun launchWork(label:String,block:suspend ()->Unit) {
        stop()
        work=viewModelScope.launch {
            _state.update { it.copy(busy=true,status=label,error=null) }
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { reportError(e.message ?: e.javaClass.simpleName) }
            finally { _state.update { it.copy(busy=false,status=if(it.error==null) "Ready" else "확인이 필요해요") } }
        }
    }
    private fun budget():AnalysisConfig {
        val p=getApplication<Application>().getSystemService(PowerManager::class.java)
        val hot=Build.VERSION.SDK_INT>=29 && p.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE
        _state.update { it.copy(warm=hot) }
        return if(hot) state.value.config.copy(threads=1,timeMs=minOf(1000,state.value.config.timeMs),multiPv=minOf(3,state.value.config.multiPv)) else state.value.config
    }
    fun analyze(play:Boolean=false,force:Boolean=false) {
        val s=state.value
        launchWork(if(play) "Rapfi가 생각 중" else "Analyzing position") {
            val a=repository.analyze(s.position,budget(),s.engine,force) { update -> _state.update { it.copy(analysis=update) } }
            _state.update { it.copy(analysis=a) }
            if(play) { val m=a.best ?: kotlin.error("엔진이 착수를 반환하지 않았어요."); undo.addLast(s.position); _state.update { it.copy(position=s.position.play(m),analysis=null) }; persist() }
        }
    }
    fun stop() { work?.cancel(); databaseWork?.cancel(); engineManager.stop(); _state.update { it.copy(busy=false) } }
    fun restart() { stop(); _state.update { it.copy(status="엔진 재시작 준비 완료",analysis=null) } }
    fun chooseEngine(classical:Boolean) { stop(); _state.update { it.copy(engine=EngineSpec(classical=classical),analysis=null,rapfiDb=emptyList()) }; if(state.value.overlay==0) refreshRapfiDatabase(state.value.position) }
    fun chooseNetwork(id:String) {
        stop()
        prefs.edit().putString("freestyleNetwork",id).apply()
        val p=state.value.position
        val selected=if(p.rule==Rule.FREESTYLE) state.value.engine.copy(network=id,classical=false) else state.value.engine.copy(network="default",classical=false)
        _state.update { it.copy(engine=selected,analysis=null,rapfiDb=emptyList()) }
        if(state.value.overlay==0) refreshRapfiDatabase(state.value.position)
    }
    fun config(c:AnalysisConfig) { _state.update { it.copy(config=c) } }
    fun preview(line:List<Move>,count:Int) { _state.update { it.copy(preview=line,previewCount=count.coerceIn(0,line.size)) } }
    fun applyPv(line:List<Move>,count:Int) {
        try { var p=state.value.position; line.take(count).forEach { p=p.play(it) }; setPosition(p) } catch(e:Exception) { reportError("변화를 적용할 수 없어요: ${e.message}") }
    }
    fun save() { val s=state.value; launchWork("Saving game") { val p=s.position; val ok=database.save(p,"${p.rule} · ${p.stones.size} moves",if(p.winner()>0) p.winner() else if(p.stones.size==p.size*p.size) 0 else -1); _state.update { it.copy(status=if(ok) "대국 저장 완료" else "이미 저장된 대국") } } }
    fun bookmark(comment:String) { val p=state.value.position; viewModelScope.launch { try { database.dao.bookmark(BookmarkEntity(p.key(),p.encode(),comment)); _state.update { it.copy(status="북마크 저장 완료") } } catch(e:Exception) { reportError(e.message) } } }
    fun favorite(game:GameEntity) { viewModelScope.launch { database.dao.updateGame(game.copy(favorite=!game.favorite)) } }
    fun reviewMode(mode:Int) { _state.update { it.copy(reviewMode=mode) } }
    private fun startReview(game:Position) {
        val s=state.value
        val reviewEngine=engineForPosition(game,s.engine)
        val config=when(s.reviewMode) { 0 -> budget().copy(timeMs=300,nodes=30000,multiPv=3); 2 -> budget().copy(timeMs=8000,nodes=2000000,multiPv=5); else -> budget().copy(timeMs=1500,nodes=200000,multiPv=3) }
        prefs.edit().putString("lastReviewGame",game.encode()).apply()
        _state.update { it.copy(tab=2,position=game,engine=reviewEngine,reviewGame=game,review=emptyList(),reviewIndex=0,preview=emptyList(),previewCount=0,training=false,hasSavedReview=true,status="Review · ${game.rule} NNUE") }
        launchWork("Reviewing game") {
            reviewer.review(game,config,reviewEngine) { rows -> _state.update { it.copy(review=rows,status="Review ${rows.size}/${game.stones.size} · ${game.rule}") } }
            if(state.value.review.isNotEmpty()) reviewSelect(0)
        }
    }
    fun review() {
        val s=state.value
        startReview(s.reviewGame ?: s.position)
    }
    fun stopReview() {
        val game=state.value.reviewGame
        stop()
        if(game!=null) prefs.edit().putString("lastReviewGame",game.encode()).apply()
        _state.update {
            it.copy(
                position=game ?: it.position,
                review=emptyList(),
                reviewGame=null,
                reviewIndex=0,
                analysis=null,
                preview=emptyList(),
                previewCount=0,
                training=false,
                status=if(game!=null) "리뷰 저장됨 · 나중에 이어볼 수 있어요." else it.status,
                hasSavedReview=prefs.contains("lastReviewGame")
            )
        }
        persist()
    }
    fun resumeLastReview() {
        val encoded=prefs.getString("lastReviewGame",null) ?: return
        try {
            val game=Position.decode(encoded)
            _state.update { it.copy(position=game,reviewGame=game,review=emptyList(),reviewIndex=0,analysis=null,preview=emptyList(),previewCount=0,training=false,tab=2) }
            startReview(game)
        } catch(e:Exception) {
            prefs.edit().remove("lastReviewGame").apply()
            _state.update { it.copy(hasSavedReview=false) }
            reportError("저장된 리뷰 대국을 복원하지 못했어요: ${e.message}")
        }
    }
    fun reviewSelect(i:Int) {
        val s=state.value; val game=s.reviewGame ?: return
        val r=s.review.getOrNull(i) ?: return
        _state.update { it.copy(reviewIndex=i,position=game.prefix(r.ply),analysis=r.before,preview=emptyList(),previewCount=0,training=false) }
    }
    fun reviewFirst() { if(state.value.review.isNotEmpty()) reviewSelect(0) }
    fun reviewPrevious() { reviewSelect((state.value.reviewIndex-1).coerceAtLeast(0)) }
    fun reviewNext() { reviewSelect((state.value.reviewIndex+1).coerceAtMost(state.value.review.lastIndex)) }
    fun reviewLast() { if(state.value.review.isNotEmpty()) reviewSelect(state.value.review.lastIndex) }
    fun showLine() {
        val r=state.value.review.getOrNull(state.value.reviewIndex) ?: return
        reviewSelect(state.value.reviewIndex)
        val line=r.before.candidates.firstOrNull()?.pv ?: return
        viewModelScope.launch { for(i in 1..line.size) { preview(line,i); delay(450) } }
    }
    fun train() { reviewSelect(state.value.reviewIndex); _state.update { it.copy(training=true,hint=0,analysis=null) } }
    fun hint() { _state.update { it.copy(hint=(it.hint+1).coerceAtMost(4)) }; if(state.value.hint==4) showLine() }
    fun match() {
        val s=state.value
        launchWork("NNUE vs Classical") {
            var p=s.position
            while(p.winner()==0 && p.stones.size<p.size*p.size) {
                currentCoroutineContext().ensureActive()
                val a=repository.analyze(p,budget(),s.engine.copy(classical=p.side==2))
                p=p.play(a.best ?: kotlin.error("착수가 없어요."))
                _state.update { it.copy(position=p,analysis=null,status="Engine match · ${p.stones.size}") }; persist(); delay(150)
            }
            database.save(p,"NNUE vs Classical",p.winner())
        }
    }
    fun benchmark() {
        val s=state.value
        launchWork("Benchmark running") {
            val p=Position().play(Move(7,7)).play(Move(8,8)).play(Move(6,8)).play(Move(8,6))
            val a=repository.analyze(p,budget().copy(timeMs=10000,nodes=500000,multiPv=1),s.engine,force=true)
            val c=a.candidates.firstOrNull() ?: kotlin.error("통계 응답이 없어요.")
            database.dao.benchmark(BenchmarkEntity(device="${Build.MANUFACTURER} ${Build.MODEL}",evaluator=if(s.engine.classical) "Classical" else "NNUE",threads=a.config.threads,nodes=c.nodes,elapsed=c.timeMs,nps=c.nps))
        }
    }
    fun registerExternal(packageName:String,library:String) {
        launchWork("외부 엔진 검증 중") {
            val candidate=withContext(Dispatchers.IO) { EngineRegistry(getApplication()).resolve(packageName,library) }
            repository.analyze(Position().play(Move(7,7)),AnalysisConfig(timeMs=300,nodes=5000,multiPv=1,threads=1),candidate,force=true)
            _state.update { it.copy(engine=candidate,analysis=null) }
            prefs.edit().putString("externalPackage",packageName).putString("externalLibrary",library).apply()
        }
    }
    fun refreshNetworks() { val dir=File(getApplication<Application>().filesDir,"networks"); _state.update { it.copy(networks=dir.listFiles()?.filter { f -> f.name.endsWith(".bin.lz4") }?.map { f -> f.name.removeSuffix(".bin.lz4") } ?: emptyList()) } }
    suspend fun importNetwork(bytes:ByteArray) {
        require(bytes.size in 1000..50_000_000) { "Network 크기가 올바르지 않아요." }
        val id=sha256(bytes); val dir=File(getApplication<Application>().filesDir,"networks"); dir.mkdirs(); val file=File(dir,"$id.bin.lz4"); file.writeBytes(bytes)
        try { repository.analyze(Position().play(Move(7,7)),AnalysisConfig(timeMs=100,nodes=1000,depth=2,multiPv=1,threads=1),EngineSpec(network=id),force=true) }
        catch(e:Exception) { file.delete(); throw IllegalArgumentException("호환되지 않거나 손상된 mix9svq network예요.",e) }
        refreshNetworks(); chooseNetwork(id)
    }
    override fun onCleared() { databaseWork?.cancel(); engineManager.close(); db.close() }
}
