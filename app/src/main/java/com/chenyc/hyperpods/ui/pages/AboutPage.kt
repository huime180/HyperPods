package com.chenyc.hyperpods.ui.pages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chenyc.hyperpods.BuildConfig
import com.chenyc.hyperpods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

private fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        if (this@openUrl !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    startActivity(intent)
}

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                // 本模块自己的项目主页、版本与许可。版本用 BuildConfig.VERSION_NAME
                // （与 module.prop 由 :app:verifyModuleProp 断言一致），不手写常量。
                BasicComponent(
                    title = stringResource(R.string.about_project_home),
                    summary = "https://github.com/huime180/HyperPods",
                    onClick = {
                        context.openUrl("https://github.com/huime180/HyperPods")
                    }
                )
                BasicComponent(
                    title = stringResource(R.string.about_version),
                    summary = "HyperPods ${BuildConfig.VERSION_NAME}"
                )
                BasicComponent(
                    title = stringResource(R.string.about_license),
                    summary = "GPL-3.0"
                )
                // 融合的两条上游。仓库名都带 OppoPods：旧版这里写的 1812z/HyperPods、
                // Leaf-lsgtky/HyperPods 两个地址都已 404，链接按实际仓库改正。
                BasicComponent(
                    title = "OppoPods (1812z)",
                    summary = "https://github.com/1812z/OppoPods",
                    onClick = {
                        context.openUrl("https://github.com/1812z/OppoPods")
                    }
                )
                BasicComponent(
                    title = "OppoPods (Leaf-lsgtky)",
                    summary = "https://github.com/Leaf-lsgtky/OppoPods",
                    onClick = {
                        context.openUrl("https://github.com/Leaf-lsgtky/OppoPods")
                    }
                )
                BasicComponent(
                    title = stringResource(R.string.based_on),
                    summary = "HyperPods by Art_Chen"
                )
                BasicComponent(
                    title = "Github",
                    summary = "https://github.com/Art-Chen/HyperPods",
                    onClick = {
                        context.openUrl("https://github.com/Art-Chen/HyperPods")
                    }
                )
            }
        }
    }
}
