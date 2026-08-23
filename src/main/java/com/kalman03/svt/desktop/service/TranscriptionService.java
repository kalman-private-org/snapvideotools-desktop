package com.kalman03.svt.desktop.service;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.k2fsa.sherpa.onnx.WaveReader;
import com.kalman03.svt.desktop.enums.TranscriptionStatus;
import com.kalman03.svt.desktop.model.TranscriptionSegment;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 串行执行本地语音识别，避免多个大模型推理同时争抢内存和 CPU。
 */
@Slf4j
@Service
public class TranscriptionService {

    private static final int SAMPLE_RATE = 16_000;
    private static final int VAD_WINDOW_SIZE = 512;
    private static final Pattern CONTROL_TAG = Pattern.compile("<\\|[^|>]+\\|>");

    private final SpeechModelService speechModelService;
    private final FFmpegService ffmpegService;
    private final ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sensevoice-transcription");
        thread.setDaemon(true);
        return thread;
    });

    public TranscriptionService(SpeechModelService speechModelService, FFmpegService ffmpegService) {
        this.speechModelService = speechModelService;
        this.ffmpegService = ffmpegService;
    }

    public CompletableFuture<Result> transcribe(Path mediaPath, Path txtPath, Path srtPath,
                                                Consumer<Progress> progressListener,
                                                BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                notify(progressListener, TranscriptionStatus.WAITING_MODEL, 0);
                speechModelService.ensureReady().join();
                checkCancelled(cancelled);

                notify(progressListener, TranscriptionStatus.PREPARING_AUDIO, 2);
                Path tempDir = Path.of(System.getProperty("user.home"), ".snapvideotools", "temp", "transcription");
                Files.createDirectories(tempDir);
                Path wavePath = tempDir.resolve("task-" + Thread.currentThread().threadId() + "-"
                        + System.nanoTime() + ".wav");
                try {
                    if (!ffmpegService.prepareTranscriptionAudio(mediaPath, wavePath)) {
                        throw new IOException("Unable to prepare audio for transcription");
                    }
                    checkCancelled(cancelled);
                    notify(progressListener, TranscriptionStatus.TRANSCRIBING, 5);
                    List<TranscriptionSegment> segments = recognizeWave(wavePath, progressListener, cancelled);
                    writeOutputs(txtPath, srtPath, segments);
                    notify(progressListener, TranscriptionStatus.COMPLETED, 100);
                    return new Result(txtPath, srtPath, segments.isEmpty());
                } finally {
                    Files.deleteIfExists(wavePath);
                }
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, transcriptionExecutor);
    }

    private List<TranscriptionSegment> recognizeWave(Path wavePath, Consumer<Progress> progressListener,
                                                     BooleanSupplier cancelled) {
        WaveReader reader = new WaveReader(wavePath.toString());
        if (reader.getSampleRate() != SAMPLE_RATE) {
            throw new IllegalStateException("Unexpected audio sample rate: " + reader.getSampleRate());
        }
        float[] allSamples = reader.getSamples();
        List<TranscriptionSegment> results = new ArrayList<>();
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(speechModelService.getVadFile().toString())
                .setThreshold(0.5f)
                .setMinSilenceDuration(0.5f)
                .setMinSpeechDuration(0.25f)
                .setWindowSize(VAD_WINDOW_SIZE)
                .setMaxSpeechDuration(30.0f)
                .build();
        VadModelConfig vadConfig = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
                .build();
        Vad vad = new Vad(vadConfig);
        try {
            for (int start = 0; start < allSamples.length; start += VAD_WINDOW_SIZE) {
                checkCancelled(cancelled);
                int end = Math.min(start + VAD_WINDOW_SIZE, allSamples.length);
                float[] window = Arrays.copyOfRange(allSamples, start, start + VAD_WINDOW_SIZE);
                if (end - start < VAD_WINDOW_SIZE) {
                    Arrays.fill(window, end - start, VAD_WINDOW_SIZE, 0f);
                }
                vad.acceptWaveform(window);
                drainSegments(vad, results, cancelled);
                double ratio = allSamples.length == 0 ? 1 : (double) end / allSamples.length;
                notify(progressListener, TranscriptionStatus.TRANSCRIBING, 5 + ratio * 90);
            }
            vad.flush();
            drainSegments(vad, results, cancelled);
        } finally {
            vad.release();
        }
        return results;
    }

    private void drainSegments(Vad vad, List<TranscriptionSegment> results, BooleanSupplier cancelled) {
        while (!vad.empty()) {
            checkCancelled(cancelled);
            SpeechSegment segment = vad.front();
            try {
                String text = cleanText(speechModelService.recognize(segment.getSamples(), SAMPLE_RATE));
                if (!text.isBlank()) {
                    double start = (double) segment.getStart() / SAMPLE_RATE;
                    double end = start + (double) segment.getSamples().length / SAMPLE_RATE;
                    results.add(new TranscriptionSegment(start, end, text));
                }
            } finally {
                vad.pop();
            }
        }
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return CONTROL_TAG.matcher(text).replaceAll("").trim();
    }

    private void writeOutputs(Path txtPath, Path srtPath, List<TranscriptionSegment> segments) throws IOException {
        Files.createDirectories(txtPath.getParent());
        String txt = String.join(System.lineSeparator(), segments.stream()
                .map(TranscriptionSegment::text).toList());
        StringBuilder srt = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            TranscriptionSegment segment = segments.get(index);
            srt.append(index + 1).append(System.lineSeparator())
                    .append(formatSrtTime(segment.startSeconds())).append(" --> ")
                    .append(formatSrtTime(segment.endSeconds())).append(System.lineSeparator())
                    .append(segment.text()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        writeAtomically(txtPath, txt);
        writeAtomically(srtPath, srt.toString());
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(part, content, StandardCharsets.UTF_8);
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String formatSrtTime(double seconds) {
        long millis = Math.max(0, Math.round(seconds * 1000));
        long hours = millis / 3_600_000;
        millis %= 3_600_000;
        long minutes = millis / 60_000;
        millis %= 60_000;
        long secs = millis / 1000;
        long remainder = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, secs, remainder);
    }

    private void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new CancellationException("Transcription cancelled");
        }
    }

    private void notify(Consumer<Progress> listener, TranscriptionStatus status, double progress) {
        if (listener != null) {
            listener.accept(new Progress(status, Math.max(0, Math.min(100, progress))));
        }
    }

    /** 清空队列后只移除临时 WAV，不触碰模型和已下载媒体。 */
    public void clearTemporaryFiles() {
        Path tempDir = Path.of(System.getProperty("user.home"), ".snapvideotools", "temp", "transcription");
        if (!Files.isDirectory(tempDir)) {
            return;
        }
        try (var paths = Files.list(tempDir)) {
            for (Path path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Failed to clear transcription temporary files", e);
        }
    }

    @PreDestroy
    public void destroy() {
        transcriptionExecutor.shutdownNow();
    }

    public record Progress(TranscriptionStatus status, double progress) {
    }

    public record Result(Path txtPath, Path srtPath, boolean noSpeechDetected) {
    }
}
