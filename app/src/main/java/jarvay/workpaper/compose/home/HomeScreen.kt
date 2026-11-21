package jarvay.workpaper.compose.home

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import jarvay.workpaper.R
import jarvay.workpaper.compose.components.LocalSimpleSnackbar
import jarvay.workpaper.compose.components.SimpleDialog
import jarvay.workpaper.data.album.Album
import jarvay.workpaper.glance.WidgetColorScheme
import jarvay.workpaper.others.GestureEvent
import jarvay.workpaper.others.MAX_BLUR_RADIUS
import jarvay.workpaper.others.ReaderPanXMode
import jarvay.workpaper.others.ReaderPanYMode
import jarvay.workpaper.others.ReaderReadingDirection
import jarvay.workpaper.others.ReaderUndersizeVAlign
import jarvay.workpaper.others.WallpaperScaleType
import jarvay.workpaper.others.getOneWallpaperInDir
import jarvay.workpaper.others.requestAlarmPermission
import jarvay.workpaper.others.requestNotificationPermission
import jarvay.workpaper.ui.theme.SCREEN_HORIZONTAL_PADDING
import jarvay.workpaper.viewModel.HomeScreenViewModel
import jarvay.workpaper.viewModel.SimpleWallpaperViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 整个 App 只有这一个页面：没有抽屉菜单，没有二级设置页，从上到下一条长龙把
 * 所有功能列完。原来分散在 抽屉菜单/设置页/动态壁纸设置页/关于页/打赏页 的内容，
 * 现在要么合并进这个页面的某个分区，要么（打赏、检查更新）整个删除。
 *
 * 视觉上：纯黑底色，不用 Card/圆角矩形框，分区之间只用一条分割线隔开。
 */
@Composable
fun HomeScreen() {
    Scaffold(
        containerColor = Color.Black,
        topBar = { TopBar() },
    ) { contentPadding ->
        // enableEdgeToEdge() 之后内容会画到系统栏底下，Scaffold 算出来的
        // contentPadding 同时包含顶部（跟 TopBar 高度对应）和底部（跟系统
        // 导航栏/手势条高度对应）两块安全区域。之前只用了 top，底部这块被
        // 忽略掉了，开着三大金刚导航栏时最后一项内容（"点击查看"）就会被
        // 导航栏盖住。这里把 bottom 也用上，让 LazyColumn 的可视区域整体
        // 让出导航栏的高度。
        SimpleWallpaperContent(
            modifier = Modifier.padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding()
            )
        )
    }
}

@Composable
private fun SimpleWallpaperContent(
    modifier: Modifier = Modifier,
    viewModel: SimpleWallpaperViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val simpleSnackbar = LocalSimpleSnackbar.current

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val rule by viewModel.rule.collectAsStateWithLifecycle()
    val style by viewModel.style.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val activeAlbumId = rule?.rule?.albumIds?.firstOrNull()

    var deleteAlbumTarget by remember { mutableStateOf<Album?>(null) }
    var notificationDialogShow by remember { mutableStateOf(false) }
    var licenseDialogShow by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                viewModel.addFolder(context, uri)
            }
        }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = SCREEN_HORIZONTAL_PADDING),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 大标题：选择文件夹
        item {
            Section(title = stringResource(id = R.string.simple_section_folders)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    albums.forEach { albumWithWallpapers ->
                        FolderRow(
                            album = albumWithWallpapers.album,
                            active = albumWithWallpapers.album.albumId == activeAlbumId,
                            onClick = {
                                viewModel.setActiveAlbum(
                                    context,
                                    albumWithWallpapers.album.albumId
                                )
                            },
                            onDelete = { deleteAlbumTarget = albumWithWallpapers.album }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FilledTonalIconButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(id = R.string.simple_add_folder)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_scan_subfolders))
                        Switch(
                            checked = settings.scanSubfolders,
                            onCheckedChange = { viewModel.setScanSubfolders(it) }
                        )
                    }
                }
            }
        }

        item { Divider() }

        // 大标题：切换壁纸
        item {
            Section(title = stringResource(id = R.string.simple_section_switch)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledTonalIconButton(onClick = { viewModel.previous(context) }) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(id = R.string.simple_previous)
                        )
                    }
                    if (settings.readerMode) {
                        FilledTonalIconButton(onClick = {
                            context.sendBroadcast(
                                Intent(jarvay.workpaper.service.LiveWallpaperService.ACTION_RESET_READER_TRANSFORM)
                            )
                        }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(id = R.string.simple_reader_reset)
                            )
                        }
                    }
                    FilledTonalIconButton(onClick = { viewModel.next(context) }) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(id = R.string.simple_next)
                        )
                    }
                }

                JumpToIndexRow(
                    onConfirm = { index ->
                        viewModel.jumpTo(context, index) {
                            simpleSnackbar.show(R.string.simple_jump_to_out_of_range)
                        }
                    }
                )
            }
        }

        item { Divider() }

        // 这一块原来带一个"定时切换"大标题；产品决定去掉这个标题（跟下面
        // "自动切换"开关本身的文字重复），功能和布局都不变，只是不再传
        // title 给 Section。
        item {
            Section(title = null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LightText(text = stringResource(id = R.string.simple_auto_switch))
                    Switch(
                        checked = rule?.rule?.changeByTiming ?: false,
                        onCheckedChange = { viewModel.setAutoSwitch(it) }
                    )
                }

                if (rule?.rule?.changeByTiming == true) {
                    var intervalText by remember(rule?.rule?.interval) {
                        mutableStateOf((rule?.rule?.interval ?: 30).toString())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DarkOutlinedTextField(
                            value = intervalText,
                            onValueChange = { newValue ->
                                intervalText = newValue.filter { it.isDigit() }
                            },
                            label = stringResource(id = R.string.simple_interval_minutes),
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TextButton(onClick = {
                            intervalText.toIntOrNull()?.let { minutes ->
                                if (minutes > 0) viewModel.setInterval(minutes)
                            }
                        }) {
                            Text(text = stringResource(id = R.string.ok))
                        }
                    }
                }
            }
        }

        item { Divider() }

        // 大标题："效果"（原"滤镜"），模糊上限从 25 提到 50
        item {
            Section(title = stringResource(id = R.string.simple_section_effect)) {
                FilterSlider(
                    label = stringResource(id = R.string.style_form_item_blur_radius),
                    value = style?.blurRadius ?: 0,
                    valueRange = 0f..MAX_BLUR_RADIUS.toFloat(),
                    onValueChange = { viewModel.updateFilter(blurRadius = it) },
                    onReset = { viewModel.updateFilter(blurRadius = 0) }
                )
                FilterSlider(
                    label = stringResource(id = R.string.style_form_item_brightness),
                    value = style?.brightness ?: 0,
                    valueRange = -50f..50f,
                    onValueChange = { viewModel.updateFilter(brightness = it) },
                    onReset = { viewModel.updateFilter(brightness = 0) }
                )
                FilterSlider(
                    label = stringResource(id = R.string.style_form_item_contrast),
                    value = style?.contrast ?: 0,
                    valueRange = -50f..50f,
                    onValueChange = { viewModel.updateFilter(contrast = it) },
                    onReset = { viewModel.updateFilter(contrast = 0) }
                )
                FilterSlider(
                    label = stringResource(id = R.string.style_form_item_saturation),
                    value = style?.saturation ?: 0,
                    valueRange = -50f..50f,
                    onValueChange = { viewModel.updateFilter(saturation = it) },
                    onReset = { viewModel.updateFilter(saturation = 0) }
                )
            }
        }

        item { Divider() }

        // 大标题：缩放模式（图片/视频共用）
        item {
            Section(title = stringResource(id = R.string.simple_section_scale_type)) {
                val currentScaleType = try {
                    WallpaperScaleType.valueOf(settings.scaleType)
                } catch (e: Exception) {
                    WallpaperScaleType.FIXED_PERCENTAGE
                }
                var expanded by remember { mutableStateOf(false) }
                // 阅读器模式开启时，这一整块缩放模式设置不生效（见
                // LiveWallpaperService.loadBitmap 里的强制"适合屏幕"逻辑），
                // 这里只做置灰+禁用交互+提示，不改动实际生效的设置值。
                val disabledByReaderMode = settings.readerMode
                val contentAlpha = if (disabledByReaderMode) 0.4f else 1f

                Column(
                    modifier = Modifier.alpha(contentAlpha),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_scale_type))
                        Box {
                            Text(
                                modifier = Modifier.clickable(enabled = !disabledByReaderMode) {
                                    expanded = true
                                },
                                text = stringResource(id = currentScaleType.labelResId),
                                color = MaterialTheme.colorScheme.primary
                            )
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                WallpaperScaleType.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(id = it.labelResId)) },
                                        onClick = {
                                            viewModel.setScaleType(it)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (currentScaleType == WallpaperScaleType.FIXED_PERCENTAGE) {
                        var percentText by remember(settings.scalePercent) {
                            mutableStateOf(settings.scalePercent.toString())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DarkOutlinedTextField(
                                value = percentText,
                                onValueChange = { percentText = it.filter { c -> c.isDigit() } },
                                label = stringResource(id = R.string.simple_scale_percent),
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !disabledByReaderMode
                            )
                            FilledTonalIconButton(
                                enabled = !disabledByReaderMode,
                                onClick = {
                                    percentText.toIntOrNull()?.let { viewModel.setScalePercent(it) }
                                }
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(id = R.string.ok)
                                )
                            }
                        }
                    }
                }

                if (disabledByReaderMode) {
                    LightText(
                        text = stringResource(id = R.string.simple_scale_type_disabled_by_reader_tips),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        item { Divider() }

        // 大标题：阅读器模式（双指缩放/拖动壁纸，双击复位）。
        // 标题和总开关放在同一行（跟 SwitchRow 一样的布局），下面提示文案+
        // 子开关另起一个 Column，不用 Section 那种"标题占一整行"的老样式。
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.simple_section_reader_mode),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Switch(
                        checked = settings.readerMode,
                        onCheckedChange = { viewModel.setReaderMode(it) }
                    )
                }
                LightText(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringResource(id = R.string.simple_reader_mode_tips),
                    color = MaterialTheme.colorScheme.secondary
                )

                if (settings.readerMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_reader_section_paging))
                        Switch(
                            checked = settings.readerSectionPaging,
                            onCheckedChange = { viewModel.setReaderSectionPaging(it) }
                        )
                    }
                    LightText(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(id = R.string.simple_reader_section_paging_tips),
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (settings.readerSectionPaging) {
                        val currentReadingDirection = try {
                            ReaderReadingDirection.valueOf(settings.readerReadingDirection)
                        } catch (e: Exception) {
                            ReaderReadingDirection.LEFT_TO_RIGHT
                        }
                        var readingDirectionExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LightText(text = stringResource(id = R.string.simple_reader_reading_direction))
                            Box {
                                Text(
                                    modifier = Modifier.clickable { readingDirectionExpanded = true },
                                    text = stringResource(id = currentReadingDirection.labelResId),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                DropdownMenu(
                                    expanded = readingDirectionExpanded,
                                    onDismissRequest = { readingDirectionExpanded = false }
                                ) {
                                    ReaderReadingDirection.entries.forEach {
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(id = it.labelResId)) },
                                            onClick = {
                                                viewModel.setReaderReadingDirection(it)
                                                readingDirectionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_reader_undersize_valign))
                        val currentUndersizeVAlign = try {
                            ReaderUndersizeVAlign.valueOf(settings.readerUndersizeVerticalAlign)
                        } catch (e: Exception) {
                            ReaderUndersizeVAlign.CENTER
                        }
                        var undersizeVAlignExpanded by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                modifier = Modifier.clickable { undersizeVAlignExpanded = true },
                                text = stringResource(id = currentUndersizeVAlign.labelResId),
                                color = MaterialTheme.colorScheme.primary
                            )
                            DropdownMenu(
                                expanded = undersizeVAlignExpanded,
                                onDismissRequest = { undersizeVAlignExpanded = false }
                            ) {
                                ReaderUndersizeVAlign.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(id = it.labelResId)) },
                                        onClick = {
                                            viewModel.setReaderUndersizeVerticalAlign(it)
                                            undersizeVAlignExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_reader_carry_over))
                        Switch(
                            checked = settings.readerCarryOverTransform,
                            onCheckedChange = { viewModel.setReaderCarryOverTransform(it) }
                        )
                    }

                    if (settings.readerCarryOverTransform) {
                        val currentXMode = try {
                            ReaderPanXMode.valueOf(settings.readerCarryOverPanXMode)
                        } catch (e: Exception) {
                            ReaderPanXMode.FOLLOW
                        }
                        var xExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LightText(text = stringResource(id = R.string.simple_reader_carry_over_pan_x_mode))
                            Box {
                                Text(
                                    modifier = Modifier.clickable { xExpanded = true },
                                    text = stringResource(id = currentXMode.labelResId),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                DropdownMenu(
                                    expanded = xExpanded,
                                    onDismissRequest = { xExpanded = false }
                                ) {
                                    ReaderPanXMode.entries.forEach {
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(id = it.labelResId)) },
                                            onClick = {
                                                viewModel.setReaderCarryOverPanXMode(it)
                                                xExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val currentYMode = try {
                            ReaderPanYMode.valueOf(settings.readerCarryOverPanYMode)
                        } catch (e: Exception) {
                            ReaderPanYMode.FOLLOW
                        }
                        var yExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LightText(text = stringResource(id = R.string.simple_reader_carry_over_pan_y_mode))
                            Box {
                                Text(
                                    modifier = Modifier.clickable { yExpanded = true },
                                    text = stringResource(id = currentYMode.labelResId),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                DropdownMenu(
                                    expanded = yExpanded,
                                    onDismissRequest = { yExpanded = false }
                                ) {
                                    ReaderPanYMode.entries.forEach {
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(id = it.labelResId)) },
                                            onClick = {
                                                viewModel.setReaderCarryOverPanYMode(it)
                                                yExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_reader_double_tap_reset))
                        Switch(
                            checked = settings.readerDoubleTapReset,
                            onCheckedChange = { viewModel.setReaderDoubleTapReset(it) }
                        )
                    }
                }
            }
        }

        item { Divider() }

        // 大标题：桌面手势（阅读器模式开启时，双击被阅读器模式接管，这里隐藏掉）
        if (!settings.readerMode) {
            item {
                Section(title = stringResource(id = R.string.simple_section_gesture)) {
                    val currentEvent = try {
                        GestureEvent.valueOf(settings.doubleTapEvent)
                    } catch (e: Exception) {
                        GestureEvent.NONE
                    }
                    var expanded by remember { mutableStateOf(false) }
                    val options = listOf(
                        GestureEvent.NONE,
                        GestureEvent.CHANGE_WALLPAPER_PREV,
                        GestureEvent.CHANGE_WALLPAPER_NEXT,
                        GestureEvent.TOGGLE_EFFECT,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LightText(text = stringResource(id = R.string.simple_double_tap))
                        Box {
                            Text(
                                modifier = Modifier.clickable { expanded = true },
                                text = stringResource(id = currentEvent.labelResId),
                                color = MaterialTheme.colorScheme.primary
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEach {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(id = it.labelResId)) },
                                        onClick = {
                                            viewModel.setDoubleTapEvent(it)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Divider() }
        }

        // 大标题：允许壁纸滚动（从设置里移出来的）
        // 从这里开始，简单的"标题+开关"类设置统一用 SwitchRow：标题和开关放
        // 在同一行，不用再像 Section 那样标题占一行、开关另起一行。
        item {
            SwitchRow(
                title = stringResource(id = R.string.simple_section_wallpaper_scrollable),
                checked = settings.wallpaperScrollable,
                onCheckedChange = { viewModel.setWallpaperScrollable(it) }
            )
        }

        item { Divider() }

        // 大标题：过渡动画（从设置里移出来的）
        item {
            SwitchRow(
                title = stringResource(id = R.string.simple_section_image_transition),
                checked = settings.imageTransition,
                onCheckedChange = { viewModel.setImageTransition(it) }
            )
        }

        item { Divider() }

        // 大标题：开启通知
        item {
            SwitchRow(
                title = stringResource(id = R.string.simple_section_notification),
                checked = settings.enableNotification,
                onCheckedChange = { c ->
                    if (c) {
                        val hasPermission = checkNotifyPermission(context) {
                            notificationDialogShow = true
                        }
                        if (!hasPermission) return@SwitchRow
                        viewModel.setEnableNotification(true)
                    } else {
                        viewModel.setEnableNotification(false)
                    }
                }
            )
        }

        item { Divider() }

        // 大标题：桌面小组件
        item {
            Section(title = stringResource(id = R.string.simple_section_widget)) {
                LightText(
                    text = stringResource(id = R.string.simple_widget_tips),
                    color = MaterialTheme.colorScheme.secondary
                )

                var expanded by remember { mutableStateOf(false) }
                val currentScheme = WidgetColorScheme.fromValue(settings.widgetColorScheme)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LightText(text = stringResource(id = R.string.settings_item_widget_color_scheme))
                    Box {
                        Text(
                            modifier = Modifier.clickable { expanded = true },
                            text = stringResource(id = currentScheme.labelRes),
                            color = MaterialTheme.colorScheme.primary
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            WidgetColorScheme.entries.forEach { scheme ->
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(id = scheme.labelRes)) },
                                    onClick = {
                                        viewModel.setWidgetColorScheme(scheme.value)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Divider() }

        // 大标题：备份与恢复（本地文件，只备份数据库+设置，不含图片本身）
        item {
            var restoreConfirmShow by remember { mutableStateOf(false) }
            var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
            var restoreDoneShow by remember { mutableStateOf(false) }

            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
                onResult = { uri: Uri? ->
                    if (uri != null) {
                        MainScope().launch {
                            val ok = jarvay.workpaper.others.BackupManager.export(context, uri)
                            simpleSnackbar.show(
                                if (ok) R.string.simple_backup_export_success
                                else R.string.simple_backup_export_failed
                            )
                        }
                    }
                }
            )
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri: Uri? ->
                    if (uri != null) {
                        pendingRestoreUri = uri
                        restoreConfirmShow = true
                    }
                }
            )

            Section(title = stringResource(id = R.string.simple_section_backup)) {
                LightText(
                    text = stringResource(id = R.string.simple_backup_tips),
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        val name = "LiveShift-Wallpaper-backup-${System.currentTimeMillis()}.zip"
                        exportLauncher.launch(name)
                    }) {
                        Text(text = stringResource(id = R.string.simple_backup_export))
                    }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip"))
                    }) {
                        Text(text = stringResource(id = R.string.simple_backup_import))
                    }
                }
            }

            SimpleDialog(
                show = restoreConfirmShow,
                title = { Text(text = stringResource(id = R.string.simple_backup_import_confirm_title)) },
                text = stringResource(id = R.string.simple_backup_import_confirm_text),
                onConfirm = {
                    val uri = pendingRestoreUri
                    if (uri != null) {
                        MainScope().launch {
                            val ok = jarvay.workpaper.others.BackupManager.import(context, uri)
                            if (ok) {
                                restoreDoneShow = true
                            } else {
                                simpleSnackbar.show(R.string.simple_backup_import_failed)
                            }
                        }
                    }
                },
                onDismissRequest = { restoreConfirmShow = false }
            )

            SimpleDialog(
                show = restoreDoneShow,
                title = { Text(text = stringResource(id = R.string.simple_backup_import_done_title)) },
                text = stringResource(id = R.string.simple_backup_import_done_text),
                dismissOnConfirm = false,
                onConfirm = {
                    jarvay.workpaper.others.BackupManager.restartApp(context)
                },
                onDismissRequest = { restoreDoneShow = false }
            )
        }

        item { Divider() }

        // 大标题：WebDAV 同步。跟上面本地备份共用同一份 zip 格式（数据库+
        // 设置整体打包，包含相册/规则/滤镜/阅读器缩放位置/当前第几张这些）。
        // 只有"立即上传"/"立即下载"两个手动按钮，不做定时自动同步；
        // 冲突处理是强制覆盖——上传直接覆盖远端文件，下载直接覆盖本地数据，
        // 不做时间戳/版本比较。账号密码用 EncryptedSharedPreferences
        // （Android Keystore 包一层）单独存，不进本地备份 zip，也不跟着
        // 其它设置一起走 DataStore 明文存储。
        item {
            var webDavUrl by remember { mutableStateOf("") }
            var webDavUsername by remember { mutableStateOf("") }
            var webDavPassword by remember { mutableStateOf("") }
            var credentialsLoaded by remember { mutableStateOf(false) }
            var uploadConfirmShow by remember { mutableStateOf(false) }
            var downloadConfirmShow by remember { mutableStateOf(false) }
            var webDavDownloadDoneShow by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (!credentialsLoaded) {
                    credentialsLoaded = true
                    val creds = jarvay.workpaper.others.WebDavManager.getCredentials(context)
                    if (creds != null) {
                        webDavUrl = creds.url
                        webDavUsername = creds.username
                        webDavPassword = creds.password
                    }
                }
            }

            Section(title = stringResource(id = R.string.simple_section_webdav)) {
                LightText(
                    text = stringResource(id = R.string.simple_webdav_tips),
                    color = MaterialTheme.colorScheme.secondary
                )
                DarkOutlinedTextField(
                    value = webDavUrl,
                    onValueChange = { webDavUrl = it },
                    label = stringResource(id = R.string.simple_webdav_url),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                DarkOutlinedTextField(
                    value = webDavUsername,
                    onValueChange = { webDavUsername = it },
                    label = stringResource(id = R.string.simple_webdav_username),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                DarkOutlinedTextField(
                    value = webDavPassword,
                    onValueChange = { webDavPassword = it },
                    label = stringResource(id = R.string.simple_webdav_password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        jarvay.workpaper.others.WebDavManager.saveCredentials(
                            context, webDavUrl.trim(), webDavUsername, webDavPassword
                        )
                        simpleSnackbar.show(R.string.simple_webdav_save_success)
                    }) {
                        Text(text = stringResource(id = R.string.simple_webdav_save))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        if (webDavUrl.isBlank()) {
                            simpleSnackbar.show(R.string.simple_webdav_url_empty)
                        } else {
                            uploadConfirmShow = true
                        }
                    }) {
                        Text(text = stringResource(id = R.string.simple_webdav_upload))
                    }
                    TextButton(onClick = {
                        if (webDavUrl.isBlank()) {
                            simpleSnackbar.show(R.string.simple_webdav_url_empty)
                        } else {
                            downloadConfirmShow = true
                        }
                    }) {
                        Text(text = stringResource(id = R.string.simple_webdav_download))
                    }
                }
            }

            SimpleDialog(
                show = uploadConfirmShow,
                title = { Text(text = stringResource(id = R.string.simple_webdav_upload_confirm_title)) },
                text = stringResource(id = R.string.simple_webdav_upload_confirm_text),
                onConfirm = {
                    val url = webDavUrl.trim()
                    jarvay.workpaper.others.WebDavManager.saveCredentials(
                        context, url, webDavUsername, webDavPassword
                    )
                    MainScope().launch {
                        val ok = jarvay.workpaper.others.WebDavManager.upload(context)
                        simpleSnackbar.show(
                            if (ok) R.string.simple_webdav_upload_success
                            else R.string.simple_webdav_upload_failed
                        )
                    }
                },
                onDismissRequest = { uploadConfirmShow = false }
            )

            SimpleDialog(
                show = downloadConfirmShow,
                title = { Text(text = stringResource(id = R.string.simple_webdav_download_confirm_title)) },
                text = stringResource(id = R.string.simple_webdav_download_confirm_text),
                onConfirm = {
                    val url = webDavUrl.trim()
                    jarvay.workpaper.others.WebDavManager.saveCredentials(
                        context, url, webDavUsername, webDavPassword
                    )
                    MainScope().launch {
                        val ok = jarvay.workpaper.others.WebDavManager.download(context)
                        if (ok) {
                            webDavDownloadDoneShow = true
                        } else {
                            simpleSnackbar.show(R.string.simple_webdav_download_failed)
                        }
                    }
                },
                onDismissRequest = { downloadConfirmShow = false }
            )

            SimpleDialog(
                show = webDavDownloadDoneShow,
                title = { Text(text = stringResource(id = R.string.simple_backup_import_done_title)) },
                text = stringResource(id = R.string.simple_backup_import_done_text),
                dismissOnConfirm = false,
                onConfirm = {
                    jarvay.workpaper.others.BackupManager.restartApp(context)
                },
                onDismissRequest = { webDavDownloadDoneShow = false }
            )
        }

        item { Divider() }

        // 大标题：开源许可（点击弹窗，不再单独占一个页面/一句"基于 XX 二次开发"的说明）
        item {
            Section(
                title = stringResource(id = R.string.about_oss_title),
                titleModifier = Modifier.clickable { licenseDialogShow = true }
            ) {
                LightText(
                    text = stringResource(id = R.string.about_oss_tap_tips),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    SimpleDialog(
        show = deleteAlbumTarget != null,
        text = stringResource(id = R.string.simple_delete_folder_tips),
        onDismissRequest = { deleteAlbumTarget = null }
    ) {
        deleteAlbumTarget?.let { viewModel.removeAlbum(it) }
    }

    SimpleDialog(
        text = stringResource(id = R.string.permission_request_notification),
        show = notificationDialogShow,
        onDismissRequest = { notificationDialogShow = false }
    ) {
        requestNotificationPermission(context)
    }

    SimpleDialog(
        show = licenseDialogShow,
        hideDismissButton = true,
        confirmButtonText = stringResource(id = R.string.close),
        title = { Text(text = stringResource(id = R.string.about_oss_title)) },
        content = {
            Text(
                text = stringResource(id = R.string.about_oss_license_text),
                style = MaterialTheme.typography.bodySmall
            )
        },
        onDismissRequest = { licenseDialogShow = false },
        onConfirm = { licenseDialogShow = false }
    )
}

@Composable
private fun Section(
    title: String?,
    titleModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        // title 为 null 时（比如"定时切换"那块，标题跟里面的开关文字重复，
        // 产品决定去掉）不渲染标题这一行，内容照常展示，其余布局不变。
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = titleModifier
            )
        }
        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * 单行的"标题+开关"设置项：标题和开关在同一行，不像 Section 那样标题占一行、
 * 内容另起一行。用于简单的、内容只有一个 Switch 的设置项（以后同类的新增
 * 开关也用这个，不要再用 Section+单独一行 Switch 的老样式）。
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
}

@Composable
private fun LightText(text: String, color: Color = Color.White, modifier: Modifier = Modifier) {
    Text(text = text, color = color, modifier = modifier)
}

@Composable
private fun DarkOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        enabled = enabled,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
    )
}

/**
 * "跳转到第几张"：一个数字输入框 + 一个确定按钮，1-based。
 * 越界/文件夹为空一律弹提示，不自动纠正。
 */
@Composable
private fun JumpToIndexRow(onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DarkOutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() } },
            label = stringResource(id = R.string.simple_jump_to_label),
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        TextButton(onClick = {
            val index = text.toIntOrNull()
            if (index != null && index > 0) {
                onConfirm(index)
            }
        }) {
            Text(text = stringResource(id = R.string.ok))
        }
    }
}

@Composable
private fun FolderRow(
    album: Album,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val coverSize = 56.dp

    val dirUri = album.dirs?.firstOrNull()
    val coverFile = dirUri?.let { context.getOneWallpaperInDir(it.toUri()) }
    val model = coverFile?.let {
        ImageRequest.Builder(context).data(it.uri).size(128, 128).build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(coverSize)
                        .height(coverSize)
                        .aspectRatio(1f)
                )
            } else {
                // 顶层文件夹里没找到图片/视频时（比如图片都在子文件夹里），
                // 用一个灰底占位图标代替空白，提示"这一层没有找到素材"，
                // 不代表文件夹是空的（子文件夹里可能有）。
                Box(
                    modifier = Modifier
                        .width(coverSize)
                        .height(coverSize)
                        .aspectRatio(1f)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ImageNotSupported,
                        contentDescription = null,
                        tint = Color.LightGray
                    )
                }
            }
            LightText(text = album.name)
        }

        // "✔" 和 "删除" 之间用一个明确的 24dp Spacer 隔开，而不是靠
        // SpaceBetween/默认 padding 顶开——之前间距太小，容易点错。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.width(24.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun FilterSlider(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LightText(text = label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = value.toString(), color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = onReset) {
                    Text(text = stringResource(id = R.string.reset))
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange
        )
    }
}

private fun checkPermissions(context: Context, onRequestPermission: () -> Unit): Boolean {
    var hasPermission = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager: AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        hasPermission = alarmManager.canScheduleExactAlarms()

        if (!hasPermission) {
            onRequestPermission()
        }
    }

    return hasPermission
}

private fun checkNotifyPermission(context: Context, onRequestPermission: () -> Unit): Boolean {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (notificationManager.areNotificationsEnabled()) {
        return true
    }
    onRequestPermission()
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    homeScreenViewModel: HomeScreenViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    var alarmPermissionDialogShow by remember { mutableStateOf(false) }

    val runningPreferences by homeScreenViewModel.runningPreferences.collectAsStateWithLifecycle()
    val running = runningPreferences?.running ?: false

    val infiniteTransition = rememberInfiniteTransition(label = "infinite transition")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale"
    )

    // 顶部整体固定不滚动：第一行是 App 名（居中），第二行是新增的
    // "壁纸开关"大标题 + 开关（开关本身还是原来那个，只是从 TopAppBar 的
    // actions 里挪出来、加了个文字标签，位置换到标题右边）。
    Column(modifier = Modifier.background(Color.Black)) {
        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White,
            ),
            title = {
                Text(text = stringResource(id = R.string.app_name))
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SCREEN_HORIZONTAL_PADDING, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.simple_section_running),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Switch(
                checked = running,
                onCheckedChange = {
                    if (it && checkPermissions(context, onRequestPermission = {
                            alarmPermissionDialogShow = true
                        })) {
                        MainScope().launch {
                            homeScreenViewModel.start()
                        }
                    } else if (!it) {
                        MainScope().launch {
                            homeScreenViewModel.stop()
                        }
                    }
                },
                thumbContent = if (running) {
                    {
                        Icon(
                            imageVector = Icons.Default.Lens,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SwitchDefaults.IconSize)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin.Center
                                },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    null
                }
            )
        }
    }

    SimpleDialog(
        show = alarmPermissionDialogShow,
        text = stringResource(id = R.string.permission_request_alarm),
        onDismissRequest = { alarmPermissionDialogShow = false }
    ) {
        requestAlarmPermission(context = context)
    }
}
