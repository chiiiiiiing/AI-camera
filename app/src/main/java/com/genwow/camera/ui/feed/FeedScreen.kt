package com.genwow.camera.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.genwow.camera.data.demo.DemoCommunityRepository
import com.genwow.camera.data.demo.DemoPost
import com.genwow.camera.ui.theme.GenWowTheme
import com.genwow.camera.ui.theme.SystemBarsAppearance

private val FeedBackground = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F3),
        Color(0xFFF7E6F7),
        Color(0xFFD8CCF5),
    ),
)

@Composable
fun FeedScreen(
    posts: List<DemoPost>,
    onToggleLike: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarsAppearance(light = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    Column(modifier.fillMaxSize().background(FeedBackground).statusBarsPadding()) {
        CommunityTopTabs(selectedIndex = selectedTab, onSelected = { selectedTab = it })
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            items(posts, key = DemoPost::id) { post ->
                FeedPostCard(post = post, onToggleLike = onToggleLike)
            }
        }
    }
}

@Preview(name = "Feed 412x917", widthDp = 412, heightDp = 917)
@Composable
private fun FeedPreview() {
    val repository = DemoCommunityRepository()
    GenWowTheme {
        FeedScreen(posts = repository.posts, onToggleLike = repository::toggleLike)
    }
}
