package com.lightningstudio.watchrss.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.account.AppAccessProductInfo

/** A deliberately explicit boundary between WatchRSS access and third-party platform rights. */
@Composable
internal fun PaidAccessTransparencyCard(
    product: AppAccessProductInfo = AppAccessProductInfo(),
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "¥6购买的是什么？",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            PaidAccessBoundaryText(product = product, compact = compact)
        }
    }
}

@Composable
internal fun PaidAccessBoundaryText(
    product: AppAccessProductInfo = AppAccessProductInfo(),
    compact: Boolean = false
) {
    Text(
        text = "这是${product.productName}：一次支付¥${product.priceFen / 100}，不自动续费，账号增加${product.deviceCapacity}台手机授权容量。",
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = "它与哔哩哔哩、抖音的会员或平台功能无关；这笔费用主要用于腕上RSS的小说阅读、备忘录，以及手机与手表之间的资料和阅读状态同步。",
        style = MaterialTheme.typography.bodyMedium
    )
    if (!compact) {
        HorizontalDivider()
        Text(
            text = "不包含：哔哩哔哩或抖音会员权益、腕上RSS当前未提供的其他平台社区功能，也不会提升网络速度、视频清晰度、码率、CDN或加载速度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "WatchRSS云会员、云空间和相关云服务是独立权益；平台账号、平台会员和平台内容规则由对应平台管理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun PaidAccessActivatedCard(
    capacity: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "手机版设备授权已生效",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("当前账号手机容量：${capacity}台。")
            Text(
                "已获得手机端小说与本地资料阅读、备忘录，以及手机与手表协同同步能力。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "本次付款不包含WatchRSS云会员，也不包含哔哩哔哩或抖音平台会员；不会改变视频清晰度、码率、CDN、播放地址或网络速度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
