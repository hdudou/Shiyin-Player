package com.shiyinplayer.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiyinplayer.R
import com.shiyinplayer.data.radio.RadioBrowserClient
import kotlinx.coroutines.launch

/**
 * 电台搜索页面（搜索结果页 + 收藏按钮）。
 * 搜索框下方不再显示流派关键词提醒。
 * 搜索结果项右侧显示"收藏"按钮，点击即可收藏；点击卡片主体可播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSearchScreen(
    onBack: () -> Unit,
    onStationFound: (url: String, name: String) -> Unit,
    onAddFavorite: ((url: String, name: String, genre: String) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.radio_search_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        )

        RadioBrowserSearchTab(
            onPlayStation = onStationFound,
            onAddFavorite = onAddFavorite
        )
    }
}

@Composable
private fun RadioBrowserSearchTab(
    onPlayStation: (url: String, name: String) -> Unit,
    onAddFavorite: ((url: String, name: String, genre: String) -> Unit)?
) {
    val client = remember { RadioBrowserClient() }
    DisposableEffect(Unit) {
        onDispose { client.shutdown() }
    }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<RadioBrowserClient.RadioBrowserStation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val favoritedUrls = remember { mutableStateOf(emptySet<String>()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 搜索框（无标签提示）
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.radio_search_placeholder)) },
            trailingIcon = {
                IconButton(onClick = {
                    if (query.isNotBlank()) {
                        isLoading = true
                        scope.launch {
                            try {
                                stations = client.searchStations(query = query)
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                }
            },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        // 搜索结果（无流派关键词提醒）
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stations) { station ->
                    RadioBrowserStationItem(
                        station = station,
                        isFavorited = station.url in favoritedUrls.value,
                        onClick = { onPlayStation(station.url, station.name) },
                        onFavorite = {
                            if (onAddFavorite != null) {
                                onAddFavorite(station.url, station.name, station.tags)
                                favoritedUrls.value = favoritedUrls.value + station.url
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioBrowserStationItem(
    station: RadioBrowserClient.RadioBrowserStation,
    isFavorited: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (station.tags.isNotEmpty()) {
                    Text(
                        station.tags,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    stringResource(R.string.radio_search_meta, station.country, station.bitrate, station.votes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 播放按钮（点击直接播放）
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.radio_play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // 收藏按钮（点击加入收藏）
            IconButton(onClick = onFavorite) {
                Icon(
                    if (isFavorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(if (isFavorited) R.string.radio_favorited else R.string.radio_favorite),
                    tint = if (isFavorited) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
