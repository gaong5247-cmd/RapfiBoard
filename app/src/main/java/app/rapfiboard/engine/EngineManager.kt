package app.rapfiboard.engine

import android.content.Context
import android.util.Log
import app.rapfiboard.BuildConfig
import app.rapfiboard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.BufferedWriter

object AppLog {
    fun event(category:String, message:String) { if(BuildConfig.DEBUG) Log.d("Rapfi/$category",message) }
}
class RuntimeInstaller(private val context:Context) {
    val root=File(context.filesDir,"engine/runtime-v1")
    suspend fun install():File = withContext(Dispatchers.IO) {
        root.mkdirs()
        val manifest=JSONObject(context.assets.open("runtime/manifest.json").bufferedReader().use { it.readText() })
        for(name in manifest.keys()) {
            require(!name.contains('/') && !name.contains(".."))
            val target=File(root,name); val expected=manifest.getString(name)
            if(!target.isFile || sha256(target.readBytes())!=expected) {
                val temp=File(root,"$name.tmp")
                context.assets.open("runtime/$name").use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(sha256(temp.readBytes())==expected) { "기본 리소스 검증 실패: $name" }
                check(temp.renameTo(target)) { "엔진 파일을 저장하지 못했어요." }
            }
        }
        root
    }
    fun config(spec:EngineSpec, rule:Rule):File {
        val mode=if(spec.classical) "classical" else "nnue"
        val directory=File(root,"$mode/${rule.name.lowercase()}"); directory.mkdirs()
        val template=File(root,if(spec.classical) "classical.toml" else "nnue.toml").readText()
        val runtime=root.absolutePath.replace("\\","/")
        var config=template.replace("@RUNTIME@",runtime)

        if(!spec.classical) {
            val selectedWeights=when(rule) {
                Rule.FREESTYLE -> {
                    val path=if(spec.network=="default") {
                        "$runtime/mix9svqfreestyle_bsmix.bin.lz4"
                    } else {
                        val imported=File(context.filesDir,"networks/${spec.network}.bin.lz4")
                        check(imported.isFile && sha256(imported.readBytes())==spec.network) { "선택한 network가 없거나 손상됐어요." }
                        imported.absolutePath.replace("\\","/")
                    }
                    """
                    [[model.evaluator.weights]]
                    weight_file = "$path"
                    """.trimIndent()
                }
                Rule.STANDARD -> """
                    [[model.evaluator.weights]]
                    weight_file = "$runtime/mix9svqstandard_bs15.bin.lz4"
                """.trimIndent()
                Rule.RENJU -> """
                    [[model.evaluator.weights]]
                    weight_file_black = "$runtime/mix9svqrenju_bs15_black.bin.lz4"
                    weight_file_white = "$runtime/mix9svqrenju_bs15_white.bin.lz4"
                """.trimIndent()
            }
            val firstWeight=config.indexOf("[[model.evaluator.weights]]")
            val searchSection=config.indexOf("\n[search]")
            check(firstWeight>=0 && searchSection>firstWeight) { "NNUE config의 weights 섹션을 찾을 수 없어요." }
            config=config.substring(0,firstWeight)+selectedWeights+"\n\n"+config.substring(searchSection+1)
        }

        val target=File(directory,"config.toml")
        target.writeText(config)
        return directory
    }
}
/** Single consumer, bounded protocol queue, OS process isolation. A cancelled request destroys its process.
 * Therefore responses from one position can never leak into a later request.
 */
class EngineManager(private val context:Context):GomokuEngine {
    private val mutex=Mutex()
    private val installer=RuntimeInstaller(context)
    @Volatile private var process:Process?=null
    @Volatile private var writer:BufferedWriter?=null
    private var readerJob:Job?=null
    private var activeSpec:EngineSpec?=null
    private var activeRule:Rule?=null
    private var lines=Channel<String>(512)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private fun send(command:String) {
        AppLog.event("PROTOCOL","> $command")
        val w=writer ?: error("엔진이 실행 중이 아니에요.")
        synchronized(w) { w.write(command); w.newLine(); w.flush() }
    }
    private suspend fun next():String = lines.receiveCatching().getOrNull() ?: error("엔진이 예기치 않게 종료됐어요. 재시작해 주세요.")
    private suspend fun start(spec:EngineSpec,size:Int,rule:Rule) {
        if(process?.isAlive==true && activeSpec==spec && activeRule==rule) return
        closeProcess()
        installer.install()
        val dir=installer.config(spec,rule)
        val executable=spec.executable ?: File(context.applicationInfo.nativeLibraryDir,"librapfi.so").absolutePath
        check(File(executable).canExecute()) { "이 기기 ABI에서 엔진을 실행할 수 없어요." }
        lines=Channel(512); val queue=lines
        val p=ProcessBuilder(executable).directory(dir).redirectErrorStream(true).start(); process=p; writer=p.outputStream.bufferedWriter()
        readerJob=scope.launch {
            try {
                p.inputStream.bufferedReader().use { input ->
                    while(isActive) {
                        val line=input.readLine() ?: break
                        if(line.length>65536) { queue.send("ERROR Oversized engine output"); p.destroy(); break }
                        AppLog.event("PROTOCOL","< $line")
                        queue.send(line)
                    }
                }
            } catch(e:Exception) { if(isActive) AppLog.event("ENGINE",e.javaClass.simpleName) }
            finally { queue.close() }
        }
        withTimeout(30000) {
            send("ABOUT"); send("START $size")
            var identity=false
            while(true) {
                val l=next()
                if(l.contains("name=",true)) identity=true
                if(l.startsWith("ERROR")) error(l)
                if(l=="OK") { check(identity) { "엔진 ABOUT 응답이 없어요." }; break }
            }
        }
        activeSpec=spec
        activeRule=rule
        if(spec.rapfiExtensions) send("YXSHOWINFO")
        if(spec.rapfiExtensions) send("INFO PONDERING 0")
    }
    override suspend fun analyze(position:Position,config:AnalysisConfig,spec:EngineSpec,onUpdate:(Analysis)->Unit):Analysis = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(position.stones.size < position.size*position.size && position.winner()==0) { "종료된 위치는 분석할 수 없어요." }
            require(spec.rapfiExtensions || position.isAlternating()) { "이 엔진은 편집된 포지션을 지원하지 않아요." }
            require(spec.classical || position.rule==Rule.FREESTYLE || position.size==15) { "기본 Standard/Renju NNUE는 15×15 전용이에요." }
            require(spec.network=="default" || position.rule==Rule.FREESTYLE) { "가져온 network는 Freestyle에서 선택해 주세요." }
            try {
                start(spec,position.size,position.rule)
                val parser=ProtocolParser(position.size)
                send("INFO RULE ${position.rule.protocol}"); send("START ${position.size}")
                withTimeout(5000) { while(true) { val line=next(); if(line=="OK") break; if(line.startsWith("ERROR")) error(line) } }
                send("INFO TIMEOUT_TURN ${config.timeMs}")
                send("INFO TIMEOUT_MATCH 2147483647"); send("INFO TIME_LEFT 2147483647")
                if(spec.rapfiExtensions) {
                    send("INFO HASH_SIZE ${config.hashMb*1024}"); send("INFO THREAD_NUM ${config.threads}")
                    send("INFO MAX_DEPTH ${config.depth}"); send("INFO MAX_NODE ${config.nodes}")
                    send("INFO SHOW_DETAIL 2"); send("INFO USEDATABASE 0")
                }
                send(if(spec.rapfiExtensions) "YXBOARD" else "BOARD")
                positionPacket(position).forEach(::send); send("DONE")
                var forbidden=emptySet<Move>()
                if(spec.rapfiExtensions && position.rule==Rule.RENJU) {
                    send("YXSHOWFORBID")
                    withTimeout(5000) { while(true) { when(val e=parser.parse(next())) { is EngineEvent.Forbidden -> { forbidden=e.moves; break }; is EngineEvent.Error -> error(e.message); else -> {} } } }
                }
                if(spec.rapfiExtensions) send("YXNBEST ${config.multiPv}")
                val candidates=mutableMapOf<Int,Candidate>()
                var best:Move?=null
                withTimeout(config.timeMs+15000) {
                    while(true) {
                        when(val event=parser.parse(next())) {
                            is EngineEvent.Pv -> {
                                candidates[event.candidate.index]=event.candidate
                                onUpdate(Analysis(position.key(),spec,config,candidates.toSortedMap().values.toList(),null,forbidden,completed=false))
                            }
                            is EngineEvent.Best -> { best=event.move; break }
                            is EngineEvent.Error -> error(event.message)
                            is EngineEvent.Message -> if(!spec.classical && event.message.contains("disabled: no compatible weight")) error("NNUE 로드 실패. Classical 또는 기본 network를 선택해 주세요.")
                            else -> {}
                        }
                    }
                }
                Analysis(position.key(),spec,config,candidates.toSortedMap().values.toList(),best,forbidden)
            } catch(e:CancellationException) { closeProcess(); throw e }
              catch(e:Exception) { closeProcess(); throw IllegalStateException("엔진 분석 실패: ${e.message}",e) }
        }
    }
    override fun stop() { closeProcess() }
    private fun closeProcess() {
        process?.destroy(); process?.let { if(it.isAlive) it.destroyForcibly() }
        process=null; writer=null; activeSpec=null; activeRule=null; readerJob?.cancel(); readerJob=null; lines.close()
    }
    override fun close() { closeProcess() }
}
