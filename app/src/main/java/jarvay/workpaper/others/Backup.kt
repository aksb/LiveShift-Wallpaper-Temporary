package jarvay.workpaper.others

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStoreFile
import com.blankj.utilcode.util.LogUtils
import jarvay.workpaper.data.AppDatabase
import jarvay.workpaper.data.preferences.DEFAULT_PREFERENCES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份/恢复：只打包"数据库文件 + DataStore 设置文件"这两样东西本身，
 * 不做逐字段的 JSON 序列化。好处是天然完整（相册/壁纸/规则/滤镜/阅读进度/
 * 阅读器缩放位置——只要是存在这两处的，一次性全带上，不用跟着数据库表结构
 * 的变化同步维护一份 JSON 映射）、代码量小、不容易出 bug。
 * 坏处是完全不能跨版本/跨机型做字段级别的兼容处理，但对于"同一个 App 在
 * 同一台或者换了台设备上恢复自己的备份"这个场景足够用了。
 *
 * 不备份图片本身——相册指向的文件夹权限（SAF 授权）本来就是跟安装绑定的，
 * 换设备/重装之后这部分权限会失效，需要用户自己重新选一次文件夹，恢复出来的
 * 相册记录会先保留着，等用户重新选完文件夹、扫描到同名文件时才能对上。
 *
 * 核心打包/解包逻辑抽成 [writeBackupZip]/[readBackupZip]，直接对着
 * InputStream/OutputStream 操作，不关心数据来自本地 SAF 文件还是 WebDAV
 * 网络请求的 body——本地导出导入（[export]/[import]）和 WebDAV 同步
 * （见 [WebDavManager]）复用同一份实现，行为完全一致。
 */
object BackupManager {
    private val DB_SIDE_FILE_SUFFIXES = listOf("", "-wal", "-shm")

    private fun writeBackupZip(context: Context, out: OutputStream) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_FILE)
        val prefsFile = context.preferencesDataStoreFile(DEFAULT_PREFERENCES)

        ZipOutputStream(out).use { zip ->
            DB_SIDE_FILE_SUFFIXES.forEach { suffix ->
                val file = File(dbFile.absolutePath + suffix)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (prefsFile.exists()) {
                zip.putNextEntry(ZipEntry(prefsFile.name))
                prefsFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 恢复完不会立刻重新读数据库/设置——两者都是进程级单例，运行中直接换底层
     * 文件不安全。这里只是把文件落盘，调用方需要在恢复成功后引导用户调用
     * [restartApp] 重启一次 App 进程，才会真正读到新数据。
     */
    private fun readBackupZip(context: Context, input: InputStream) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_FILE)
        val prefsFile = context.preferencesDataStoreFile(DEFAULT_PREFERENCES)
        val dbDir = dbFile.parentFile
        val prefsDir = prefsFile.parentFile

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                // 只认识备份自己写进去的这几个文件名，不接受带路径的条目，
                // 避免万一有人拿构造过的 zip 冒充备份文件搞路径穿越。
                if (!name.contains('/') && !name.contains("..")) {
                    val targetDir = if (name == prefsFile.name) prefsDir else dbDir
                    val targetFile = File(targetDir, name)
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 恢复前把当前持有的数据库连接关掉，避免刚写进去的文件被旧连接的
        // 缓存/未提交事务弄乱；反正接下来就要重启整个进程了。
        AppDatabase.closeInstance()
    }

    suspend fun export(context: Context, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                writeBackupZip(context, out)
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            LogUtils.e(LOG_TAG, "backup export failed", e)
            false
        }
    }

    suspend fun import(context: Context, srcUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                readBackupZip(context, input)
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            LogUtils.e(LOG_TAG, "backup import failed", e)
            false
        }
    }

    /** 打包成内存里的 zip 字节数组，给 WebDAV 上传用，不落地本地文件。 */
    suspend fun exportBytes(context: Context): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val out = java.io.ByteArrayOutputStream()
            writeBackupZip(context, out)
            out.toByteArray()
        } catch (e: Exception) {
            LogUtils.e(LOG_TAG, "backup exportBytes failed", e)
            null
        }
    }

    /** 从 WebDAV 下载下来的 zip 字节数组直接恢复，不落地本地文件。 */
    suspend fun importBytes(context: Context, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                java.io.ByteArrayInputStream(bytes).use { input ->
                    readBackupZip(context, input)
                }
                true
            } catch (e: Exception) {
                LogUtils.e(LOG_TAG, "backup importBytes failed", e)
                false
            }
        }

    /**
     * 标准的"重启整个 App 进程"写法：拉起一个全新的启动 Activity 任务，
     * 然后直接杀掉当前进程。这样所有 Hilt 单例（数据库连接、DataStore）
     * 都会在新进程里重新初始化，干净地读到刚恢复的文件。
     */
    fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            val restartIntent = Intent.makeRestartActivityTask(intent.component)
            context.startActivity(restartIntent)
        }
        Runtime.getRuntime().exit(0)
    }
}
