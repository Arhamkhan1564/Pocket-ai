import com.pocketai.voice.SpeechPresenceDecision
import com.pocketai.voice.SpeechPresenceKernel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    require(args.size == 3)
    val model = File(args[0])
    val raw = File(args[1]).readBytes()
    require(raw.size % 2 == 0)
    val bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val samples = FloatArray(raw.size / 2) { bytes.short / 32768f }
    val out = File(args[2]).apply { mkdirs() }
    val began = System.nanoTime()
    try {
        SpeechPresenceKernel(model).use { kernel ->
            val probabilities = kernel.probabilities(samples)
            try {
                val decision = SpeechPresenceDecision.hasSpeech(probabilities, samples.size)
                check(kernel.hasSpeech(samples) == decision) { "Repeated presence call did not reset state." }
                val serialized = ByteBuffer.allocate(probabilities.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                probabilities.forEach(serialized::putFloat)
                File(out, "probabilities.f32le").writeBytes(serialized.array())
                File(out, "result.json").writeText("""{"samples":${samples.size},"frames":${probabilities.size},"has_speech":$decision,"repeat_state_reset_passed":true,"elapsed_seconds":${(System.nanoTime()-began)/1e9}}""")
            } finally { probabilities.fill(0f) }
        }
    } finally { samples.fill(0f); raw.fill(0) }
}
