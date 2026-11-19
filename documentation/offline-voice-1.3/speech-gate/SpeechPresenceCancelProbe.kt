import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.pocketai.voice.SpeechPresenceKernel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    val model = File(args[0])
    val payload = File(args[1]).readBytes()
    val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val pcm = FloatArray(payload.size / 2) { data.short / 32768f }
    val cancelled = AtomicBoolean(false)
    var nativeTermination = false
    var callbackTermination = false
    var closedRejected = false
    var recovery = false
    try {
        OrtSession.RunOptions().use { options ->
            val kernel = SpeechPresenceKernel(model, options) {
                if (cancelled.get()) throw CancellationException("Caller cancelled speech gate.")
            }
            kernel.use {
                options.setTerminate(true)
                try { kernel.hasSpeech(pcm) } catch (_: OrtException) { nativeTermination = true }
                check(nativeTermination) { "Native RunOptions termination did not reject inference." }
                options.setTerminate(false)
                cancelled.set(true)
                try { kernel.hasSpeech(pcm) } catch (_: CancellationException) { callbackTermination = true }
                check(callbackTermination)
                cancelled.set(false)
                recovery = kernel.hasSpeech(pcm)
                check(recovery) { "The same session could not recover after cancellation reset." }
            }
            try { kernel.hasSpeech(pcm) } catch (_: IllegalStateException) { closedRejected = true }
            check(closedRejected)
        }
        File(args[2]).writeText("""{"native_runoptions_termination":$nativeTermination,"caller_callback_cancellation":$callbackTermination,"same_session_recovery":$recovery,"use_after_close_rejected":$closedRejected,"passed":true}""")
    } finally { pcm.fill(0f); payload.fill(0) }
}
