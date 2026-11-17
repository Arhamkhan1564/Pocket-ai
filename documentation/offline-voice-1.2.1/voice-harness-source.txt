package com.pocketai.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scratch host harness for the production JNI transcription entry point. */
public final class NativeBridge {
    private native boolean supportsInferenceNative();
    private native byte[] transcribeNative(String modelPath, float[] audio, String language, int threads);

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("library, model, language, WAV, JSON path required");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        NativeBridge bridge = new NativeBridge();
        if (!bridge.supportsInferenceNative()) throw new IllegalStateException("Host inference capability unavailable");
        float[] audio = readPcmWave(Path.of(args[3]));
        long began = System.nanoTime();
        String transcript = new String(bridge.transcribeNative(args[1], audio, args[2], 2), StandardCharsets.UTF_8).trim();
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        String record = "{\"transcript\":\"" + escape(transcript) + "\",\"language_argument\":\"" + escape(args[2]) +
            "\",\"audio_samples\":" + audio.length + ",\"elapsed_seconds\":" + seconds + ",\"threads\":2}";
        Files.writeString(Path.of(args[4]), record + "\n", StandardCharsets.UTF_8);
        System.out.println(record);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c);
            }
        }
        return out.toString();
    }

    private static float[] readPcmWave(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 44 || data.getInt(0) != 0x46464952 || data.getInt(8) != 0x45564157)
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
                    throw new IllegalArgumentException("Test WAV must be mono PCM16 at16kHz");
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
