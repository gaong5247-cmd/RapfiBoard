package app.rapfiboard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { val vm:AppViewModel=viewModel(); RapfiApp(vm) } }
}
val Mint=Color(0xFF81DDB5)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RapfiApp(vm:AppViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val colors=if(s.dark) darkColorScheme(primary=Mint,background=Color(0xFF101A1A),surface=Color(0xFF172424),surfaceVariant=Color(0xFF243332),onSurface=Color(0xFFF1F4EE),onBackground=Color(0xFFF1F4EE)) else lightColorScheme(primary=Color(0xFF17634D),background=Color(0xFFF5F5EE),surface=Color.White,surfaceVariant=Color(0xFFE4ECE5))
    MaterialTheme(colorScheme=colors) {
        Scaffold(topBar={ TopAppBar(title={ Column { Text("RapfiBoard",fontWeight=FontWeight.Bold); Text("YOUR GOMOKU STUDIO",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } },actions={ IconButton(onClick=vm::dark) { Icon(Icons.Outlined.DarkMode,"테마 전환") }; IconButton(onClick=vm::save) { Icon(Icons.Outlined.Save,"대국 저장") } }) },bottomBar={
            NavigationBar { val labels=listOf("Play","Analyze","Review","Database","Engines"); val icons=listOf(Icons.Outlined.SportsEsports,Icons.Outlined.Analytics,Icons.Outlined.AutoAwesome,Icons.Outlined.Storage,Icons.Outlined.Memory)
                labels.forEachIndexed { i,label -> NavigationBarItem(selected=s.tab==i,onClick={vm.tab(i)},icon={Icon(icons[i],label)},label={Text(label,style=MaterialTheme.typography.labelSmall)}) }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)
                if(s.warm) Text("기기가 뜨거워져 분석 예산을 줄였어요.",Modifier.padding(12.dp),color=MaterialTheme.colorScheme.tertiary)
                when(s.tab) { 0,1 -> BoardScreen(vm,s); 2 -> ReviewScreen(vm,s); 3 -> DatabaseScreen(vm,s); else -> EnginesScreen(vm,s) }
            }
        }
        s.error?.let { message -> AlertDialog(onDismissRequest={vm.reportError(null)},title={Text("확인이 필요해요")},text={Text(message)},confirmButton={TextButton(onClick={vm.reportError(null)}) { Text("확인") }},dismissButton={TextButton(onClick={vm.restart();vm.reportError(null)}) { Text("엔진 재시작") }}) }
    }
}
@Composable fun SectionLabel(title:String,subtitle:String?=null) { Column(Modifier.padding(vertical=8.dp)) { Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold); subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable fun StudioCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) { Card(modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)),shape=MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content) } }
