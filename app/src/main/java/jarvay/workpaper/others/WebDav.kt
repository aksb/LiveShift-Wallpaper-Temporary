package jarvay.workpaper.others

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blankj.utilcode.util.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class WebDavCredentials(
    val url: String,
    val username: String,
    val password: String,
)

/**
 * WebDAV 同步：只做"手动立即上传/立即下载"，不做定时自动同步，也不做增量/
 * 合并——直接复用 [BackupManager] 那份本地备份用的 zip（数据库+DataStore
 * 设置整个打包），上传就是把这个 zip PUT 到 [url] 指向的这一个文件上，
 * 下载就是 GET 下来直接整个覆盖恢复。冲突处理策略是"强制覆盖"：不比较
 * 谁更新，谁最后操作就是谁说了算，别的设备/上次备份一律被覆盖掉——
 * 数据量本来就很小、也不是很重要，没必要为了这个做复杂的版本比对。
 *
 * 账号密码不能明文存 DataStore，这里单独用 [EncryptedSharedPreferences]
 * （底层是 Android Keystore 包一层密钥）存到自己的一个独立文件里，
 * 跟其它设置分开，也不走本地备份/WebDAV 同步（避免密码明文出现在
 * 备份 zip 或者传到 WebDAV 服务器上）。
 */
object WebDavManager {
    private const val PREFS_FILE_NAME = "webdav_credentials"
    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun encryptedPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore 极少数机型/被清过密钥的情况下可能损坏、打不开——这种情况下
        // 没法安全地读旧密码了，删掉旧文件重建一份空的，让用户重新填一次，
        // 总比直接崩溃或者一直报错强。
        LogUtils.e(LOG_TAG, "open encrypted webdav prefs failed, recreating", e)
        context.deleteSharedPreferences(PREFS_FILE_NAME)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveCredentials(context: Context, url: String, username: String, password: String) {
        encryptedPrefs(context).edit()
            .putString(KEY_URL, url)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getCredentials(context: Context): WebDavCredentials? {
        val prefs = encryptedPrefs(context)
        val url = prefs.getString(KEY_URL, null) ?: return null
        if (url.isBlank()) return null
        val username = prefs.getString(KEY_USERNAME, null) ?: ""
        val password = prefs.getString(KEY_PASSWORD, null) ?: ""
        return WebDavCredentials(url, username, password)
    }

    fun clearCredentials(context: Context) {
        encryptedPrefs(context).edit().clear().apply()
    }

    /** 立即上传：把当前数据库+设置打包成 zip，PUT 到配置的 WebDAV 地址，直接覆盖远端同名文件。 */
    suspend fun upload(context: Context): Boolean = withContext(Dispatchers.IO) {
        val credentials = getCredentials(context) ?: return@withContext false
        try {
            val bytes = BackupManager.exportBytes(context) ?: return@withContext false
            val request = Request.Builder()
                .url(credentials.url)
                .header("Authorization", Credentials.basic(credentials.username, credentials.password))
                .put(bytes.toRequestBody("application/zip".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            LogUtils.e(LOG_TAG, "webdav upload failed", e)
            false
        }
    }

    /** 立即下载：从配置的 WebDAV 地址 GET 下来，直接整个覆盖恢复本地数据库+设置。 */
    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        val credentials = getCredentials(context) ?: return@withContext false
        try {
            val request = Request.Builder()
                .url(credentials.url)
                .header("Authorization", Credentials.basic(credentials.username, credentials.password))
                .get()
                .build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.bytes()
            } ?: return@withContext false
            BackupManager.importBytes(context, bytes)
        } catch (e: Exception) {
            LogUtils.e(LOG_TAG, "webdav download failed", e)
            false
        }
    }
}
