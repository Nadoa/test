package com.tokisync.viewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tokisync.viewer.data.Series
import com.tokisync.viewer.ui.AppViewModel

/** 작품 표지 (Drive 토큰으로 받아 로컬 캐시) */
@Composable
fun SeriesCover(vm: AppViewModel, series: Series, modifier: Modifier = Modifier) {
    val model by produceState<Any?>(initialValue = null, series.id, series.thumbnailId, series.thumbnail) {
        value = runCatching { vm.thumbs.resolve(series) }.getOrNull()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (model == null) {
            Text(
                series.name.take(1),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = series.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
