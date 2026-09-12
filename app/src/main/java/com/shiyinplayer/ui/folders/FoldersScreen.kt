package com.shiyinplayer.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.ui.navigation.Screen

@Composable
fun FoldersScreen(navController: NavController? = null, viewModel: FoldersViewModel = hiltViewModel()) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier) {
        items(folders, key = { it.name }) { folder ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        navController?.navigate(Screen.FolderDetail.createRoute(folder.name))
                    }
                    .padding(16.dp)
            ) {
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}
