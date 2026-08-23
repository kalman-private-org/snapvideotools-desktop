package com.kalman03.svt.desktop.service;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.kalman03.svt.desktop.enums.SpeechModelStatus;
import com.kalman03.svt.desktop.enums.SpeechModelFailureReason;
import com.kalman03.svt.desktop.model.SpeechModelSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

/**
 * 管理 SenseVoice-Small 的下载、校验和单例识别器。
 */
@Slf4j
@Service
public class SpeechModelService {

    public static final String MODEL_NAME = "SenseVoice-Small";
    public static final long MODEL_DOWNLOAD_BYTES = 163_002_883L;
    public static final long VAD_DOWNLOAD_BYTES = 643_854L;
    public static final long TOTAL_DOWNLOAD_BYTES = MODEL_DOWNLOAD_BYTES + VAD_DOWNLOAD_BYTES;

    private static final URI MODEL_URI = URI.create(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2");
    private static final URI VAD_URI = URI.create(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx");
    private static final String MODEL_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e";
    private static final String VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6";

    private final Path modelDir = Path.of(System.getProperty("user.home"), ".snapvideotools", "models",
            "sensevoice-small", "2024-07-17");
    private final Path modelFile = modelDir.resolve("model.int8.onnx");
    private final Path tokensFile = modelDir.resolve("tokens.txt");
    private final Path vadFile = modelDir.resolve("silero_vad.onnx");
    private final Path readyMarker = modelDir.resolve(".ready");
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sensevoice-model");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<SpeechModelSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile SpeechModelSnapshot snapshot = new SpeechModelSnapshot(
            SpeechModelStatus.NOT_INSTALLED, 0, 0, TOTAL_DOWNLOAD_BYTES,
            SpeechModelFailureReason.NONE, null);
    private volatile CompletableFuture<Path> activePreparation;
    private OfflineRecognizer recognizer;

    @PostConstruct
    public void initializeInstalledModel() {
        if (hasInstalledFiles()) {
            activePreparation = startPreparation();
        } else if (hasIncompleteDownload()) {
            // 应用退出后保留下载成果，下次启动自动从现有文件继续准备模型。
            activePreparation = startPreparation();
        }
    }

    public SpeechModelSnapshot getSnapshot() {
        return snapshot;
    }

    public boolean isReady() {
        return snapshot.isReady();
    }

    public Path getVadFile() {
        return vadFile;
    }

    public void addStatusListener(Consumer<SpeechModelSnapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot);
    }

    public void removeStatusListener(Consumer<SpeechModelSnapshot> listener) {
        listeners.remove(listener);
    }

    /**
     * 启动或复用唯一的模型准备任务。
     */
    public synchronized CompletableFuture<Path> ensureReady() {
        if (isReady()) {
            return CompletableFuture.completedFuture(modelDir);
        }
        if (activePreparation != null && !activePreparation.isDone()) {
            return activePreparation;
        }
        activePreparation = startPreparation();
        return activePreparation;
    }

    /** 已有完整模型时只重试加载，避免加载失败后再次下载 156 MB 文件。 */
    private CompletableFuture<Path> startPreparation() {
        return CompletableFuture.supplyAsync(
                hasInstalledFiles() ? this::loadInstalledModel : this::downloadAndLoad,
                modelExecutor);
    }

    public synchronized String recognize(float[] samples, int sampleRate) {
        if (recognizer == null || !isReady()) {
            throw new IllegalStateException("SenseVoice model is not ready");
        }
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, sampleRate);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText();
        } finally {
            stream.release();
        }
    }

    private Path downloadAndLoad() {
        try {
            Files.createDirectories(modelDir);
            Path archive = modelDir.resolve("sensevoice-small.tar.bz2");
            downloadWithResume(MODEL_URI, archive, MODEL_DOWNLOAD_BYTES, 0);
            updateStatus(SpeechModelStatus.VERIFYING, 1, TOTAL_DOWNLOAD_BYTES, null);
            verifySha256(archive, MODEL_SHA256);
            extractModelFiles(archive);

            downloadWithResume(VAD_URI, vadFile, VAD_DOWNLOAD_BYTES, MODEL_DOWNLOAD_BYTES);
            updateStatus(SpeechModelStatus.VERIFYING, 1, TOTAL_DOWNLOAD_BYTES, null);
            verifySha256(vadFile, VAD_SHA256);

            Files.writeString(readyMarker, MODEL_SHA256 + System.lineSeparator() + VAD_SHA256,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(archive);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("SenseVoice model preparation failed", exception);
            Failure failure = classifyFailure(exception);
            clearActivePreparationAfterFailure();
            updateStatus(SpeechModelStatus.ERROR, snapshot.progress(), snapshot.downloadedBytes(),
                    failure.reason(), failure.detail());
            throw new IllegalStateException("SenseVoice model preparation failed", exception);
        }
        return loadInstalledModel();
    }

    private Path loadInstalledModel() {
        try {
            updateStatus(SpeechModelStatus.LOADING, 1, TOTAL_DOWNLOAD_BYTES, null);
            if (!hasInstalledFiles()) {
                throw new IOException("SenseVoice model files are incomplete");
            }
            OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
                    .setModel(modelFile.toString())
                    .setLanguage("auto")
                    .setInverseTextNormalization(true)
                    .build();
            OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                    .setSenseVoice(senseVoice)
                    .setTokens(tokensFile.toString())
                    .setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))
                    .setDebug(false)
                    .build();
            OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                    .setOfflineModelConfig(modelConfig)
                    .setDecodingMethod("greedy_search")
                    .build();
            synchronized (this) {
                releaseRecognizer();
                recognizer = new OfflineRecognizer(config);
            }
            updateStatus(SpeechModelStatus.READY, 1, TOTAL_DOWNLOAD_BYTES, null);
            return modelDir;
        } catch (Exception exception) {
            return failModelLoad(exception);
        } catch (LinkageError error) {
            return failModelLoad(error);
        }
    }

    private Path failModelLoad(Throwable error) {
        log.error("Failed to load installed SenseVoice model", error);
        clearActivePreparationAfterFailure();
        updateStatus(SpeechModelStatus.ERROR, 1, TOTAL_DOWNLOAD_BYTES,
                SpeechModelFailureReason.MODEL_LOAD, messageOf(error));
        throw new IllegalStateException("Failed to load SenseVoice model", error);
    }

    /** 先释放失败任务引用，使用户点击“重试”时一定会排入新的准备任务。 */
    private synchronized void clearActivePreparationAfterFailure() {
        activePreparation = null;
    }

    private void downloadWithResume(URI uri, Path target, long expectedBytes, long completedBefore)
            throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        if (Files.exists(target) && Files.size(target) == expectedBytes) {
            updateDownloadProgress(completedBefore + expectedBytes);
            return;
        }
        if (Files.exists(part) && Files.size(part) == expectedBytes) {
            replaceDownloadedFile(part, target);
            updateDownloadProgress(completedBefore + expectedBytes);
            return;
        }
        if (Files.exists(part) && Files.size(part) > expectedBytes) {
            Files.delete(part);
        }
        long existing = Files.exists(part) ? Files.size(part) : 0;
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "SnapVideoTools/1.0")
                .GET();
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=" + existing + "-");
        }
        updateStatus(SpeechModelStatus.DOWNLOADING,
                (double) (completedBefore + existing) / TOTAL_DOWNLOAD_BYTES,
                completedBefore + existing, null);
        HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        boolean append = existing > 0 && response.statusCode() == 206;
        if (response.statusCode() != 200 && response.statusCode() != 206) {
            throw new ModelServerException(response.statusCode());
        }
        if (!append) {
            existing = 0;
        }
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[64 * 1024];
            long downloaded = existing;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                downloaded += count;
                updateDownloadProgress(completedBefore + downloaded);
            }
        }
        if (Files.size(part) != expectedBytes) {
            throw new ModelFileSizeException(Files.size(part), expectedBytes);
        }
        replaceDownloadedFile(part, target);
    }

    /**
     * 优先原子替换下载文件；文件系统不支持时退化为同目录覆盖移动。
     */
    private void replaceDownloadedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void updateDownloadProgress(long downloadedBytes) {
        long normalized = Math.min(TOTAL_DOWNLOAD_BYTES, downloadedBytes);
        updateStatus(SpeechModelStatus.DOWNLOADING, (double) normalized / TOTAL_DOWNLOAD_BYTES,
                normalized, null);
    }

    private void extractModelFiles(Path archive) throws IOException {
        Path staging = modelDir.resolve("extracting");
        deleteDirectory(staging);
        Files.createDirectories(staging);
        try {
            try (InputStream fileInput = Files.newInputStream(archive);
                 BufferedInputStream buffered = new BufferedInputStream(fileInput);
                 BZip2CompressorInputStream bzip = new BZip2CompressorInputStream(buffered);
                 TarArchiveInputStream tar = new TarArchiveInputStream(bzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    if (!entry.isFile()) {
                        continue;
                    }
                    String name = Path.of(entry.getName()).getFileName().toString();
                    if (!name.equals("model.int8.onnx") && !name.equals("tokens.txt")) {
                        continue;
                    }
                    Path output = staging.resolve(name);
                    Files.copy(tar, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            Failure storageFailure = classifyFailure(exception);
            if (storageFailure.reason() == SpeechModelFailureReason.DISK_SPACE
                    || storageFailure.reason() == SpeechModelFailureReason.PERMISSION) {
                throw exception;
            }
            deleteDirectory(staging);
            Files.deleteIfExists(archive);
            throw new ModelArchiveException(exception);
        }
        Path stagedModel = staging.resolve("model.int8.onnx");
        Path stagedTokens = staging.resolve("tokens.txt");
        if (!Files.isRegularFile(stagedModel) || !Files.isRegularFile(stagedTokens)) {
            Files.deleteIfExists(archive);
            throw new ModelArchiveException("Model archive does not contain required files");
        }
        Files.move(stagedModel, modelFile, StandardCopyOption.REPLACE_EXISTING);
        Files.move(stagedTokens, tokensFile, StandardCopyOption.REPLACE_EXISTING);
        deleteDirectory(staging);
    }

    private boolean hasInstalledFiles() {
        try {
            if (!Files.isRegularFile(modelFile) || Files.size(modelFile) < 200_000_000L
                    || !Files.isRegularFile(tokensFile) || Files.size(tokensFile) < 100_000L
                    || !Files.isRegularFile(vadFile) || Files.size(vadFile) != VAD_DOWNLOAD_BYTES
                    || !Files.isRegularFile(readyMarker)) {
                return false;
            }
            String marker = Files.readString(readyMarker, StandardCharsets.UTF_8);
            return marker.contains(MODEL_SHA256) && marker.contains(VAD_SHA256);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasIncompleteDownload() {
        Path archive = modelDir.resolve("sensevoice-small.tar.bz2");
        return Files.exists(archive)
                || Files.exists(archive.resolveSibling(archive.getFileName() + ".part"))
                || Files.exists(vadFile.resolveSibling(vadFile.getFileName() + ".part"));
    }

    private void verifySha256(Path file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new ModelChecksumException();
        }
    }

    private void updateStatus(SpeechModelStatus status, double progress, long downloadedBytes, String error) {
        updateStatus(status, progress, downloadedBytes, SpeechModelFailureReason.NONE, error);
    }

    private void updateStatus(SpeechModelStatus status, double progress, long downloadedBytes,
                              SpeechModelFailureReason failureReason, String error) {
        snapshot = new SpeechModelSnapshot(status, Math.max(0, Math.min(1, progress)), downloadedBytes,
                TOTAL_DOWNLOAD_BYTES, failureReason, error);
        for (Consumer<SpeechModelSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.warn("Speech model listener failed", e);
            }
        }
    }

    private Failure classifyFailure(Throwable error) {
        ModelServerException serverException = findCause(error, ModelServerException.class);
        if (serverException != null) {
            return new Failure(SpeechModelFailureReason.SERVER, "HTTP " + serverException.statusCode());
        }
        if (findCause(error, ModelChecksumException.class) != null) {
            return new Failure(SpeechModelFailureReason.CHECKSUM, null);
        }
        if (findCause(error, ModelArchiveException.class) != null) {
            return new Failure(SpeechModelFailureReason.ARCHIVE, null);
        }
        Throwable cause = rootCause(error);
        if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException
                || cause instanceof java.net.SocketTimeoutException) {
            return new Failure(SpeechModelFailureReason.TIMEOUT, null);
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException
                || cause instanceof SSLException || cause instanceof SocketException) {
            return new Failure(SpeechModelFailureReason.NETWORK, null);
        }
        if (cause instanceof AccessDeniedException || cause instanceof SecurityException) {
            return new Failure(SpeechModelFailureReason.PERMISSION, null);
        }
        if (cause instanceof FileSystemException || cause instanceof IOException) {
            String message = messageOf(cause).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("no space") || message.contains("disk full")
                    || message.contains("not enough space")) {
                return new Failure(SpeechModelFailureReason.DISK_SPACE, null);
            }
            if (message.contains("permission") || message.contains("access denied")) {
                return new Failure(SpeechModelFailureReason.PERMISSION, null);
            }
            if (cause instanceof ModelFileSizeException || message.contains("unexpected end")
                    || message.contains("premature eof") || message.contains("connection reset")
                    || message.contains("closed")) {
                return new Failure(SpeechModelFailureReason.NETWORK, null);
            }
        }
        if (cause instanceof InterruptedException) {
            return new Failure(SpeechModelFailureReason.INTERRUPTED, null);
        }
        return new Failure(SpeechModelFailureReason.UNKNOWN, sanitizeDetail(messageOf(cause)));
    }

    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String sanitizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String normalized = detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) + "…" : normalized;
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        modelExecutor.shutdownNow();
        releaseRecognizer();
    }

    private void releaseRecognizer() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
    }

    private record Failure(SpeechModelFailureReason reason, String detail) {
    }

    private static final class ModelServerException extends IOException {
        private final int statusCode;

        private ModelServerException(int statusCode) {
            super("Model server returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private static final class ModelFileSizeException extends IOException {
        private ModelFileSizeException(long actualBytes, long expectedBytes) {
            super("Unexpected model file size: " + actualBytes + ", expected: " + expectedBytes);
        }
    }

    private static final class ModelChecksumException extends IOException {
        private ModelChecksumException() {
            super("Model checksum verification failed");
        }
    }

    private static final class ModelArchiveException extends IOException {
        private ModelArchiveException(String message) {
            super(message);
        }

        private ModelArchiveException(Throwable cause) {
            super("Model archive extraction failed", cause);
        }
    }
}
