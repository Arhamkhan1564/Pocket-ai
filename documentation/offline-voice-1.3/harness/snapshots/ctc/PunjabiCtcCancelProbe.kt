import com.pocketai.voice.PunjabiCtcKernel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    val model = File(args[0]); val labels = File(args[1]).readLines()
    val source = ByteBuffer.wrap(File(args[2]).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val audio = FloatArray(source.remaining()) { source.get() / 32768f }
    OrtSession.RunOptions().use { options ->
        PunjabiCtcKernel(model, labels, 1, runOptions = options).use { kernel ->
            options.setTerminate(true)
            var forwarded = false
            try { kernel.transcribe(audio) } catch (failure: OrtException) {
                forwarded = failure.message.orEmpty().contains("terminat", ignoreCase = true)
            }
            check(forwarded) { "Caller RunOptions did not terminate native inference." }
            options.setTerminate(false)
            check(kernel.transcribe(audio).frameIds.isNotEmpty())
            println("provided_run_options_terminate=true; session_reusable_after_reset=true")
        }
    }
    val cancelled = AtomicBoolean(false)
    OrtSession.RunOptions().use { options ->
        PunjabiCtcKernel(model, labels, 1, runOptions = options,
            checkCancelled = { if (cancelled.get()) throw CancellationException("Caller cancelled") }).use { kernel ->
            val longer = FloatArray(60 * 16000) { audio[it % audio.size] }
            val worker = Thread { Thread.sleep(150); cancelled.set(true); options.setTerminate(true) }
            val started = System.nanoTime()
            worker.start()
            var stopped = false
            try { kernel.transcribe(longer) } catch (_: CancellationException) { stopped = true }
            finally { worker.join() }
            check(stopped)
            println("caller_hook_cancellation=true; stop_seconds=${(System.nanoTime() - started) / 1e9}")
        }
    }
}
