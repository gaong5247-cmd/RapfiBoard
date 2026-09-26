package app.rapfiboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun DatabaseScreen(vm:AppViewModel,s:AppState) {
    val games by vm.games.collectAsStateWithLifecycle();val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var query by remember { mutableStateOf("") };var byPosition by remember { mutableStateOf(false) };var positionIds by remember { mutableStateOf(emptySet<Long>()) }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) scope.launch { try { val count=withContext(Dispatchers.IO) { val text=context.contentResolver.openInputStream(uri)!!.use { input -> val bytes=input.readLimited(20_000_001); require(bytes.size<=20_000_000); bytes.toString(Charsets.UTF_8) }; vm.database.import(text) };vm.reportError("${count}개 대국을 가져왔어요. 중복 대국은 제외했어요.") } catch(e:Exception) { vm.reportError("가져오기 실패: ${e.message}") } } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) scope.launch { try { withContext(Dispatchers.IO) { val data=vm.database.export();context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(data) } } } catch(e:Exception) { vm.reportError("내보내기 실패: ${e.message}") } } }
    val exportGame=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if(uri!=null) scope.launch { try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(s.position.encode()) } } } catch(e:Exception) { vm.reportError(e.message) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SectionLabel("Your game library","기록을 모으고, 같은 위치의 수를 비교하세요.")
        StudioCard {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={import.launch(arrayOf("*/*"))}) {Text("Import")};OutlinedButton(onClick={export.launch("rapfiboard-database.json")}){Text("DB export")};TextButton(onClick={exportGame.launch("game.rbf")}){Text("Game export")} }
            Text("RBF1 대국 · RapfiBoard JSON 데이터베이스\n파일 선택기를 통해 저장·공유할 수 있어요.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(query,{query=it},label={Text("Search games")},modifier=Modifier.fillMaxWidth())
            FilterChip(selected=byPosition,onClick={ byPosition=!byPosition; if(byPosition) scope.launch { try { positionIds=vm.database.dao.positionGames(s.position.key()).map { it.id }.toSet() } catch(e:Exception) { vm.reportError(e.message) } } },label={Text("현재 포지션으로 검색")})
        }
        if(s.stats.isNotEmpty()) StudioCard {
            SectionLabel("Opening explorer","현재 위치에서 실제 저장된 대국 통계")
            s.stats.forEach { st -> val known=st.wins+st.draws+st.losses;Text("${Move(st.x,st.y).label(s.position.size)}  ·  ${st.games} games\nW ${if(known>0) percent(st.wins.toDouble()/known) else "—"}  D ${if(known>0) percent(st.draws.toDouble()/known) else "—"}  L ${if(known>0) percent(st.losses.toDouble()/known) else "—"}") }
        }
        val filtered=games.filter { it.title.contains(query,true) && (!byPosition || it.id in positionIds) }
        if(filtered.isEmpty()) Text("아직 저장된 대국이 없어요. 판 위에서 Save를 누르거나 대국 파일을 가져오세요.",Modifier.padding(16.dp))
        filtered.forEach { g -> StudioCard {
            Text(g.title,fontWeight=FontWeight.SemiBold);Text("${if(g.result<0) "미완료 / 결과 미상" else if(g.result==0) "Draw" else if(g.result==1) "Black wins" else "White wins"} · #${g.id}",style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick={vm.load(Position.decode(g.notation))}){Text("Open")};TextButton(onClick={vm.favorite(g)}){Text(if(g.favorite) "★ Favorite" else "☆ Favorite")} }
        } }
        if(marks.isNotEmpty()) SectionLabel("Bookmarked positions")
        marks.forEach { b -> StudioCard { Text(b.comment);TextButton(onClick={vm.load(Position.decode(b.notation))}){Text("Open position")} } }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun EnginesScreen(vm:AppViewModel,s:AppState) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val benches by vm.benchmarks.collectAsStateWithLifecycle()
    var showLicense by remember { mutableStateOf(false) };var license by remember { mutableStateOf("") }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) scope.launch { try { withContext(Dispatchers.IO) { val bytes=context.contentResolver.openInputStream(uri)!!.use { it.readLimited(50_000_001) };vm.importNetwork(bytes) } } catch(e:Exception) { vm.reportError(e.message) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SectionLabel("Engine room","On-device. Private. Ready when you are.")
        StudioCard {
            Text("RAPFI",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            Text("Mix9SVQ · Alpha-Beta · 3c94c2a",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!s.engine.classical,onClick={vm.chooseEngine(false)},label={Text("NNUE")});FilterChip(selected=s.engine.classical,onClick={vm.chooseEngine(true)},label={Text("Classical")}) }
            Text("Piskvork + 검증된 Yixin / Rapfi 확장\n기본 network: Freestyle · Standard 15 · Renju 15",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=vm::restart){Text("Restart engine")}
        }
        StudioCard {
            SectionLabel("Performance")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Eco" to AnalysisConfig(timeMs=700,nodes=50000,multiPv=1,threads=1,hashMb=16),"Balanced" to AnalysisConfig(),"Maximum" to AnalysisConfig(timeMs=5000,nodes=1000000,multiPv=5,threads=minOf(6,Runtime.getRuntime().availableProcessors()),hashMb=64)).forEach { (label,c) -> OutlinedButton(onClick={vm.config(c)}){Text(label)} } }
            Text("Custom threads: ${s.config.threads}");Slider(s.config.threads.toFloat(),{vm.config(s.config.copy(threads=it.toInt()))},valueRange=1f..8f,steps=6)
            Text("MultiPV: ${s.config.multiPv}");Slider(s.config.multiPv.toFloat(),{vm.config(s.config.copy(multiPv=it.toInt()))},valueRange=1f..10f,steps=8)
            Text("Time: ${s.config.timeMs} ms");Slider(s.config.timeMs.toFloat(),{vm.config(s.config.copy(timeMs=it.toLong()))},valueRange=300f..10000f)
            Text("Hash: ${s.config.hashMb} MB · Node limit ${s.config.nodes}",style=MaterialTheme.typography.bodySmall)
            Text("과열 시 자동으로 thread와 분석 예산을 낮춥니다.",style=MaterialTheme.typography.bodySmall)
        }
        StudioCard {
            SectionLabel("Network manager","파일 SHA-256 검증 · 실제 로드 테스트")
            FilterChip(selected=s.engine.network=="default",onClick={vm.chooseNetwork("default")},label={Text("Bundled official networks")})
            TextButton(onClick={import.launch(arrayOf("*/*"))},enabled=!s.busy){Text("Import Freestyle mix9svq (.bin.lz4)")}
            Text("가져온 모델은 Freestyle용 mix9svq만 지원해요. Standard/Renju는 기본 모델을 사용하세요.",style=MaterialTheme.typography.bodySmall)
            s.networks.forEach { hash -> FilterChip(selected=s.engine.network==hash,onClick={vm.chooseNetwork(hash)},label={Text(hash.take(20)+"…")}) }
            Text("Active SHA: ${s.engine.network}",style=MaterialTheme.typography.bodySmall)
        }
        StudioCard {
            SectionLabel("Measure & compare")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick=vm::benchmark,enabled=!s.busy){Text("Benchmark")};OutlinedButton(onClick=vm::match,enabled=!s.busy){Text("NNUE vs Classical")} }
            if(s.busy) TextButton(onClick=vm::stop){Text("Stop")}
            benches.forEach { b -> Text("${b.evaluator} · ${b.threads} threads\n${b.nodes} nodes · ${b.elapsed} ms · ${b.nps} NPS\n${b.device}",style=MaterialTheme.typography.bodySmall);HorizontalDivider() }
        }
        StudioCard {
            SectionLabel("External engine APK", "설치된 엔진 패키지의 Piskvork 실행 파일")
            var pkg by remember { mutableStateOf("") }; var lib by remember { mutableStateOf("libpbrain.so") }
            OutlinedTextField(pkg,{pkg=it},label={Text("Package name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(lib,{lib=it},label={Text("Native executable (lib…so)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={vm.registerExternal(pkg,lib)},enabled=pkg.isNotBlank()&&!s.busy){Text("Validate & select")}
            Text("기본 Piskvork 엔진은 best move만 표시할 수 있어요. MultiPV·Review는 검증된 Rapfi 확장에서 제공해요. APK 설치는 Android에서 직접 진행해야 합니다.",style=MaterialTheme.typography.bodySmall)
            Text("Active: ${s.engine.name}",style=MaterialTheme.typography.bodySmall)
        }
        StudioCard {
            SectionLabel("Licenses & provenance")
            Text("Rapfi / RapfiBoard: GPL-3.0-or-later\nOfficial network weights: CC0\nYixinBoard의 코드·이미지는 포함하지 않았습니다.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={scope.launch { license=withContext(Dispatchers.IO){context.assets.open("licenses.txt").bufferedReader().use {it.readText()}};showLicense=true }}){Text("Open license texts")}
        }
    }
    if(showLicense) AlertDialog(onDismissRequest={showLicense=false},title={Text("Open source licenses")},text={Text(license,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},confirmButton={TextButton(onClick={showLicense=false}){Text("Close")}})
}

private fun java.io.InputStream.readLimited(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(out.size()<limit) { val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0) break;out.write(buffer,0,n) }
    return out.toByteArray()
}
