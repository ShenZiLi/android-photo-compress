package com.photocompress.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.allAlbumNames
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.EmptyState
import com.photocompress.app.ui.formatCount
import com.photocompress.app.ui.theme.appColors

/**
 * 图集过滤（二级页面）：逐图集开关，被排除的图集不出现在未压缩 / 已压缩页。
 */
@Composable
fun AlbumFilterScreen(
    state: UiState,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val albums = state.allAlbumNames()
    val excluded = state.excludedAlbums

    Column(modifier = Modifier.fillMaxWidth()) {
        AppBar(
            title = "图集过滤",
            subtitle = "已排除 ${formatCount(excluded.size)} 个 · 共 ${formatCount(albums.size)} 个图集",
            navigation = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
                }
            },
        )
        if (albums.isEmpty()) {
            EmptyState("暂无可配置的图集", "扫描完成后这里会列出本机图集")
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.first }) { (name, count) ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${formatCount(count)} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.onSurfaceMuted,
                            )
                        }
                        Switch(
                            checked = name in excluded,
                            onCheckedChange = { onToggle(name, it) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
