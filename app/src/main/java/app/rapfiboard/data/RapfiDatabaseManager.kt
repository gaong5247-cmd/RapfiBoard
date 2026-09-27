package app.rapfiboard.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RapfiDbInfo(val installed:Boolean,val bytes:Long=0L,val sha256:String="")
data class RapfiDbTransfer(val active:Boolean=false,val progress:Float=0f,val copied:Long=0L,val total:Long=0L,val label:String="")

class RapfiDatabaseManager(private val context:Context) {
    companion object {
        const val DEFAULT_DB_URL="https://github.com/gaong5247-cmd/RapfiBoard/releases/download/rapfi-db-v1/rapfi.db"
        const val DEFAULT_SHA_URL="https://github.com/gaong5247-cmd/RapfiBoard/releases/download/rapfi-db-v1/rapfi.db.sha256"
        const val MIN_REASONABLE_DB_BYTES=100L*1024L*1024L
    }

    private val runtime=File(context.filesDir,"engine/runtime-v1").apply { mkdirs() }
    val databaseFile:File get()=File(runtime,"rapfi.db")
    private val partialFile:File get()=File(runtime,"rapfi.db.part")

    suspend fun info():RapfiDbInfo = withContext(Dispatchers.IO) {
        val f=databaseFile
        if(!f.isFile) RapfiDbInfo(false) else RapfiDbInfo(true,f.length(),"")
    }

    suspend fun downloadDefault(onProgress:(RapfiDbTransfer)->Unit):RapfiDbInfo = withContext(Dispatchers.IO) {
        val expected=downloadExpectedSha()
        val connection=(URL(DEFAULT_DB_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout=20_000; readTimeout=45_000; instanceFollowRedirects=true
            setRequestProperty("User-Agent","RapfiBoard-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "DB 다운로드 HTTP ${connection.responseCode}" }
            val total=connection.contentLengthLong.coerceAtLeast(0L)
            ensureSpace(total)
            val digest=MessageDigest.getInstance("SHA-256")
            var copied=0L
            partialFile.outputStream().buffered(1024*1024).use { out ->
                connection.inputStream.buffered(1024*1024).use { input ->
                    val buffer=ByteArray(1024*1024)
                    while(true) {
                        val n=input.read(buffer); if(n<0) break
                        out.write(buffer,0,n); digest.update(buffer,0,n); copied+=n
                        onProgress(RapfiDbTransfer(true,if(total>0) copied.toFloat()/total else 0f,copied,total,"기본 Rapfi DB 다운로드 중"))
                    }
                }
            }
            check(copied>=MIN_REASONABLE_DB_BYTES) { "다운로드된 DB가 너무 작아요 ($copied bytes)." }
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            if(expected.isNotBlank()) check(actual.equals(expected,true)) { "SHA-256 불일치. 파일이 손상됐거나 Release가 바뀌었어요." }
            installPartial()
            onProgress(RapfiDbTransfer(false,1f,copied,copied,"완료"))
            RapfiDbInfo(true,databaseFile.length(),actual)
        } catch(e:Exception) {
            partialFile.delete(); throw e
        } finally { connection.disconnect() }
    }

    suspend fun importUri(uri:Uri,onProgress:(RapfiDbTransfer)->Unit):RapfiDbInfo = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val total=runCatching { resolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        ensureSpace(total)
        val digest=MessageDigest.getInstance("SHA-256")
        var copied=0L
        try {
            resolver.openInputStream(uri).use { raw ->
                checkNotNull(raw) { "선택한 파일을 열 수 없어요." }
                raw.buffered(1024*1024).use { input ->
                    partialFile.outputStream().buffered(1024*1024).use { out ->
                        val buffer=ByteArray(1024*1024)
                        while(true) {
                            val n=input.read(buffer); if(n<0) break
                            out.write(buffer,0,n); digest.update(buffer,0,n); copied+=n
                            onProgress(RapfiDbTransfer(true,if(total>0) copied.toFloat()/total else 0f,copied,total.coerceAtLeast(0L),"로컬 Rapfi DB 가져오는 중"))
                        }
                    }
                }
            }
            check(copied>=MIN_REASONABLE_DB_BYTES) { "선택한 파일이 Rapfi DB로 보기엔 너무 작아요." }
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            installPartial()
            onProgress(RapfiDbTransfer(false,1f,copied,copied,"완료"))
            RapfiDbInfo(true,databaseFile.length(),actual)
        } catch(e:Exception) {
            partialFile.delete(); throw e
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        partialFile.delete()
        check(!databaseFile.exists() || databaseFile.delete()) { "Rapfi DB를 삭제하지 못했어요." }
    }

    private fun installPartial() {
        val target=databaseFile
        val backup=File(runtime,"rapfi.db.old"); backup.delete()
        if(target.exists()) check(target.renameTo(backup)) { "기존 DB 백업에 실패했어요." }
        if(!partialFile.renameTo(target)) {
            if(backup.exists()) backup.renameTo(target)
            error("새 DB 설치에 실패했어요.")
        }
        backup.delete()
    }

    private fun ensureSpace(total:Long) {
        if(total<=0) return
        val required=total+128L*1024L*1024L
        check(StatFs(runtime.absolutePath).availableBytes>required) { "저장공간이 부족해요. 약 ${human(required)} 이상 확보해 주세요." }
    }

    private fun downloadExpectedSha():String {
        val c=(URL(DEFAULT_SHA_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout=10_000; readTimeout=10_000; instanceFollowRedirects=true
            setRequestProperty("User-Agent","RapfiBoard-Android")
        }
        return try {
            c.connect()
            if(c.responseCode !in 200..299) ""
            else c.inputStream.bufferedReader().use { it.readText() }.trim().split(Regex("\\s+")).firstOrNull()
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } ?: ""
        } catch(_:Exception) { "" } finally { c.disconnect() }
    }

    private fun human(bytes:Long):String {
        val mb=bytes/1024.0/1024.0
        return if(mb<1024) "%.1f MB".format(mb) else "%.2f GB".format(mb/1024.0)
    }
}
