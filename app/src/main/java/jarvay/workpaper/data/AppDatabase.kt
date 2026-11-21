package jarvay.workpaper.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import jarvay.workpaper.data.album.Album
import jarvay.workpaper.data.album.AlbumDao
import jarvay.workpaper.data.rule.Rule
import jarvay.workpaper.data.rule.RuleAlbumRelation
import jarvay.workpaper.data.rule.RuleAlbumRelationDao
import jarvay.workpaper.data.rule.RuleDao
import jarvay.workpaper.data.style.Style
import jarvay.workpaper.data.style.StyleDao
import jarvay.workpaper.data.wallpaper.Wallpaper
import jarvay.workpaper.data.wallpaper.WallpaperDao

@Database(
    entities = [
        Rule::class,
        Album::class,
        RuleAlbumRelation::class,
        Wallpaper::class,
        Style::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = AppDatabase.AutoMigration3To4::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        // wallpapers 表新增 readerScale/readerPanX/readerPanY 三列（阅读器模式用），
        // 都带默认值，Room 能自动生成迁移，不用手写。
        AutoMigration(from = 7, to = 8),
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun albumDao(): AlbumDao

    abstract fun ruleAlbumRelationDao(): RuleAlbumRelationDao

    abstract fun wallpaperDao(): WallpaperDao

    abstract fun styleDao(): StyleDao

    companion object {
        // 备份/恢复功能要直接复制这个数据库文件，所以把文件名暴露成 public。
        const val DB_FILE = "workpaper.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: getRoomDatabase(getDatabaseBuilder(context)).also { instance = it }
            }
        }

        /**
         * 恢复备份前调用：关掉当前持有的数据库连接，避免往正在被读写的
         * db 文件上直接覆盖导致损坏。恢复流程会在这之后很快重启整个 App 进程，
         * 所以这里不需要考虑"关掉之后还要继续用"的情况。
         */
        @Synchronized
        fun closeInstance() {
            instance?.close()
            instance = null
        }

        private fun getDatabaseBuilder(ctx: Context): Builder<AppDatabase> {
            val appContext = ctx.applicationContext
            val dbFile = appContext.getDatabasePath(DB_FILE)
            return Room.databaseBuilder(
                context = appContext,
                klass = AppDatabase::class.java,
                name = dbFile.absolutePath
            )
        }

        private fun getRoomDatabase(builder: Builder<AppDatabase>): AppDatabase {
            return builder
                .allowMainThreadQueries()
                .addMigrations(
                    Migration_1_2
                )
                .build()
        }
    }

    @DeleteColumn("rules", "blurRadius")
    @DeleteColumn("rules", "replaceGlobalBlur")
    class AutoMigration3To4 : AutoMigrationSpec
}