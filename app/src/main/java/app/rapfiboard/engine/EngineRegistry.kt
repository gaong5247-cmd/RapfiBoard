package app.rapfiboard.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** External code remains in Android's package-managed, read-only nativeLibraryDir.
 * Imported writable ELF files are never chmod/exec'd to evade Android W^X.
 */
class EngineRegistry(private val context:Context) {
    fun resolve(packageName:String,library:String):EngineSpec {
        require(Regex("[a-zA-Z][a-zA-Z0-9_.]+").matches(packageName)) { "패키지 이름이 올바르지 않아요." }
        require(Regex("lib[a-zA-Z0-9_-]+\\.so").matches(library)) { "lib이름.so 형식이어야 해요." }
        val info=context.packageManager.getApplicationInfo(packageName,0)
        val file=File(info.nativeLibraryDir,library)
        require(file.isFile && file.canExecute()) { "설치된 엔진 APK에서 실행 파일을 찾을 수 없어요. 추출된 PIE 실행 파일을 제공하는 APK가 필요해요." }
        RandomAccessFile(file,"r").use { raf ->
            val header=ByteArray(64);raf.readFully(header)
            require(header.take(4)==listOf<Byte>(0x7f,0x45,0x4c,0x46) && header[5].toInt()==1) { "지원하지 않는 ELF 형식이에요." }
            val buffer=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val is64=header[4].toInt()==2;val machine=buffer.getShort(18).toInt()
            val abi=when(machine){183->"arm64-v8a";40->"armeabi-v7a";62->"x86_64";else->"unsupported"}
            require(abi in Build.SUPPORTED_ABIS) { "기기와 엔진 ABI가 다릅니다: $abi" }
            val offset=if(is64) buffer.getLong(32) else buffer.getInt(28).toLong() and 0xffffffffL
            val size=buffer.getShort(if(is64)54 else 42).toInt() and 0xffff
            val count=buffer.getShort(if(is64)56 else 44).toInt() and 0xffff
            require(count in 1..128 && size in 32..128 && offset>=0 && offset+size*count<=raf.length())
            val ph=ByteArray(size);var interpreter=false
            repeat(count) { i -> raf.seek(offset+i*size);raf.readFully(ph);if(ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN).int==3) interpreter=true }
            require(interpreter) { "이 파일은 독립 실행 엔진이 아니에요 (PT_INTERP 없음)." }
        }
        return EngineSpec(id="$packageName/$library",name=packageName,executable=file.absolutePath,rapfiExtensions=false)
    }
}
