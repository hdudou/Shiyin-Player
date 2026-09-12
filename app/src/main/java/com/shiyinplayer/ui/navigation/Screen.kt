package com.shiyinplayer.ui.navigation

import android.net.Uri

/** 路由定义。设置子屏与网络/ZeroTier 单独列出。 */
sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Songs : Screen("songs")
    object Albums : Screen("albums")
    object Artists : Screen("artists")
    object Playlists : Screen("playlists")
    object More : Screen("more")
    object NowPlaying : Screen("nowplaying")
    object Queue : Screen("queue")
    object Folders : Screen("folders")
    object Search : Screen("search")
    object ZeroTier : Screen("zerotier")
    object Network : Screen("network")
    object Equalizer : Screen("equalizer")
    object About : Screen("about")
    object Stats : Screen("stats")
    object Duplicates : Screen("duplicates")

    // 详情页（P0 补全：专辑/艺术家/歌单/文件夹）
    object AlbumDetail : Screen("album_detail/{albumName}?artist={artist}") {
        fun createRoute(albumName: String, artistName: String?) =
            "album_detail/${Uri.encode(albumName)}?artist=${Uri.encode(artistName ?: "")}"
    }
    object ArtistDetail : Screen("artist_detail/{artistName}") {
        fun createRoute(artistName: String) = "artist_detail/${Uri.encode(artistName)}"
    }
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_detail/$playlistId"
    }
    object FolderDetail : Screen("folder_detail/{folderName}") {
        fun createRoute(folderName: String) = "folder_detail/${Uri.encode(folderName)}"
    }
    object SmartPlaylist : Screen("smart_playlist/{type}") {
        fun createRoute(type: String) = "smart_playlist/$type"
    }

    // 更多 9 分类设置子屏（P3 归类：界面/音乐库/回放/音乐来源/元数据/声音引擎/系统集成）
    object SettingsInterface : Screen("settings_interface")
    object SettingsLibrary : Screen("settings_library")
    object SettingsPlayback : Screen("settings_playback")
    object SettingsSources : Screen("settings_sources")
    object SettingsMetadata : Screen("settings_metadata")
    object SettingsMetadataSources : Screen("settings_metadata_sources")
    object SettingsSound : Screen("settings_sound")
    object SettingsIntegration : Screen("settings_integration")

    // 播放器数据导出/导入（入口弹窗选择后进入各自独立界面）
    object DataExport : Screen("data_export")
    object DataImport : Screen("data_import")


}
