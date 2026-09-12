package com.shiyinplayer.data.mapper

import com.shiyinplayer.data.local.entity.AlbumEntity
import com.shiyinplayer.data.local.entity.ArtistEntity
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import com.shiyinplayer.data.local.entity.PlaylistEntity
import com.shiyinplayer.data.local.entity.PlaylistItemEntity
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.model.Playlist
import com.shiyinplayer.data.model.PlaylistItem
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.util.SongSearchKey

/** 实体 <-> 领域模型互转（架构 §1.2.4：实体与领域模型分离）。 */
object EntityMappers {
    fun SongEntity.toModel() = Song(
        id, title, artistId, albumId, artistName, albumName, albumArtUri,
        durationMs, trackNumber, uri, mimeType, sourceType, path, dateAdded,
        sizeBytes, cueId, trackIndex, clipStartMs, clipEndMs, dedupKey,
        genre, year, rating, playCount, lastPlayedMs, altUris = emptyList(),
        formatVerified = formatVerified, lyricOffsetMs = lyricOffsetMs
    )

    fun Song.toEntity() = SongEntity(
        id, title, artistId, albumId, artistName, albumName, albumArtUri,
        durationMs, trackNumber, uri, mimeType, source, path, dateAdded,
        sizeBytes, cueId, trackIndex, clipStartMs, clipEndMs, dedupKey,
        genre, year, rating, playCount, lastPlayedMs, formatVerified,
        lyricOffsetMs = lyricOffsetMs,
        searchKey = SongSearchKey.of(title, artistName, albumName)
    )

    fun AlbumEntity.toModel() = Album(id, name, artistName, albumArtUri, year, songCount)
    fun Album.toEntity() = AlbumEntity(id, name, artistName, albumArtUri, year, songCount)

    fun ArtistEntity.toModel() = Artist(id, name, albumCount, songCount)
    fun Artist.toEntity() = ArtistEntity(id, name, albumCount, songCount)

    fun PlaylistEntity.toModel() = Playlist(id, name, dateCreated, dateModified)
    fun Playlist.toEntity() = PlaylistEntity(id, name, dateCreated, dateModified)

    fun PlaylistItemEntity.toModel() = PlaylistItem(id, playlistId, songId, position)
    fun PlaylistItem.toEntity() = PlaylistItemEntity(id, playlistId, songId, position)

    fun MusicSourceEntity.toModel() = MusicSource(id, name, type, configJson, enabled, lastScanTime)
    fun MusicSource.toEntity() = MusicSourceEntity(id, name, type, configJson, enabled, lastScanTime)
}
