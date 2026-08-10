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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.busung.s25uroot.ui.theme.RootMyGalaxyTheme
import kotlinx.coroutines.launch
import java.util.UUID

// 常量定义
private const val ROOT_GITHUB = "https://github.com/BuSung-dev/Root-My-Galaxy"
private const val KSU_DOWNLOAD = "https://github.com/tiann/KernelSU/releases/download/v3.2.5/KernelSU_v3.2.5_32525-release.apk"

// TargetProfile 扩展属性
val TargetProfile.kernelRelease get() = kernelVersions.firstOrNull() ?: "unknown"
val TargetProfile.buildDisplay get() = displayName
fun TargetProfile.matchesKernel(snap: DeviceSnapshot) = snap.kernelRelease in kernelVersions

// 页面导航枚举
enum class AppPage(@androidx.annotation.StringRes val label: Int, val icon: ImageVector) {
    Overview(R.string.nav_overview, Icons.Rounded.Home),
    History(R.string.nav_history, Icons.Rounded.History),
    Settings(R.string.nav_settings, Icons.Rounded.Settings)
}

// 设备兼容警告类型
enum class CompatibilityWarning {
    Kernel, Build
}

class MainActivity : ComponentActivity() {
    private val installVM by viewModels<InstallViewModel>()
    private var resumedOnce = false
    private var accent by mutableStateOf(AccentColor.Dynamic)
    private var theme by mutableStateOf(AppThemeMode.System)
    private var advMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 读取本地偏好设置
        accent = AppPreferences.accentColor(this)
        theme = AppPreferences.themeMode(this)
        advMode = AppPreferences.advancedMode(this)

        setContent {
            RootMyGalaxyTheme(accent, theme) {
                RootApp(
                    installVM = installVM,
                    accent = accent,
                    theme = theme,
                    advMode = advMode,
                    onAccent = { newColor ->
                        AppPreferences.setAccentColor(this, newColor)
                        accent = newColor
                    },
                    onTheme = { newMode ->
                        AppPreferences.setThemeMode(this, newMode)
                        theme = newMode
                    },
                    onAdv = { newAdv ->
                        AppPreferences.setAdvancedMode(this, newAdv)
                        advMode = newAdv
                    },
                    openInstall = { pid, mgr ->
                        Intent(this, InstallActivity::class.java).apply {
                            putExtra(InstallActivity.EXTRA_INSTALL_REQUEST_ID, UUID.randomUUID().toString())
                            pid?.let { putExtra(InstallActivity.EXTRA_PROFILE_ID, it) }
                            putExtra(InstallActivity.EXTRA_MANAGER_KEY, mgr)
                            startActivity(this)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce) {
            installVM.refresh()
        } else {
            resumedOnce = true
        }
    }
}

@Composable
private fun RootApp(
    installVM: InstallViewModel,
    accent: AccentColor,
    theme: AppThemeMode,
    advMode: Boolean,
    onAccent: (AccentColor) -> Unit,
    onTheme: (AppThemeMode) -> Unit,
    onAdv: (Boolean) -> Unit,
    openInstall: (String?, String) -> Unit
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

    // 机型选择弹窗回调
    if (showPicker) {
        TargetSelectionSheet(
            device = dev,
            catalog = catalog,
            onDismiss = { showPicker = false },
            refreshCatalog = installVM::loadTargetCatalog
        ) { prof, mgr ->
            selProfile = prof
            selMgr = mgr
            showPicker = false

            // 检测兼容警告
            warn = when {
                !prof.matchesKernel(dev) -> CompatibilityWarning.Kernel
                prof.buildDisplay != dev.buildId -> CompatibilityWarning.Build
                else -> null
            }
            // 无警告直接弹出确认安装
            if (warn == null) showConfirm = true
        }
    }

    // 兼容警告弹窗
    warn?.let { warningType ->
        val profile = selProfile ?: return@let
        AlertDialog(
            onDismissRequest = {
                warn = null
                showPicker = true
            },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = {
                Text(
                    text = stringResource(
                        if (warningType == CompatibilityWarning.Kernel)
                            R.string.kernel_mismatch_title
                        else R.string.build_mismatch_title
                    )
                )
            },
            text = {
                val contentText = when (warningType) {
                    CompatibilityWarning.Kernel -> stringResource(
                        R.string.kernel_mismatch_body,
                        dev.kernelBuildVersion,
                        profile.kernelBuildVersion
                    )
                    CompatibilityWarning.Build -> stringResource(
                        R.string.build_mismatch_body,
                        dev.buildId,
                        profile.buildDisplay
                    )
                }
                Text(contentText)
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    // 双重警告递进判断
                    val newWarn = when {
                        warningType == CompatibilityWarning.Kernel && profile.buildDisplay != dev.buildId -> CompatibilityWarning.Build
                        else -> null
                    }
                    warn = newWarn
                    if (warn == null) showConfirm = true
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    warn = null
                    showPicker = true
                }) {
                    Text(stringResource(R.string.action_back))
                }
            }
        )
    }

    // 安装确认弹窗
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
            title = { Text(stringResource(R.string.install_confirm_title)) },
            text = { Text(stringResource(R.string.install_confirm_body)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showConfirm = false
                    openInstall(selProfile?.profileId, selMgr)
                    // 重置选择状态
                    selProfile = null
                    selMgr = "sukisu"
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(72.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                AppPage.entries.forEach { navPage ->
                    NavigationBarItem(
                        selected = navPage == page,
                        onClick = { page = navPage },
                        icon = { Icon(navPage.icon, contentDescription = null) },
                        label = { Text(stringResource(navPage.label)) }
                    )
                }
            }
        }
    ) { paddingValues ->
        AnimatedContent(targetState = page, label = "page_switch") { currentPage ->
            when (currentPage) {
                AppPage.Overview -> OverviewPage(
                    pad = paddingValues,
                    dev = dev,
                    state = state
                ) {
                    selProfile = null
                    selMgr = "sukisu"
                    if (advMode) {
                        showPicker = true
                        installVM.loadTargetCatalog()
                    } else {
                        showConfirm = true
                    }
                }
                AppPage.History -> HistoryPage(
                    pad = paddingValues,
                    history = history
                )
                AppPage.Settings -> SettingsPage(
                    pad = paddingValues,
                    accent = accent,
                    theme = theme,
                    advMode = advMode,
                    onAccent = onAccent,
                    onTheme = onTheme,
                    onAdv = onAdv
                )
            }
        }
    }
}

/**
 * 首页占位Composable，自行填充UI逻辑
 */
@Composable
private fun OverviewPage(
    pad: PaddingValues,
    dev: DeviceSnapshot,
    state: InstallUiState,
    onClick: () -> Unit
) {
    // 此处填充你的首页布局、设备信息展示、安装启动按钮等UI代码
}

// ===================== 以下为依赖数据类/ViewModel 声明占位（你项目原有）=====================
// 下述类为你项目内部实体，无需实现，仅用于编译不报错
interface TargetProfile {
    val kernelVersions: List<String>
    val displayName: String
    val kernelBuildVersion: String
    val profileId: String
    val buildDisplay: String
}

object DeviceSnapshot {
    fun current(): DeviceSnapshot = object : DeviceSnapshot {
        override val kernelRelease = ""
        override val kernelBuildVersion = ""
        override val buildId = ""
    }
}

interface DeviceSnapshot {
    val kernelRelease: String
    val kernelBuildVersion: String
    val buildId: String
}

enum class AccentColor { Dynamic }
enum class AppThemeMode { System }
object AppPreferences {
    fun accentColor(activity: MainActivity): AccentColor = AccentColor.Dynamic
    fun setAccentColor(activity: MainActivity, color: AccentColor) = Unit
    fun themeMode(activity: MainActivity): AppThemeMode = AppThemeMode.System
    fun setThemeMode(activity: MainActivity, mode: AppThemeMode) = Unit
    fun advancedMode(activity: MainActivity): Boolean = false
    fun setAdvancedMode(activity: MainActivity, enable: Boolean) = Unit
}

class InstallViewModel {
    val state = kotlinx.flow.MutableStateFlow(object : InstallUiState {})
    val history = kotlinx.flow.MutableStateFlow(emptyList<Any>())
    val targetCatalog = kotlinx.flow.MutableStateFlow(emptyList<TargetProfile>())
    fun refresh() = Unit
    fun loadTargetCatalog() = Unit
}

interface InstallUiState
const val EXTRA_INSTALL_REQUEST_ID = "install_req_id"
const val EXTRA_PROFILE_ID = "profile_id"
const val EXTRA_MANAGER_KEY = "manager_key"
class InstallActivity : ComponentActivity()

@Composable
fun TargetSelectionSheet(
    device: DeviceSnapshot,
    catalog: List<TargetProfile>,
    onDismiss: () -> Unit,
    refreshCatalog: () -> Unit,
    onSelect: (TargetProfile, String) -> Unit
) = Unit

@Composable
fun HistoryPage(pad: PaddingValues, history: List<Any>) = Unit

@Composable
fun SettingsPage(
    pad: PaddingValues,
    accent: AccentColor,
    theme: AppThemeMode,
    advMode: Boolean,
    onAccent: (AccentColor) -> Unit,
    onTheme: (AppThemeMode) -> Unit,
    onAdv: (Boolean) -> Unit
) = Unit
