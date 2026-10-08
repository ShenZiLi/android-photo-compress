package com.photocompress.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photocompress.app.data.media.StorageAccess
import com.photocompress.app.ui.AppRoot
import com.photocompress.app.ui.AppViewModel
import com.photocompress.app.ui.theme.PhotoCompressTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoCompressTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    AppEntry(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermission()
    }
}

/** 入口：缺少「所有文件访问」时先引导授权（D9）。 */
@Composable
private fun AppEntry(vm: AppViewModel) {
    val context = LocalContext.current
    val state by vm.ui.collectAsStateWithLifecycle()
    var requested by remember { mutableStateOf(false) }

    if (!state.hasAllFilesAccess) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要文件访问权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${stringResource(R.string.app_name)}需要「所有文件访问」权限，才能原地改写照片并保留拍摄时间与位置信息。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
            com.photocompress.app.ui.components.GlassButton(
                onClick = {
                    requested = true
                    runCatching { context.startActivity(StorageAccess.allFilesAccessIntent(context)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (requested) "重新打开授权页" else "去授权")
            }
            if (requested) {
                Text(
                    "授权后返回本页面会自动继续。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    } else {
        AppRoot(vm)
    }
}
