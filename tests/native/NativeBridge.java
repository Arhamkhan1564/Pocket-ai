package com.pocketai.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Host JNI smoke harness; model weights are test inputs, never application assets. */
public final class NativeBridge {
    private native boolean supportsInferenceNative();
    private native byte[] generateNative(String modelPath, byte[] system, byte[] prompt,
                                        int contextSize, int maxTokens, int threads);
    private native byte[] transcribeNative(String modelPath, float[] audio, String language, int threads);
    private native void cancelNative();

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected library, tiny-GGUF, Whisper-model, 16k-mono-WAV paths");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        NativeBridge bridge = new NativeBridge();
        if (!bridge.supportsInferenceNative()) throw new AssertionError("Host should support inference");
        float[] audio = readPcmWave(Path.of(args[3]));
        String initialTranscript = new String(bridge.transcribeNative(args[2], audio, "en", 2), StandardCharsets.UTF_8);
        if (!initialTranscript.toLowerCase(Locale.ROOT).contains("country"))
            throw new AssertionError("Whisper before LLM initialization failed: " + initialTranscript);
        System.out.println("PASS Whisper before LLM initialization");
        String answer = new String(bridge.generateNative(args[1], new byte[0],
                "Once upon a time".getBytes(StandardCharsets.UTF_8), 512, 48, 2), StandardCharsets.UTF_8);
        if (answer.isBlank() || answer.length() < 10) throw new AssertionError("GGUF generation returned no usable output");
        System.out.println("PASS GGUF generation: " + answer.replace('\n', ' ').substring(0, Math.min(answer.length(), 180)));

        boolean rejected = false;
        try {
            bridge.generateNative(args[1] + ".missing", new byte[0], "Hello".getBytes(StandardCharsets.UTF_8), 512, 16, 1);
        } catch (IllegalStateException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Missing model was not rejected");
        System.out.println("PASS missing-model recovery");

        String transcript = new String(bridge.transcribeNative(args[2], audio, "en", 2), StandardCharsets.UTF_8);
        String normalized = transcript.toLowerCase(Locale.ROOT);
        if (!normalized.contains("country") || !normalized.contains("ask"))
            throw new AssertionError("Whisper transcription failed known-speech check: " + transcript);
        System.out.println("PASS Whisper transcription: " + transcript.trim());

        rejected = false;
        try { bridge.transcribeNative(args[2], new float[10], "en", 1); }
        catch (IllegalStateException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Short audio was not rejected");
        System.out.println("PASS invalid-audio recovery");
        System.out.println("PASS native sequential load/unload: speech, generation, failed load, speech, failed speech");
    }

    private static float[] readPcmWave(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt(0) != 0x46464952 || data.getInt(8) != 0x45564157)
            throw new IllegalArgumentException("Expected RIFF/WAVE");
        int offset = 12;
        boolean formatFound = false;
        while (offset + 8 <= bytes.length) {
            int id = data.getInt(offset);
            int length = data.getInt(offset + 4);
            if (length < 0 || offset + 8L + length > bytes.length) throw new IllegalArgumentException("Invalid WAV chunk");
            if (id == 0x20746d66) {
                if (length < 16 || data.getShort(offset + 8) != 1 || data.getShort(offset + 10) != 1 ||
                    data.getInt(offset + 12) != 16000 || data.getShort(offset + 22) != 16)
                    throw new IllegalArgumentException("Test WAV must be mono PCM16 16kHz");
                formatFound = true;
            }
            if (id == 0x61746164 && formatFound) {
                float[] samples = new float[length / 2];
                for (int i = 0; i < samples.length; i++) samples[i] = data.getShort(offset + 8 + i * 2) / 32768f;
                return samples;
            }
            offset += 8 + length + (length & 1);
        }
        throw new IllegalArgumentException("No PCM audio data in WAV");
    }
}
