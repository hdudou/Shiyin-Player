package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Update
import com.shiyinplayer.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    companion object {
        /**
         * [8] 多源合并键 SQL 表达式：与 LibraryRepository.mergeSongs() 的 Kotlin 实现一致
         * （lowercase(trim(title)+'\0'+trim(artistName?:'')+'\0'+trim(albumName?:''))）。
         * 注意：SQLite lower() 仅 ASCII 小写、trim() 仅空格——与 Kotlin 在 CJK 数据集上等价（已验证），
         * 若未来出现重音拉丁文标题需同步评估。
         * 2026-08-19 修复：title 与 artistName/albumName 一样加 COALESCE——此前 trim(title) 在
         * title 为 NULL 时表达式整体为 NULL，GROUP BY 组 mergeKey=NULL，Room 映射 MergedAltRow/
         * MergedPrimaryRow 的非空 mergeKey 参数抛 NPE（真机播放中闪退，songdao 崩溃栈
         * MergedAltRow.<init> parameter mergeKey）。任何路径写入 title=NULL 行都不应崩列表。
         */
        const val MERGE_KEY_EXPR =
            "lower(trim(coalesce(title,''))||char(0)||trim(coalesce(artistName,''))||char(0)||trim(coalesce(albumName,'')))"
    }

    @Query("SELECT * FROM songs ORDER BY title COLLATE LOCALIZED")
    fun observeAll(): Flow<List<SongEntity>>

    /** 2026-08-28：分页拉取全表（导出/重建/目录树等后台批量场景替代 observeAll().first()，防 CursorWindow 溢出）。 */
    @Query("SELECT * FROM songs ORDER BY title COLLATE LOCALIZED LIMIT :limit OFFSET :offset")
    suspend fun getAllPaged(offset: Int, limit: Int): List<SongEntity>

    /**
     * [8] SQL 层多源合并（首屏加载优化）：每组合并键返回一行主曲目。主行 = 来源优先级最高
     * （LOCAL<SMB<WEBDAV<HTTP，id 决胜）的行；单 MIN 聚合 + bare-column 规则保证 s.* 来自该行。
     * _rank 列仅供选主排序，Room 映射时忽略。走 index_songs_mergekey_expr 表达式索引（AppModule onOpen 创建）。
     *
     * 2026-08-19 修复（Room 2.6.1 CursorWindow 崩溃）：这两个 Flow 查询在歌曲 >700 首时，
     * 合并结果（组数）超过单个 CursorWindow 容量（约 719 行 × 30 列），且 [LibraryRepository.getSongs]
     * 用 combine 并发订阅二者 → 并发 fillWindow 竞争导致窗口越界读（`Failed to read row N from
     * CursorWindow`）→ getString 返回 null → Room 非空映射抛 NPE 闪退（logcat 特征：
     * `Couldn't read row 719, col 11 from CursorWindow` + `MergedAltRow.<init> parameter mergeKey`）。
     * 修复：见 getSongs() —— 改为 count flow 触发 + 顺序执行 [getMergedPrimariesOnce]/[getMergedAltsOnce]
     * （suspend 查询走 CoroutinesRoom.execute，SQLiteCursor 正常翻页，且串行消除并发窗口竞争）。
     * 此处 Flow 版本保留仅作后备/单点查询。
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT s.*, " + SongDao.MERGE_KEY_EXPR + " AS mergeKey, " +
            "MIN(CASE s.sourceType WHEN 'LOCAL' THEN 0 WHEN 'SMB' THEN 1 WHEN 'WEBDAV' THEN 2 ELSE 3 END * 1000000000 + s.id) AS _rank " +
            "FROM songs s GROUP BY " + SongDao.MERGE_KEY_EXPR
    )
    fun observeMergedPrimaries(): Flow<List<MergedPrimaryRow>>

    /** 2026-08-19：同 [observeMergedPrimaries] 的 suspend 版（一次性查询，供顺序执行避免 CursorWindow 竞争）。 */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT s.*, " + SongDao.MERGE_KEY_EXPR + " AS mergeKey, " +
            "MIN(CASE s.sourceType WHEN 'LOCAL' THEN 0 WHEN 'SMB' THEN 1 WHEN 'WEBDAV' THEN 2 ELSE 3 END * 1000000000 + s.id) AS _rank " +
            "FROM songs s GROUP BY " + SongDao.MERGE_KEY_EXPR +
            " ORDER BY mergeKey LIMIT :limit OFFSET :offset"
    )
    suspend fun getMergedPrimaries(offset: Int, limit: Int): List<MergedPrimaryRow>
    suspend fun getMergedPrimariesOnce(): List<MergedPrimaryRow> = getMergedPrimaries(0, Int.MAX_VALUE)

    /** [8] 各合并组的全部备选来源：组内所有行的 uri/sourceType/id 以 char(1) 分隔（含主行，Kotlin 侧按 id 排除）。 */
    @Query(
        "SELECT " + SongDao.MERGE_KEY_EXPR + " AS mergeKey, " +
            "GROUP_CONCAT(uri, char(1)) AS uris, " +
            "GROUP_CONCAT(sourceType, char(1)) AS types, " +
            "GROUP_CONCAT(id, char(1)) AS ids " +
            "FROM songs GROUP BY " + SongDao.MERGE_KEY_EXPR
    )
    fun observeMergedAlts(): Flow<List<MergedAltRow>>

    /** 2026-08-19：同 [observeMergedAlts] 的 suspend 版（一次性查询，供顺序执行避免 CursorWindow 竞争）。 */
    @Query(
        "SELECT " + SongDao.MERGE_KEY_EXPR + " AS mergeKey, " +
            "GROUP_CONCAT(uri, char(1)) AS uris, " +
            "GROUP_CONCAT(sourceType, char(1)) AS types, " +
            "GROUP_CONCAT(id, char(1)) AS ids " +
            "FROM songs GROUP BY " + SongDao.MERGE_KEY_EXPR +
            " ORDER BY mergeKey LIMIT :limit OFFSET :offset"
    )
    suspend fun getMergedAlts(offset: Int, limit: Int): List<MergedAltRow>
    suspend fun getMergedAltsOnce(): List<MergedAltRow> = getMergedAlts(0, Int.MAX_VALUE)

    /**
     * 2026-08-19：songs 表变化触发器（仅 1 行结果，不触发 CursorWindow 大结果集问题）。
     * [LibraryRepository.getSongs] 用它驱动重查：每次歌曲数变化 → 顺序执行两个 suspend 合并查询。
     */
    @Query("SELECT COUNT(*) FROM songs")
    fun observeSongsCount(): Flow<Int>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun getByIdSync(id: Long): SongEntity?

    /** P2-9：断点续播批量取曲目（一次查询替代逐 id 串行 getById）。 */
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    /** AZ-删除对账：判定给定 id 中哪些仍存在于 songs 表（轻量，只回主键）。 */
    @Query("SELECT id FROM songs WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY trackNumber, title")
    fun observeByAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistId = :artistId ORDER BY title")
    fun observeByArtist(artistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumName = :albumName AND (artistName = :artistName OR :artistName IS NULL) ORDER BY trackNumber, title")
    fun observeByAlbumName(albumName: String, artistName: String?): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistName = :artistName ORDER BY title")
    fun observeByArtistName(artistName: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE title LIKE :q ESCAPE '\\' OR artistName LIKE :q ESCAPE '\\' OR albumName LIKE :q ESCAPE '\\' OR searchKey LIKE :sk ESCAPE '\\'")
    fun search(q: String, sk: String): Flow<List<SongEntity>>

    // F2-2：按字段定向搜索（搜索类型切换）；F6-2 增补拼音/首字母匹配（searchKey）
    @Query("SELECT * FROM songs WHERE (title LIKE :q ESCAPE '\\') OR searchKey LIKE :sk ESCAPE '\\'")
    fun searchByTitle(q: String, sk: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistName LIKE :q ESCAPE '\\' OR searchKey LIKE :sk ESCAPE '\\'")
    fun searchByArtist(q: String, sk: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumName LIKE :q ESCAPE '\\' OR searchKey LIKE :sk ESCAPE '\\'")
    fun searchByAlbum(q: String, sk: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE path LIKE :q ESCAPE '\\' OR (uri LIKE :q ESCAPE '\\' AND uri NOT LIKE 'content:%')")
    fun searchByFilename(q: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(songs: List<SongEntity>)

    /** 2026-08-26：批量插入并返回每行 id——被 IGNORE 跳过（已存在）的行返回 -1，用于精确统计「新增」数量。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllRows(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun update(song: SongEntity)

    @Update
    suspend fun updateAll(songs: List<SongEntity>)

    /** 2026-08-24：全量扫描按 dedupKey 定位已存在行（完整实体，含 id/用户数据），用于按 id 覆盖元数据而保留主键。 */
    @Query("SELECT * FROM songs WHERE dedupKey IN (:keys)")
    suspend fun getByDedupKeys(keys: List<String>): List<SongEntity>

    @Query("UPDATE songs SET albumArtUri = :url WHERE id = :id")
    suspend fun setAlbumArt(id: Long, url: String)

    @Query("UPDATE songs SET playCount = :count, lastPlayedMs = :lastPlayedMs WHERE id = :id")
    suspend fun updatePlayStats(id: Long, count: Int, lastPlayedMs: Long)

    /** F2-3：播放统计概览（SQL 聚合，避免全表加载）。 */
    @Query("SELECT COUNT(*) AS totalSongs, COALESCE(SUM(playCount), 0) AS totalPlays, SUM(CASE WHEN playCount > 0 THEN 1 ELSE 0 END) AS playedSongs FROM songs")
    fun observePlayStats(): Flow<PlayStatsRow>

    @Query("UPDATE songs SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    /** [7] 手工修正元数据（标题/艺术家/专辑）写回主库（不触碰 year，避免清空已抓取的年份）。 */
    @Query("UPDATE songs SET title = :title, artistName = :artist, albumName = :album WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, artist: String?, album: String?)

    /** [7] 在线匹配写回（含年份，决策 6）。 */
    @Query("UPDATE songs SET title = :title, artistName = :artist, albumName = :album, year = :year WHERE id = :id")
    suspend fun updateMetadataWithYear(id: Long, title: String, artist: String?, album: String?, year: Int?)

    /** §12 歌词时间偏移写回（仅写 DB）。 */
    @Query("UPDATE songs SET lyricOffsetMs = :ms WHERE id = :id")
    suspend fun setLyricOffset(id: Long, ms: Long)

    /** 自动同步元数据：取所有本地歌曲（sourceType=LOCAL，path 可解析）。 */
    @Query("SELECT * FROM songs WHERE sourceType = 'LOCAL'")
    suspend fun getLocalSongs(): List<SongEntity>

    /** 自动同步元数据：只取本地歌曲中「元数据缺失」（artist/album/year/genre/title 任一空）的曲目，跳过已有完整元数据的。 */
    @Query(
        "SELECT * FROM songs WHERE sourceType = 'LOCAL' AND " +
            "(title IS NULL OR title = '' " +
            "OR artistName IS NULL OR artistName = '' " +
            "OR albumName IS NULL OR albumName = '' " +
            "OR year IS NULL OR year = 0 " +
            "OR genre IS NULL OR genre = '')"
    )
    suspend fun getLocalSongsForSync(): List<SongEntity>

    /** 手动批量同步元数据：全库（任意来源）元数据缺失的歌曲按 id 分页取，limit+offset 翻页避免 CursorWindow 溢出。
     *  缺失判定仅含 标题/歌手/专辑 三字段（year/genre 可由展示层兜底，缺失不应阻塞同步收敛，避免横继续重复处理）。 */
    @Query(
        "SELECT * FROM songs WHERE " +
            "(title IS NULL OR title = '' " +
            "OR artistName IS NULL OR artistName = '' " +
            "OR albumName IS NULL OR albumName = '') " +
            "ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun getSongsMissingMetadataPaged(limit: Int, offset: Int): List<SongEntity>

    /** 手动批量同步元数据：统计全库仍缺元数据的歌曲总数（用于进度显示）。缺失判定同上（仅标题/歌手/专辑）。 */
    @Query(
        "SELECT COUNT(*) FROM songs WHERE " +
            "(title IS NULL OR title = '' " +
            "OR artistName IS NULL OR artistName = '' " +
            "OR albumName IS NULL OR albumName = '')"
    )
    suspend fun countSongsMissingMetadata(): Long

    /** 自动同步元数据：取网络源歌曲（WEBDAV/SMB），优先缺歌手/专辑/年份的，限量处理避免打爆在线 API。 */
    @Query(
        "SELECT * FROM songs WHERE sourceType IN ('WEBDAV','SMB') " +
            "ORDER BY CASE WHEN artistName IS NULL OR artistName = '' THEN 0 ELSE 1 END, " +
            "CASE WHEN albumName IS NULL OR albumName = '' THEN 0 ELSE 1 END, " +
            "CASE WHEN year IS NULL OR year = 0 THEN 0 ELSE 1 END, id LIMIT :limit"
    )
    suspend fun getNetworkSongsForSync(limit: Int): List<SongEntity>

    /** 自动同步元数据：统计网络源中仍缺元数据（artist/album/year 任一空）的歌曲数。 */
    @Query(
        "SELECT COUNT(*) FROM songs WHERE sourceType IN ('WEBDAV','SMB') AND " +
            "(artistName IS NULL OR artistName = '' OR albumName IS NULL OR albumName = '' OR year IS NULL OR year = 0)"
    )
    suspend fun countNetworkSongsMissingMetadata(): Int

    /**
     * 自动同步元数据（fill-in 策略）：仅当 DB 字段为空/0 时用文件内嵌标签填充，
     * 不覆盖已有值（含用户手工匹配 / 在线元数据结果）。返回受影响行数。
     */
    @Query(
        "UPDATE songs SET " +
            "artistName = COALESCE(artistName, :artist), " +
            "albumName = COALESCE(albumName, :album), " +
            "year = COALESCE(year, :year), " +
            "genre = COALESCE(genre, :genre), " +
            "trackNumber = CASE WHEN :track IS NOT NULL AND trackNumber = 0 THEN :track ELSE trackNumber END " +
            "WHERE id = :id"
    )
    suspend fun fillTagsFromFile(
        id: Long,
        artist: String?,
        album: String?,
        year: Int?,
        genre: String?,
        track: Int?
    ): Int

    /** 2026-08-19：标题清洗——用真实歌名/歌手覆盖文件名回退的 title（仅文件名风格标题会触发）。 */
    @Query("UPDATE songs SET title = :title, artistName = COALESCE(:artist, artistName) WHERE id = :id")
    suspend fun updateTitleArtist(id: Long, title: String, artist: String?): Int

    /** 回填真实时长（远程来源首次播放后由播放器写入，避免列表恒显 0:00）。 */
    @Query("UPDATE songs SET durationMs = :ms WHERE id = :id AND (durationMs <= 0 OR durationMs != :ms)")
    suspend fun setDurationMs(id: Long, ms: Long)

    /** 最近播放（智能列表）。 */
    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY lastPlayedMs DESC")
    fun observeRecentlyPlayed(): Flow<List<SongEntity>>

    /** 最常播放（智能列表）。 */
    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedMs DESC")
    fun observeMostPlayed(): Flow<List<SongEntity>>

    @Query("DELETE FROM songs WHERE sourceType = :sourceType")
    suspend fun deleteBySource(sourceType: String)

    /** 2026-08-19 需求5：删除某网络源的全部曲目——按 uri 前缀精确匹配（instr 避免 LIKE 通配符误伤 URL 编码路径）。 */
    @Query("DELETE FROM songs WHERE sourceType = :sourceType AND instr(uri, :uriPrefix) = 1")
    suspend fun deleteByUriPrefix(sourceType: String, uriPrefix: String)

    /** CD-删源前取命中 id 列表，供清理歌单孤儿条目引用。 */
    @Query("SELECT id FROM songs WHERE sourceType = :sourceType AND instr(uri, :uriPrefix) = 1")
    suspend fun getIdsByUriPrefix(sourceType: String, uriPrefix: String): List<Long>

    /** F1-1 删源兜底：按 dedupKey 前缀取命中 id。ZT/base 改写 host 后旧曲目 uri 前缀匹配不到，
     *  而 dedupKey 保留扫描键（可能未被改写），用同一源根前缀匹配 dedupKey 仍可命中，避免删源残留。 */
    @Query("SELECT id FROM songs WHERE sourceType = :sourceType AND instr(dedupKey, :prefix) = 1")
    suspend fun getIdsByDedupKeyPrefix(sourceType: String, prefix: String): List<Long>

    /** P1-4：按来源取轻量键（id + dedupKey），供扫描后清理失效曲目。 */
    @Query("SELECT id, dedupKey FROM songs WHERE sourceType = :sourceType")
    suspend fun getIdsAndDedupKeysBySource(sourceType: String): List<SourceSongKey>

    /** 需求：按源根前缀取某网络源的轻量键（instr=1 前缀匹配，避免 LIKE 通配符误伤 URL/路径）；供网源失效清理。 */
    @Query("SELECT id, dedupKey FROM songs WHERE sourceType = :sourceType AND instr(dedupKey, :prefix) = 1")
    suspend fun getIdsAndDedupKeysByPrefix(sourceType: String, prefix: String): List<SourceSongKey>

    /** P1-4：按 id 批量删除（分块调用，规避 SQLite 变量数上限）。 */
    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** P2-6：聚合表重建后回写 songs.albumId（按 name+artistName 匹配新主键，避免外键失效）。 */
    @Query(
        "UPDATE songs SET albumId = (" +
            "SELECT a.id FROM albums a WHERE a.name = songs.albumName " +
            "AND (a.artistName = songs.artistName OR (a.artistName IS NULL AND songs.artistName IS NULL))" +
            ") WHERE albumName IS NOT NULL AND albumName != ''"
    )
    suspend fun rewriteAlbumIds()

    /** P2-6：聚合表重建后回写 songs.artistId（按 name 匹配新主键）。 */
    @Query(
        "UPDATE songs SET artistId = (SELECT ar.id FROM artists ar WHERE ar.name = songs.artistName)" +
            " WHERE artistName IS NOT NULL AND artistName != ''"
    )
    suspend fun rewriteArtistIds()

    @Query("DELETE FROM songs")
    suspend fun clear()

    /** 每张专辑的首个有效封面（用于专辑网格封面回填）。 */
    @Query(
        "SELECT albumName, artistName, albumArtUri FROM songs " +
            "WHERE albumArtUri IS NOT NULL AND albumArtUri != '' " +
            "GROUP BY albumName, artistName"
    )
    fun observeAlbumCovers(): Flow<List<AlbumCoverRow>>

    // ===== F3-4：重复曲目检测 =====
    /**
     * 按「规范化 标题+歌手 + 时长(2 秒桶)」聚合成重复组（仅收录 title 非空且时长已知的曲目，
     * 避免同名单曲误并）。每组返回标题/歌手 + 成员 id 列表（char(1) 分隔，可再 getByIds 取完整行）。
     */
    @Query(
        "SELECT lower(trim(coalesce(title,''))||char(0)||trim(coalesce(artistName,''))) AS dkey, " +
            "MAX(title) AS title, MAX(artistName) AS artist, GROUP_CONCAT(id, char(1)) AS ids " +
            "FROM songs " +
            "WHERE title IS NOT NULL AND trim(title) != '' AND durationMs > 0 " +
            "GROUP BY lower(trim(coalesce(title,''))||char(0)||trim(coalesce(artistName,''))), (durationMs/1000)/2 " +
            "HAVING COUNT(*) > 1 ORDER BY title COLLATE LOCALIZED"
    )
    fun observeDuplicateGroups(): Flow<List<DuplicateGroupRow>>

    @Query(
        "SELECT lower(trim(coalesce(title,''))||char(0)||trim(coalesce(artistName,''))) AS dkey, " +
            "MAX(title) AS title, MAX(artistName) AS artist, GROUP_CONCAT(id, char(1)) AS ids " +
            "FROM songs " +
            "WHERE title IS NOT NULL AND trim(title) != '' AND durationMs > 0 " +
            "GROUP BY lower(trim(coalesce(title,''))||char(0)||trim(coalesce(artistName,''))), (durationMs/1000)/2 " +
            "HAVING COUNT(*) > 1 ORDER BY title COLLATE LOCALIZED"
    )
    suspend fun findDuplicateGroupsOnce(): List<DuplicateGroupRow>
}

/** 专辑封面回填行。 */
data class AlbumCoverRow(
    val albumName: String? = null,
    val artistName: String? = null,
    val albumArtUri: String? = null
)

/** [8] 合并组主行：@Embedded 主曲目 + 该组的 SQL 合并键（与备选行的 mergeKey 精确匹配）。 */
data class MergedPrimaryRow(
    @Embedded val song: SongEntity,
    val mergeKey: String
)

/** [8] 合并组备选行：组内全部行（含主行）的 uri/sourceType/id，三列按 char(1) 分隔且位置对齐。 */
data class MergedAltRow(
    val mergeKey: String,
    val uris: String,
    val types: String,
    val ids: String
)

/** P1-4：扫描清理时使用的轻量行（仅 id + dedupKey）。 */
data class SourceSongKey(
    val id: Long,
    val dedupKey: String
)

/** F3-4：重复曲目组行。ids 为组内成员 id 以 char(1) 分隔，需再 getByIds 展开完整行。 */
data class DuplicateGroupRow(
    val dkey: String,
    val title: String?,
    val artist: String?,
    val ids: String
)

/** F2-3：播放统计概览行（SQL 聚合结果）。 */
data class PlayStatsRow(
    val totalSongs: Int,
    val totalPlays: Long,
    val playedSongs: Int
)
