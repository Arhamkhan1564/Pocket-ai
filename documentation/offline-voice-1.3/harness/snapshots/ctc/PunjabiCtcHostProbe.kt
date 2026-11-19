import com.pocketai.voice.PunjabiCtcKernel
import com.pocketai.voice.PunjabiCtcFrames
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val bytes = File(args[2]).readBytes()
    require(bytes.size % 2 == 0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val audio = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
    val output = File(args[3]).also { it.mkdirs() }
    val normalized = PunjabiCtcFrames.normalize(audio)
    val normalizedBytes = ByteBuffer.allocate(normalized.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    normalized.forEach { normalizedBytes.putFloat(it) }
    File(output, "normalized.f32le").writeBytes(normalizedBytes.array())
    if (args.size > 4 && args[4] == "normalize-only") return
    val started = System.nanoTime()
    PunjabiCtcKernel(File(args[0]), Files.readAllLines(Path.of(args[1])), 1).use { kernel ->
        val result = kernel.transcribe(audio)
        File(output, "transcript.txt").writeText(result.text)
        val frames = ByteBuffer.allocate(result.frameIds.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        result.frameIds.forEach { frames.putInt(it) }
        File(output, "frames.i32le").writeBytes(frames.array())
        File(output, "stats.txt").writeText("windows=${result.windowCount}\nframes=${result.frameIds.size}\nwall_seconds=${(System.nanoTime() - started) / 1e9}\n")
        println(result.text)
    }
}
