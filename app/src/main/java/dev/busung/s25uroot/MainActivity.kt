package dev.busung.s25uroot
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.busung.s25uroot.ui.theme.RootMyGalaxyTheme
import kotlinx.coroutines.launch
import java.util.UUID

// 常量、扩展一次性压缩
private const val ROOT_GITHUB = "https://github.com/BuSung-dev/Root-My-Galaxy"
private const val KSU_DOWNLOAD = "https://github.com/tiann/KernelSU/releases/download/v3.2.5/KernelSU_v3.2.5_32525-release.apk"

val TargetProfile.kernelRelease get() = kernelVersions.firstOrNull() ?: "unknown"
val TargetProfile.buildDisplay get() = displayName
fun TargetProfile.matchesKernel(snap: DeviceSnapshot) = snap.kernelRelease in kernelVersions

enum class AppPage(@StringRes val label: Int, val icon: ImageVector) {
    Overview(R.string.nav_overview, Icons.Rounded.Home),
    History(R.string.nav_history, Icons.Rounded.History),
    Settings(R.string.nav_settings, Icons.Rounded.Settings)
}
enum class CompatibilityWarning { Kernel, Build }

class MainActivity : ComponentActivity() {
    private val installVM by viewModels<InstallViewModel>()
    private var resumedOnce = false
    private var accent by mutableStateOf(AccentColor.Dynamic)
    private var theme by mutableStateOf(AppThemeMode.System)
    private var advMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accent = AppPreferences.accentColor(this)
        theme = AppPreferences.themeMode(this)
        advMode = AppPreferences.advancedMode(this)
        setContent {
            RootMyGalaxyTheme(accent, theme) {
                RootApp(installVM, accent, theme, advMode,
                    onAccent = { AppPreferences.setAccentColor(this,it);accent=it },
                    onTheme = { AppPreferences.setThemeMode(this,it);theme=it },
                    onAdv = { AppPreferences.setAdvancedMode(this,it);advMode=it },
                    openInstall = { pid,mgr ->
                        Intent(this,InstallActivity::class.java).apply {
                            putExtra(InstallActivity.EXTRA_INSTALL_REQUEST_ID, UUID.randomUUID().toString())
                            pid?.let { putExtra(InstallActivity.EXTRA_PROFILE_ID,it) }
                            putExtra(InstallActivity.EXTRA_MANAGER_KEY,mgr)
                            startActivity(this)
                        }
                    }
                )
            }
        }
    }
    override fun onResume() { super.onResume();if(resumedOnce) installVM.refresh() else resumedOnce=true }
}

@Composable
private fun RootApp(
    installVM: InstallViewModel, accent: AccentColor, theme: AppThemeMode, advMode: Boolean,
    onAccent: (AccentColor)->Unit, onTheme: (AppThemeMode)->Unit, onAdv: (Boolean)->Unit,
    openInstall: (String?,String)->Unit
) {
    val state by installVM.state.collectAsStateWithLifecycle()
    val history by installVM.history.collectAsStateWithLifecycle()
    val catalog by installVM.targetCatalog.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(AppPage.Overview) }
    var showPicker by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var selProfile by remember { mutableStateOf<TargetProfile?>(null) }
    var selMgr by remember { mutableStateOf("sukisu") }
    var warn by remember { mutableStateOf<CompatibilityWarning?>(null) }
    val dev = remember { DeviceSnapshot.current() }

    if(showPicker) TargetSelectionSheet(dev,catalog,{showPicker=false},installVM::loadTargetCatalog){ prof,mgr->
        selProfile=prof;selMgr=mgr;showPicker=false
        warn = when{
            !prof.matchesKernel(dev)->CompatibilityWarning.Kernel
            prof.buildDisplay != dev.buildId->CompatibilityWarning.Build
            else->null
        }
        if(warn==null) showConfirm=true
    }

    warn?.let { w->
        AlertDialog(onDismissRequest={warn=null;showPicker=true},
            icon={Icon(Icons.Rounded.Warning,null)},
            title={Text(stringResource(if(w==CompatibilityWarning.Kernel) R.string.kernel_mismatch_title else R.string.build_mismatch_title))},
            text={Text(if(w==CompatibilityWarning.Kernel) stringResource(R.string.kernel_mismatch_body,dev.kernelBuildVersion,selProfile!!.kernelBuildVersion)
                else stringResource(R.string.build_mismatch_body,dev.buildId,selProfile!!.buildDisplay))},
            confirmButton={FilledTonalButton({
                warn = if(w==CompatibilityWarning.Kernel && selProfile!!.buildDisplay!=dev.buildId) CompatibilityWarning.Build else null
                if(warn==null) showConfirm=true
            }){Text(stringResource(R.string.action_continue))}},
            dismissButton={TextButton({warn=null;showPicker=true}){Text(stringResource(R.string.action_back))}}
        )
    }

    if(showConfirm) AlertDialog(onDismissRequest={showConfirm=false},
        icon={Icon(Icons.Rounded.Security,null)},
        title={Text(stringResource(R.string.install_confirm_title))},
        text={Text(stringResource(R.string.install_confirm_body))},
        confirmButton={FilledTonalButton({
            showConfirm=false;openInstall(selProfile?.profileId,selMgr);selProfile=null;selMgr="sukisu"
        }){Text(stringResource(R.string.action_confirm))}},
        dismissButton={TextButton({showConfirm=false}){Text(stringResource(R.string.action_cancel))}}
    )

    Scaffold(bottomBar={NavigationBar(modifier=Modifier.height(72.dp),containerColor=MaterialTheme.colorScheme.surfaceContainer){
        AppPage.entries.forEach { p->
            NavigationBarItem(p==page,{page=p},icon={Icon(p.icon,null)},label={Text(stringResource(p.label))})
        }
    }}){pad->
        AnimatedContent(page){p->
            when(p){
                AppPage.Overview->OverviewPage(pad,dev,state){
                    selProfile=null;selMgr="sukisu"
                    if(advMode){showPicker=true;installVM.loadTargetCatalog()}else showConfirm=true
                }
                AppPage.History->HistoryPage(pad,history)
                AppPage.Settings->SettingsPage(pad,accent,theme,advMode,onAccent,onTheme,onAdv)
            }
        }
    }
}

@Composable private fun OverviewPage(pad: PaddingValues, dev: DeviceSnapshot, state: InstallUiState, onClick: ()->Unit) {
    // 页面内部UI自行填充，此处仅保留入口，无冗余封装
}
