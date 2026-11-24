package com.healer.hyperos.swipegesture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.healer.hyperos.swipegesture.BuildConfig
import com.healer.hyperos.swipegesture.hook.SwipeGestureModuleEntry
import com.healer.hyperos.swipegesture.policy.OverlayMaterial
import com.healer.hyperos.swipegesture.policy.OverlayThemeMode
import com.healer.hyperos.swipegesture.policy.SwipeGestureSettings
import com.healer.hyperos.swipegesture.policy.SwitchMode
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val PROJECT_HOMEPAGE_URL = "https://github.com/Healer-01/HyperOS-swipe-gesture"
private const val MIUIX_PROJECT_URL = "https://github.com/compose-miuix-ui/miuix"
private const val DIAG_LOGCAT_COMMAND =
    "adb logcat -v time -s ${SwipeGestureModuleEntry.TAG}:V CoreBackPreview:V InputDispatcher:V"

private data class InstalledAppEntry(
    val packageName: String,
    val label: String,
)

@Composable
fun SwipeGestureScreen() {
    val settings by SettingsRepository.settings.collectAsState()
    val framework by SettingsRepository.framework.collectAsState()
    val context = LocalContext.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "侧滑快切",
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "status") {
                SmallTitle(text = "模块状态")
                ModuleStatusCard(framework = framework)
            }

            item(key = "gesture_trigger") {
                Spacer(modifier = Modifier.height(10.dp))
                SmallTitle(text = "手势与触发参数")
                GestureTriggerCard(settings = settings)
            }

            item(key = "overlay_appearance") {
                Spacer(modifier = Modifier.height(10.dp))
                SmallTitle(text = "悬浮菜单外观")
                OverlayAppearanceCard(settings = settings)
            }

            item(key = "blacklist") {
                Spacer(modifier = Modifier.height(10.dp))
                SmallTitle(text = "应用过滤")
                BlacklistEntryCard(
                    blacklistCount = settings.blacklistedPackages.size,
                    onClick = { showBlacklistDialog = true },
                )
            }

            item(key = "diagnostics") {
                Spacer(modifier = Modifier.height(10.dp))
                SmallTitle(text = "诊断与日志")
                DiagnosticsCard(
                    diagnosticTraceEnabled = settings.diagnosticTraceEnabled,
                    onToggleDiagnostic = { checked ->
                        SettingsRepository.update { it.copy(diagnosticTraceEnabled = checked) }
                    },
                    onCopyCommand = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("adb_diag", DIAG_LOGCAT_COMMAND))
                        Toast.makeText(context, "已复制只读诊断命令", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            item(key = "about") {
                Spacer(modifier = Modifier.height(10.dp))
                SmallTitle(text = "关于模块")
                AboutCard(
                    onOpenHomepage = { openExternalUrl(context, PROJECT_HOMEPAGE_URL) },
                    onOpenMiuix = { openExternalUrl(context, MIUIX_PROJECT_URL) },
                    onShowLicenses = { showLicenseDialog = true },
                )
            }
        }

        BlacklistPickerDialog(
            show = showBlacklistDialog,
            blacklistedPackages = settings.blacklistedPackages,
            onTogglePackage = { pkg, blocked ->
                SettingsRepository.update { current ->
                    val updated = current.blacklistedPackages.toMutableSet()
                    if (blocked) updated.add(pkg) else updated.remove(pkg)
                    current.copy(blacklistedPackages = updated)
                }
            },
            onDismiss = { showBlacklistDialog = false },
        )

        SuperDialog(
            show = showLicenseDialog,
            title = "开源与致谢",
            summary = "本模块专为 HyperOS 4（Android 17 / libxposed API 102）从零构建，感谢以下开源项目：\n" +
                "• miuix-kotlin-multiplatform (Apache-2.0)\n" +
                "• libxposed API & Service (Apache-2.0)\n" +
                "• 设计灵感参考自 hyper-gesture 项目",
            onDismissRequest = { showLicenseDialog = false },
        ) {
            TextButton(
                text = "关闭",
                onClick = { showLicenseDialog = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun ModuleStatusCard(framework: FrameworkSnapshot) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                framework.fullyScoped -> Color(0xFF2E7D32)
                                framework.connected -> Color(0xFFEF6C00)
                                else -> MiuixTheme.colorScheme.disabledOnSurface
                            },
                        ),
                )
                Text(
                    text = when {
                        framework.fullyScoped -> "LSPosed 已激活并就绪"
                        framework.connected -> "LSPosed 已连接，请补齐作用域"
                        else -> "未检测到 LSPosed 服务（离线预览模式）"
                    },
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = if (framework.connected) {
                    "框架：${framework.frameworkName} ${framework.frameworkVersion}（API ${framework.apiVersion}）"
                } else {
                    "请在 LSPosed 管理器启用本模块（要求 libxposed API >= ${FrameworkSnapshot.MIN_SUPPORTED_API}）并勾选系统框架与系统界面"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            if (framework.connected) {
                Text(
                    text = "作用域：system ${if (framework.systemFrameworkInScope) "✓" else "✗"}  |  " +
                        "${SwipeGestureModuleEntry.PKG_SYSTEM_UI} ${if (framework.systemUiInScope) "✓" else "✗"}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            if (framework.statusMessage.isNotBlank()) {
                Text(
                    text = framework.statusMessage,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            if (framework.connected && !framework.fullyScoped) {
                Spacer(modifier = Modifier.height(2.dp))
                TextButton(
                    text = "向 LSPosed 申请所需作用域",
                    onClick = { SettingsRepository.requestMissingScope() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestureTriggerCard(settings: SwipeGestureSettings) {
    val enabled = settings.enabled

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SuperSwitch(
            checked = enabled,
            onCheckedChange = { checked ->
                SettingsRepository.update { it.copy(enabled = checked) }
            },
            title = "启用侧滑悬停切应用",
            summary = "快速侧滑保持系统原生返回，滑过距离并停顿后接管并屏蔽返回",
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "切换模式",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    label = "多应用悬浮菜单",
                    selected = settings.switchMode == SwitchMode.MULTI_APP_MENU,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update { it.copy(switchMode = SwitchMode.MULTI_APP_MENU) }
                    },
                )
                ModeChip(
                    label = "仅切上一个应用",
                    selected = settings.switchMode == SwitchMode.QUICK_PREVIOUS,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update { it.copy(switchMode = SwitchMode.QUICK_PREVIOUS) }
                    },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SuperSwitch(
            checked = settings.leftEdgeEnabled,
            onCheckedChange = { checked ->
                SettingsRepository.update { it.copy(leftEdgeEnabled = checked) }
            },
            title = "启用左侧边缘触发",
            summary = "从左边缘向内滑并停顿，在屏幕右侧呼出菜单",
        )

        SuperSwitch(
            checked = settings.rightEdgeEnabled,
            onCheckedChange = { checked ->
                SettingsRepository.update { it.copy(rightEdgeEnabled = checked) }
            },
            title = "启用右侧边缘触发",
            summary = "从右边缘向内滑并停顿，在屏幕左侧呼出菜单",
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SliderBlock(
            title = "悬停触发时长",
            valueLabel = "${settings.pauseDurationMs} ms",
            value = settings.pauseDurationMs.toFloat(),
            valueRange = SwipeGestureSettings.MIN_PAUSE_DURATION_MS.toFloat()..
                SwipeGestureSettings.MAX_PAUSE_DURATION_MS.toFloat(),
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw / 10f).roundToInt() * 10
                SettingsRepository.update { it.copy(pauseDurationMs = snapped) }
            },
        )

        SliderBlock(
            title = "触发滑动距离（屏宽占比）",
            valueLabel = "${(settings.slideDistanceRatio * 100f).roundToInt()}%",
            value = settings.slideDistanceRatio,
            valueRange = SwipeGestureSettings.MIN_SLIDE_DISTANCE_RATIO..
                SwipeGestureSettings.MAX_SLIDE_DISTANCE_RATIO,
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw * 100f).roundToInt() / 100f
                SettingsRepository.update { it.copy(slideDistanceRatio = snapped) }
            },
        )

        SliderBlock(
            title = "触发区顶部边界（避开状态栏）",
            valueLabel = "${(settings.triggerTopRatio * 100f).roundToInt()}%",
            value = settings.triggerTopRatio,
            valueRange = SwipeGestureSettings.MIN_TRIGGER_TOP_RATIO..
                SwipeGestureSettings.MAX_TRIGGER_TOP_RATIO,
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw * 100f).roundToInt() / 100f
                SettingsRepository.update { it.copy(triggerTopRatio = snapped) }
            },
        )

        SliderBlock(
            title = "触发区底部边界（避开输入法区）",
            valueLabel = "${(settings.triggerBottomRatio * 100f).roundToInt()}%",
            value = settings.triggerBottomRatio,
            valueRange = SwipeGestureSettings.MIN_TRIGGER_BOTTOM_RATIO..
                SwipeGestureSettings.MAX_TRIGGER_BOTTOM_RATIO,
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw * 100f).roundToInt() / 100f
                SettingsRepository.update { it.copy(triggerBottomRatio = snapped) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayAppearanceCard(settings: SwipeGestureSettings) {
    val enabled = settings.enabled
    val isMenuMode = settings.switchMode == SwitchMode.MULTI_APP_MENU

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OppositeEdgePreviewCanvas(settings = settings)
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "背景材质",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    label = "柔光玻璃",
                    selected = settings.overlayMaterial == OverlayMaterial.SOFT_LIGHT_GLASS,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update {
                            it.copy(overlayMaterial = OverlayMaterial.SOFT_LIGHT_GLASS)
                        }
                    },
                )
                ModeChip(
                    label = "高级磨砂",
                    selected = settings.overlayMaterial == OverlayMaterial.FROSTED_BLUR,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update {
                            it.copy(overlayMaterial = OverlayMaterial.FROSTED_BLUR)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "外观深浅色",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    label = "跟随系统",
                    selected = settings.overlayThemeMode == OverlayThemeMode.FOLLOW_SYSTEM,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update {
                            it.copy(overlayThemeMode = OverlayThemeMode.FOLLOW_SYSTEM)
                        }
                    },
                )
                ModeChip(
                    label = "始终浅色",
                    selected = settings.overlayThemeMode == OverlayThemeMode.LIGHT,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update {
                            it.copy(overlayThemeMode = OverlayThemeMode.LIGHT)
                        }
                    },
                )
                ModeChip(
                    label = "始终深色",
                    selected = settings.overlayThemeMode == OverlayThemeMode.DARK,
                    enabled = enabled,
                    onClick = {
                        SettingsRepository.update {
                            it.copy(overlayThemeMode = OverlayThemeMode.DARK)
                        }
                    },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SliderBlock(
            title = "菜单最多显示应用数",
            valueLabel = "${settings.menuMaxCount} 个",
            value = settings.menuMaxCount.toFloat(),
            valueRange = SwipeGestureSettings.MIN_MENU_MAX_COUNT.toFloat()..
                SwipeGestureSettings.MAX_MENU_MAX_COUNT.toFloat(),
            enabled = enabled && isMenuMode,
            onValueChange = { raw ->
                SettingsRepository.update { it.copy(menuMaxCount = raw.roundToInt()) }
            },
        )

        SliderBlock(
            title = "上下滑动切项步距（屏高占比）",
            valueLabel = "${(settings.menuStepRatio * 100f).roundToInt()}%",
            value = settings.menuStepRatio,
            valueRange = SwipeGestureSettings.MIN_MENU_STEP_RATIO..
                SwipeGestureSettings.MAX_MENU_STEP_RATIO,
            enabled = enabled && isMenuMode,
            onValueChange = { raw ->
                val snapped = (raw * 200f).roundToInt() / 200f
                SettingsRepository.update { it.copy(menuStepRatio = snapped) }
            },
        )

        SliderBlock(
            title = "悬浮胶囊缩放比例",
            valueLabel = "${(settings.menuScale * 100f).roundToInt()}%",
            value = settings.menuScale,
            valueRange = SwipeGestureSettings.MIN_MENU_SCALE..SwipeGestureSettings.MAX_MENU_SCALE,
            enabled = enabled,
            onValueChange = { raw ->
                val snapped = (raw * 20f).roundToInt() / 20f
                SettingsRepository.update { it.copy(menuScale = snapped) }
            },
        )
    }
}

@Composable
private fun OppositeEdgePreviewCanvas(settings: SwipeGestureSettings) {
    val systemDark = isSystemInDarkTheme()
    val previewDark = when (settings.overlayThemeMode) {
        OverlayThemeMode.LIGHT -> false
        OverlayThemeMode.DARK -> true
        OverlayThemeMode.FOLLOW_SYSTEM -> systemDark
    }
    val isSoftGlass = settings.overlayMaterial == OverlayMaterial.SOFT_LIGHT_GLASS

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 0.75.dp,
                color = MiuixTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = if (previewDark) {
                        listOf(
                            Color(0xFF06080D),
                            Color(0xFF111827),
                            Color(0xFF1E293B),
                        )
                    } else {
                        listOf(
                            Color(0xFFF8FAFC),
                            Color(0xFFF1F5F9),
                            Color(0xFFE2E8F0),
                        )
                    },
                ),
            )

            // Active vertical edge bands reflecting left/right toggles & top/bottom ratios.
            val topY = size.height * settings.triggerTopRatio
            val bandHeight = (size.height * (settings.triggerBottomRatio - settings.triggerTopRatio))
                .coerceAtLeast(8f)
            val bandWidth = 6.dp.toPx()

            if (settings.leftEdgeEnabled) {
                drawRoundRect(
                    color = Color(0xFF3B82F6).copy(alpha = 0.85f),
                    topLeft = Offset(4.dp.toPx(), topY),
                    size = Size(bandWidth, bandHeight),
                    cornerRadius = CornerRadius(bandWidth, bandWidth),
                )
            }
            if (settings.rightEdgeEnabled) {
                drawRoundRect(
                    color = Color(0xFF3B82F6).copy(alpha = 0.85f),
                    topLeft = Offset(size.width - bandWidth - 4.dp.toPx(), topY),
                    size = Size(bandWidth, bandHeight),
                    cornerRadius = CornerRadius(bandWidth, bandWidth),
                )
            }

            // Simulated opposite-edge HyperOS 4 glass / frosted capsule preview on right side.
            val shownCount = if (settings.switchMode == SwitchMode.MULTI_APP_MENU) {
                settings.menuMaxCount.coerceAtMost(5)
            } else {
                1
            }
            val previewScale = settings.menuScale.coerceIn(
                SwipeGestureSettings.MIN_MENU_SCALE,
                SwipeGestureSettings.MAX_MENU_SCALE,
            )
            val slotSize = 16.dp.toPx() * previewScale
            val gap = 3.dp.toPx() * previewScale
            val pad = 5.dp.toPx() * previewScale
            val capsuleW = slotSize + pad * 2
            val capsuleH = pad * 2 + shownCount * slotSize + (shownCount - 1) * gap
            val capsuleRadius = capsuleW * 0.368f
            val capsuleX = size.width - capsuleW - 18.dp.toPx()
            val capsuleY = (size.height - capsuleH) / 2f

            // Simulated floating drop shadow halo.
            val shadowColor = if (previewDark) Color.Black else Color(0xFF0F172A)
            drawRoundRect(
                color = shadowColor.copy(alpha = if (previewDark) 0.42f else 0.14f),
                topLeft = Offset(capsuleX - 2.dp.toPx(), capsuleY + 3.dp.toPx()),
                size = Size(capsuleW + 4.dp.toPx(), capsuleH + 4.dp.toPx()),
                cornerRadius = CornerRadius(capsuleRadius + 2.dp.toPx(), capsuleRadius + 2.dp.toPx()),
            )
            drawRoundRect(
                color = shadowColor.copy(alpha = if (previewDark) 0.24f else 0.08f),
                topLeft = Offset(capsuleX - 5.dp.toPx(), capsuleY + 4.dp.toPx()),
                size = Size(capsuleW + 10.dp.toPx(), capsuleH + 8.dp.toPx()),
                cornerRadius = CornerRadius(capsuleRadius + 5.dp.toPx(), capsuleRadius + 5.dp.toPx()),
            )

            // Frosted pearl-silver (Light) / luminous titanium-slate (Dark) glass body.
            val capsuleFillBrush = Brush.verticalGradient(
                colors = when {
                    previewDark && isSoftGlass -> listOf(Color(0xD2525E74), Color(0xC43B4457), Color(0xCE475267))
                    previewDark -> listOf(Color(0xE04C566A), Color(0xDC3C4454))
                    isSoftGlass -> listOf(Color(0xD8DCE4F3), Color(0xCCD2DCF0), Color(0xD6DAE3F2))
                    else -> listOf(Color(0xE6CFD9EA), Color(0xE2C7D2E6))
                },
                startY = capsuleY,
                endY = capsuleY + capsuleH,
            )
            drawRoundRect(
                brush = capsuleFillBrush,
                topLeft = Offset(capsuleX, capsuleY),
                size = Size(capsuleW, capsuleH),
                cornerRadius = CornerRadius(capsuleRadius, capsuleRadius),
            )

            // Inner bright specular rim + outer crisp framing hairline.
            val innerInset = 1.2.dp.toPx()
            val innerRadius = (capsuleRadius - innerInset).coerceAtLeast(0f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = if (previewDark) {
                        if (isSoftGlass) {
                            listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.50f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.24f))
                        }
                    } else {
                        if (isSoftGlass) {
                            listOf(Color.White.copy(alpha = 0.94f), Color.White.copy(alpha = 0.52f), Color.White.copy(alpha = 0.78f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.28f))
                        }
                    },
                    startY = capsuleY,
                    endY = capsuleY + capsuleH,
                ),
                topLeft = Offset(capsuleX + innerInset, capsuleY + innerInset),
                size = Size(capsuleW - innerInset * 2, capsuleH - innerInset * 2),
                cornerRadius = CornerRadius(innerRadius, innerRadius),
                style = Stroke(width = 1.1.dp.toPx()),
            )
            drawRoundRect(
                color = if (previewDark) Color.Black.copy(alpha = 0.60f) else Color(0xFF0F172A).copy(alpha = 0.28f),
                topLeft = Offset(capsuleX, capsuleY),
                size = Size(capsuleW, capsuleH),
                cornerRadius = CornerRadius(capsuleRadius, capsuleRadius),
                style = Stroke(width = 1.dp.toPx()),
            )

            val slotColors = listOf(
                Color(0xFF38BDF8),
                Color(0xFF34D399),
                Color(0xFFA78BFA),
                Color(0xFFFBBF24),
                Color(0xFFF87171),
            )
            for (i in 0 until shownCount) {
                val cx = capsuleX + capsuleW / 2f
                val cy = capsuleY + pad + slotSize / 2f + i * (slotSize + gap)
                if (i == 0) {
                    drawCircle(
                        color = if (previewDark) Color(0xFF4DA3FF) else Color(0xFF1872FF),
                        radius = slotSize * 0.50f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                drawCircle(
                    color = slotColors[i % slotColors.size],
                    radius = slotSize * 0.36f,
                    center = Offset(cx, cy),
                )
            }
        }

        val materialLabel = if (isSoftGlass) "柔光玻璃" else "高级磨砂"
        val themeLabel = when (settings.overlayThemeMode) {
            OverlayThemeMode.FOLLOW_SYSTEM -> "跟随系统"
            OverlayThemeMode.LIGHT -> "浅色"
            OverlayThemeMode.DARK -> "深色"
        }
        Text(
            text = if (settings.switchMode == SwitchMode.MULTI_APP_MENU) {
                "实时预览 · $materialLabel（$themeLabel）"
            } else {
                "实时预览 · 单切模式 · $materialLabel（$themeLabel）"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (previewDark) Color.White.copy(alpha = 0.88f) else Color(0xFF1E293B).copy(alpha = 0.88f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BlacklistEntryCard(
    blacklistCount: Int,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SuperArrow(
            title = "切换列表过滤黑名单",
            summary = if (blacklistCount == 0) {
                "未排除任何应用（桌面与小窗默认自动排除）"
            } else {
                "已排除 $blacklistCount 个应用，不出现在侧滑切换列表中"
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun DiagnosticsCard(
    diagnosticTraceEnabled: Boolean,
    onToggleDiagnostic: (Boolean) -> Unit,
    onCopyCommand: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SuperSwitch(
            checked = diagnosticTraceEnabled,
            onCheckedChange = onToggleDiagnostic,
            title = "启用详细手势诊断日志",
            summary = "仅输出只读日志到 logcat（Tag: ${SwipeGestureModuleEntry.TAG}），方便定位返回穿透",
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SuperArrow(
            title = "复制只读 ADB 诊断命令",
            summary = "查看侧滑状态机、返回拦截与 SystemUI 悬浮通道日志",
            onClick = onCopyCommand,
        )
    }
}

@Composable
private fun AboutCard(
    onOpenHomepage: () -> Unit,
    onOpenMiuix: () -> Unit,
    onShowLicenses: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SuperArrow(
            title = "项目主页（GitHub）",
            summary = "Healer-01/HyperOS-swipe-gesture · v${BuildConfig.VERSION_NAME}",
            onClick = onOpenHomepage,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SuperArrow(
            title = "界面组件：miuix-kotlin-multiplatform",
            summary = "HyperOS 原生风格 Jetpack Compose 组件库",
            onClick = onOpenMiuix,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SuperArrow(
            title = "开源许可与致谢",
            summary = "查看第三方协议及参考说明",
            onClick = onShowLicenses,
        )
    }
}

@Composable
private fun BlacklistPickerDialog(
    show: Boolean,
    blacklistedPackages: Set<String>,
    onTogglePackage: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(show) {
        if (show && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcherIntent, 0)
                    .mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                        InstalledAppEntry(packageName = pkg, label = label)
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    SuperDialog(
        show = show,
        title = "切换列表黑名单",
        summary = "勾选的应用将被隐藏，不会出现在侧滑切换菜单中",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = apps, key = { it.packageName }) { app ->
                val checked = app.packageName in blacklistedPackages
                SuperSwitch(
                    checked = checked,
                    onCheckedChange = { next -> onTogglePackage(app.packageName, next) },
                    title = app.label,
                    summary = app.packageName,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(
            text = "完成",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun SliderBlock(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueLabel,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = when {
        !enabled && selected -> MiuixTheme.colorScheme.disabledPrimary
        !enabled -> MiuixTheme.colorScheme.disabledSecondaryVariant
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.secondaryVariant
    }
    val contentColor = when {
        !enabled && selected -> MiuixTheme.colorScheme.disabledOnPrimary
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        selected -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSecondaryVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "无法打开链接：$url", Toast.LENGTH_SHORT).show()
    }
}
