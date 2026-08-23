package com.kalman03.svt.desktop.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kalman03.svt.desktop.entity.DownloadRecord;
import com.kalman03.svt.desktop.enums.DownloadStatus;
import com.kalman03.svt.desktop.enums.MediaType;
import com.kalman03.svt.desktop.enums.TabType;
import com.kalman03.svt.desktop.enums.TranscriptionStatus;
import com.kalman03.svt.desktop.model.DownloadRequest;
import com.kalman03.svt.desktop.model.DownloadTask;
import com.kalman03.svt.desktop.model.VideoInfo;
import com.kalman03.svt.desktop.repository.DownloadRecordRepository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteDownloadService implements DownloadService {

    private final VideoParsingService videoParsingService;
    private final FFmpegService ffmpegService;
    private final DownloadRecordRepository downloadRecordRepository;
    private final TranscriptionService transcriptionService;

    private ExecutorService downloadExecutor;
    private ExecutorService parsingExecutor;

    private final Map<Long, DownloadTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<Long, Object> taskExecutionTokens = new ConcurrentHashMap<>();
    private final Map<Long, Object> taskExecutionLocks = new ConcurrentHashMap<>();
    private final Map<Object, HttpURLConnection> executionConnections = new ConcurrentHashMap<>();
    private final List<Consumer<DownloadTask>> progressListeners = new CopyOnWriteArrayList<>();
    private final Object databaseWriteLock = new Object();
    private final AtomicLong queueEpoch = new AtomicLong();

    private static final String DOWNLOAD_ROOT = System.getProperty("user.home") + File.separator + "Downloads" + File.separator + "SnapVideoTools";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private record ParseOutcome(int createdTasks, String errorMessage) {}

    @PostConstruct
    public void init() {
        downloadExecutor = Executors.newFixedThreadPool(3);
        parsingExecutor = Executors.newFixedThreadPool(2);

        File downloadDir = new File(DOWNLOAD_ROOT);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        restoreInterruptedTranscriptions();

        log.info("Download service initialized. Download root: {}", DOWNLOAD_ROOT);
    }

    @PreDestroy
    public void destroy() {
        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
        }
        if (parsingExecutor != null) {
            parsingExecutor.shutdownNow();
        }
    }

    @Override
    public void submitDownload(DownloadRequest request) {
        submitDownloadAsync(request);
    }

    @Override
    public CompletableFuture<SubmitResult> submitDownloadAsync(DownloadRequest request) {
        log.info("Received download request: tabType={}, extractCover={}, extractAudio={}, extractText={}",
                request.getTabType(), request.isExtractCover(), request.isExtractAudio(), request.isExtractText());

        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String dateFolder = LocalDateTime.now().format(DATE_FORMATTER);
        long requestEpoch = queueEpoch.get();
        CompletableFuture<SubmitResult> resultFuture = new CompletableFuture<>();

        parsingExecutor.submit(() -> {
            ParseOutcome outcome = new ParseOutcome(0, null);
            try {
                TabType resolvedTab = videoParsingService.resolveTabType(request.getTabType(), request.getContent());
                if (resolvedTab != request.getTabType()) {
                    log.info("Detected an explicit work URL in profile mode; route request as a video link");
                }
                if (resolvedTab == TabType.VIDEO_LINK) {
                    outcome = processVideoLinks(request, batchId, dateFolder, requestEpoch);
                } else {
                    outcome = processUserProfile(request, batchId, dateFolder, requestEpoch);
                }
            } catch (Exception e) {
                log.error("Failed to process download request", e);
                outcome = new ParseOutcome(0, errorMessage(e, "Failed to process download request"));
            } finally {
                resultFuture.complete(new SubmitResult(outcome.createdTasks(), outcome.errorMessage()));
            }
        });

        return resultFuture;
    }

    private ParseOutcome processVideoLinks(DownloadRequest request, String batchId, String batchFolder,
                                           long requestEpoch) {
        List<String> urls = videoParsingService.extractUrls(request.getContent());
        if (urls.isEmpty()) {
            log.warn("No URLs found in content");
            return new ParseOutcome(0, null);
        }

        log.info("Found {} URLs to download", urls.size());

        String errorMessage = null;
        int createdTasks = 0;
        for (String url : urls) {
            if (requestEpoch != queueEpoch.get()) {
                break;
            }
            try {
                VideoInfo videoInfo = videoParsingService.parseVideoUrl(url);
                if (videoInfo != null) {
                    applyPlatformOverride(videoInfo, url);
                    if (createAndStartDownloadTask(videoInfo, request, batchId, batchFolder, requestEpoch)) {
                        createdTasks++;
                    }
                }
            } catch (VideoParsingService.ApiParseException e) {
                log.error("Failed to parse URL: {}", url, e);
                if (errorMessage == null && e.getMessage() != null && !e.getMessage().isBlank()) {
                    errorMessage = e.getMessage();
                }
            } catch (Exception e) {
                log.error("Failed to parse URL: {}", url, e);
                if (errorMessage == null) {
                    errorMessage = errorMessage(e, "Failed to process video link");
                }
            }
        }

        return requestEpoch == queueEpoch.get()
                ? new ParseOutcome(createdTasks, errorMessage)
                : new ParseOutcome(0, null);
    }

    private ParseOutcome processUserProfile(DownloadRequest request, String batchId, String batchFolder,
                                            long requestEpoch) {
        String profileUrl = request.getContent().trim();
        int page = 1;
        int pageSize = 20;
        int maxPages = 200;
        Set<String> seenKeys = new HashSet<>();
        int noNewItemsPages = 0;
        int createdTasks = 0;
        String errorMessage = null;

        log.info("Start parsing user profile videos: url={}, pageSize={}, batchId={}", profileUrl, pageSize, batchId);
        videoParsingService.resetUserVideosPagination(profileUrl);
        try {
            while (page <= maxPages) {
                if (requestEpoch != queueEpoch.get()) {
                    break;
                }
                log.info("Fetching user videos: url={}, page={}, pageSize={}", profileUrl, page, pageSize);
                List<VideoInfo> videos = videoParsingService.getUserVideos(profileUrl, page, pageSize);
                if (videos.isEmpty()) {
                    log.info("No videos returned, stop paging: url={}, page={}", profileUrl, page);
                    break;
                }
                log.info("Fetched {} videos: url={}, page={}", videos.size(), profileUrl, page);

                int created = 0;
                int skipped = 0;
                for (VideoInfo videoInfo : videos) {
                    if (requestEpoch != queueEpoch.get()) {
                        break;
                    }
                    String key = buildVideoDedupKey(videoInfo);
                    if (key != null && !seenKeys.add(key)) {
                        skipped++;
                        continue;
                    }
                    applyPlatformOverride(videoInfo,
                            videoInfo.getOrignalUrl() == null ? profileUrl : videoInfo.getOrignalUrl());
                    if (createAndStartDownloadTask(videoInfo, request, batchId, batchFolder, requestEpoch)) {
                        created++;
                        createdTasks++;
                    }
                }
                log.info("Created {} tasks, skipped {} duplicates: url={}, page={}, seen={}", created, skipped, profileUrl, page,
                        seenKeys.size());

                boolean hasMore = videoParsingService.hasMoreVideos(profileUrl, page, pageSize);
                log.info("Has more videos: url={}, page={}, hasMore={}", profileUrl, page, hasMore);
                if (created == 0) {
                    noNewItemsPages++;
                    log.info("No new items on page: url={}, page={}, streak={}", profileUrl, page, noNewItemsPages);
                } else {
                    noNewItemsPages = 0;
                }
                if (!hasMore) {
                    break;
                }
                if (noNewItemsPages >= 2) {
                    log.info("No new items for consecutive pages, stop paging: url={}, page={}", profileUrl, page);
                    break;
                }
                page++;
            }

            if (page > maxPages) {
                log.warn("Reached max pages when parsing user profile, stop paging: url={}, pageSize={}, maxPages={}", profileUrl,
                        pageSize, maxPages);
            }
        } catch (VideoParsingService.ApiParseException e) {
            log.error("Failed to parse user profile: {}", profileUrl, e);
            if (e.getMessage() != null && !e.getMessage().isBlank()) {
                errorMessage = e.getMessage();
            }
        } catch (Exception e) {
            log.error("Failed to parse user profile: {}", profileUrl, e);
            errorMessage = errorMessage(e, "Failed to parse user profile");
        }

        return requestEpoch == queueEpoch.get()
                ? new ParseOutcome(createdTasks, errorMessage)
                : new ParseOutcome(0, null);
    }

    private String errorMessage(Throwable throwable, String fallback) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return fallback;
        }
        return throwable.getMessage();
    }

    private boolean createAndStartDownloadTask(VideoInfo videoInfo, DownloadRequest request,
                                               String batchId, String batchFolder, long requestEpoch) {
        synchronized (databaseWriteLock) {
            if (requestEpoch != queueEpoch.get()) {
                return false;
            }
            if (videoInfo.isImageSet()) {
                downloadImageSet(videoInfo, request, batchId, batchFolder);
            } else {
                downloadVideo(videoInfo, request, batchId, batchFolder);
            }
            return true;
        }
    }

    private void downloadVideo(VideoInfo videoInfo, DownloadRequest request,
                               String batchId, String dateFolder) {
        // 新目录结构: 日期/标题/video/
        String safeTitle = sanitizeFileName(videoInfo.getTitle());
        String datePath = DOWNLOAD_ROOT + File.separator + dateFolder;
        new File(datePath).mkdirs();

        String videoPath = resolveUniquePath(datePath, safeTitle, ".mp4");

        DownloadRecord record = DownloadRecord.builder()
                .url(videoInfo.getOrignalUrl())
                .title(videoInfo.getTitle())
                .localPath(videoPath)
                .status(DownloadStatus.PENDING.getValue())
                .platform(videoInfo.getPlatform())
                .thumbnailUrl(videoInfo.getCover())
                .mediaType(MediaType.VIDEO.getValue())
                .batchId(batchId)
                .progress(0.0)
                .extractText(request.isExtractText())
                .transcriptionStatus(TranscriptionStatus.NONE.name())
                .transcriptionProgress(0.0)
                .build();
        record = saveRecord(record);

        DownloadTask task = DownloadTask.builder()
                .id(record.getId())
                .batchId(batchId)
                .videoInfo(videoInfo)
                .mediaType(MediaType.VIDEO)
                .status(DownloadStatus.PENDING)
                .progress(0)
                .localPath(videoPath)
                .thumbnailUrl(videoInfo.getCover())
                .createdAt(LocalDateTime.now())
                .extractCover(request.isExtractCover())
                .extractAudio(request.isExtractAudio())
                .extractText(request.isExtractText())
                .transcriptionStatus(TranscriptionStatus.NONE)
                .build();

        activeTasks.put(task.getId(), task);
        notifyProgressListeners(task);

        startVideoDownload(task, dateFolder, false);
    }

    private void downloadImageSet(VideoInfo videoInfo, DownloadRequest request,
                                  String batchId, String dateFolder) {
        List<String> imageUrls = videoInfo.getImageUrls();
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }

        // 新目录结构: 日期/标题/image/
        String safeTitle = sanitizeFileName(videoInfo.getTitle());
        String datePath = DOWNLOAD_ROOT + File.separator + dateFolder;
        new File(datePath).mkdirs();

        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            String imageBaseName = buildNameWithSuffix(safeTitle, String.valueOf(i + 1));
            String imagePath = resolveUniquePath(datePath, imageBaseName, getImageExtension(imageUrl));

            DownloadRecord record = DownloadRecord.builder()
                    .url(imageUrl)
                    .title(videoInfo.getTitle() + " - Image " + (i + 1))
                    .localPath(imagePath)
                    .status(DownloadStatus.PENDING.getValue())
                    .platform(videoInfo.getPlatform())
                    .thumbnailUrl(imageUrl)
                    .mediaType(MediaType.IMAGE.getValue())
                    .batchId(batchId)
                    .progress(0.0)
                    .build();
            record = saveRecord(record);

            DownloadTask task = DownloadTask.builder()
                    .id(record.getId())
                    .batchId(batchId)
                    .videoInfo(videoInfo)
                    .mediaType(MediaType.IMAGE)
                    .status(DownloadStatus.PENDING)
                    .progress(0)
                    .localPath(imagePath)
                    .thumbnailUrl(imageUrl)
                    .createdAt(LocalDateTime.now())
                    .build();

            activeTasks.put(task.getId(), task);
            notifyProgressListeners(task);

            startImageDownload(task, imageUrl);
        }
    }

    private String getImageExtension(String url) {
        if (url == null) {
            return ".jpg";
        }
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains(".png")) {
            return ".png";
        } else if (lowerUrl.contains(".gif")) {
            return ".gif";
        } else if (lowerUrl.contains(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private void startVideoDownload(DownloadTask task, String batchFolder, boolean refreshSource) {
        Object executionToken = beginTaskExecution(task);
        Future<?> future = downloadExecutor.submit(() -> {
            synchronized (getTaskExecutionLock(task)) {
                if (refreshSource && !refreshVideoInfo(task, executionToken)) {
                    return;
                }
                executeVideoDownload(task, batchFolder, executionToken);
            }
        });
        task.setFuture(future);
    }

    private void startImageDownload(DownloadTask task, String imageUrl) {
        Object executionToken = beginTaskExecution(task);
        Future<?> future = downloadExecutor.submit(() -> {
            synchronized (getTaskExecutionLock(task)) {
                executeImageDownload(task, imageUrl, executionToken);
            }
        });
        task.setFuture(future);
    }

    private void executeVideoDownload(DownloadTask task, String batchFolder, Object executionToken) {
        try {
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.DOWNLOADING, null)) {
                return;
            }

            boolean success = downloadFile(
                    task.getVideoInfo().getFirstVideoUrl(),
                    task.getLocalPath(),
                    progress -> {
                        if (isCurrentExecution(task, executionToken)) {
                            task.setProgress(progress);
                            notifyProgressListeners(task);
                        }
                    },
                    executionToken
            );

            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!success) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        buildDownloadErrorMessage(task.getVideoInfo().getFirstVideoUrl()));
                finishTaskExecution(task, executionToken);
                return;
            }

            if (!updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.PROCESSING, null)) {
                return;
            }

            markMediaCompleted(task);

            if (task.isExtractCover()) {
                extractCover(task, batchFolder);
            }

            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (task.isExtractAudio()) {
                extractAudioFromVideo(task, batchFolder);
            }

            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (task.isExtractText()) {
                startTranscription(task, executionToken);
                return;
            }
            updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.COMPLETED, null);
            activeTasks.remove(task.getId(), task);
            finishTaskExecution(task, executionToken);

        } catch (Exception e) {
            if (isCurrentExecution(task, executionToken)) {
                log.error("Download task failed: {}", task.getId(), e);
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, e.getMessage());
                finishTaskExecution(task, executionToken);
            }
        }
    }

    private void executeImageDownload(DownloadTask task, String imageUrl, Object executionToken) {
        try {
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.DOWNLOADING, null)) {
                return;
            }

            boolean success = downloadFile(
                    imageUrl,
                    task.getLocalPath(),
                    progress -> {
                        if (isCurrentExecution(task, executionToken)) {
                            task.setProgress(progress);
                            notifyProgressListeners(task);
                        }
                    },
                    executionToken
            );

            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!success) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        buildDownloadErrorMessage(imageUrl));
                finishTaskExecution(task, executionToken);
                return;
            }

            updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.COMPLETED, null);
            activeTasks.remove(task.getId(), task);
            finishTaskExecution(task, executionToken);

        } catch (Exception e) {
            if (isCurrentExecution(task, executionToken)) {
                log.error("Image download task failed: {}", task.getId(), e);
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, e.getMessage());
                finishTaskExecution(task, executionToken);
            }
        }
    }

    private String buildDownloadErrorMessage(String url) {
        if (url == null || url.isBlank()) {
            return "Download failed";
        }
        return "Download failed: " + url;
    }

    private void extractCover(DownloadTask parentTask, String dateFolder) {
        DownloadTask coverTask = findExistingChildTask(parentTask, MediaType.IMAGE);
        if (coverTask != null) {
            resetTaskForRetry(coverTask);
            coverTask.setVideoInfo(parentTask.getVideoInfo());
            coverTask.setThumbnailUrl(parentTask.getVideoInfo().getCover());
        } else {
            // 从视频路径推断标题文件夹
            String safeTitle = sanitizeFileName(parentTask.getVideoInfo().getTitle());
            String datePath = DOWNLOAD_ROOT + File.separator + dateFolder;
            new File(datePath).mkdirs();

            String coverBaseName = buildNameWithSuffix(safeTitle, "1");
            String coverPath = resolveUniquePath(datePath, coverBaseName, ".jpg");

            DownloadRecord coverRecord = DownloadRecord.builder()
                    .url(parentTask.getVideoInfo().getOrignalUrl())
                    .title(parentTask.getVideoInfo().getTitle() + " - Cover")
                    .localPath(coverPath)
                    .status(DownloadStatus.PROCESSING.getValue())
                    .platform(parentTask.getVideoInfo().getPlatform())
                    .thumbnailUrl(parentTask.getVideoInfo().getCover())
                    .mediaType(MediaType.IMAGE.getValue())
                    .batchId(parentTask.getBatchId())
                    .parentId(parentTask.getId())
                    .progress(0.0)
                    .build();
            coverRecord = saveRecord(coverRecord);

            coverTask = DownloadTask.builder()
                    .id(coverRecord.getId())
                    .batchId(parentTask.getBatchId())
                    .videoInfo(parentTask.getVideoInfo())
                    .mediaType(MediaType.IMAGE)
                    .status(DownloadStatus.PROCESSING)
                    .progress(0)
                    .localPath(coverPath)
                    .thumbnailUrl(parentTask.getVideoInfo().getCover())
                    .createdAt(LocalDateTime.now())
                    .parentTaskId(parentTask.getId())
                    .build();

            activeTasks.put(coverTask.getId(), coverTask);
            notifyProgressListeners(coverTask);
        }

        DownloadTask currentTask = coverTask;
        Object executionToken = beginTaskExecution(currentTask);
        synchronized (getTaskExecutionLock(currentTask)) {
            if (!isCurrentExecution(currentTask, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.PROCESSING, null)) {
                return;
            }

            boolean success = false;
            if (parentTask.getVideoInfo().hasCover()) {
                log.info("Downloading cover from remote URL: {}", parentTask.getVideoInfo().getCover());
                success = downloadFile(parentTask.getVideoInfo().getCover(), currentTask.getLocalPath(), null,
                        executionToken);
            }

            if (isCurrentExecution(currentTask, executionToken) && !success) {
                log.info("Extracting cover from video using FFmpeg: {}", parentTask.getLocalPath());
                success = ffmpegService.extractCover(parentTask.getLocalPath(), currentTask.getLocalPath());
            }

            if (!isCurrentExecution(currentTask, executionToken)) {
                return;
            }
            if (success) {
                updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.COMPLETED, null);
                activeTasks.remove(currentTask.getId(), currentTask);
            } else {
                updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.FAILED,
                        "Failed to extract cover");
            }
            finishTaskExecution(currentTask, executionToken);
        }
    }

    private boolean downloadFile(String urlStr, String outputPath, Consumer<Double> progressCallback) {
        return downloadFile(urlStr, outputPath, progressCallback, null);
    }

    private boolean downloadFile(String urlStr, String outputPath, Consumer<Double> progressCallback,
                                 Object executionToken) {
        HttpURLConnection connection = null;
        try {
            @SuppressWarnings("deprecation")
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (executionToken != null) {
                executionConnections.put(executionToken, connection);
                if (!taskExecutionTokens.containsValue(executionToken)) {
                    return false;
                }
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log.error("HTTP error: {} for url: {}", responseCode, urlStr);
                return false;
            }

            long totalSize = connection.getContentLengthLong();
            if (totalSize <= 0) {
                totalSize = 10 * 1024 * 1024;
            }

            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (executionToken != null && !executionConnections.containsKey(executionToken)) {
                        return false;
                    }
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    double progress = (downloaded * 100.0) / totalSize;
                    progress = Math.min(progress, 100);
                    if (progressCallback != null) {
                        progressCallback.accept(progress);
                    }
                }
            }

            log.info("File downloaded successfully: {}", outputPath);
            return true;

        } catch (Exception e) {
            log.error("Failed to download file: {}", urlStr, e);
            return false;
        } finally {
            if (connection != null) {
                if (executionToken != null) {
                    executionConnections.remove(executionToken, connection);
                }
                connection.disconnect();
            }
        }
    }

    private void extractAudioFromVideo(DownloadTask parentTask, String dateFolder) {
        DownloadTask audioTask = findExistingChildTask(parentTask, MediaType.AUDIO);
        if (audioTask != null) {
            resetTaskForRetry(audioTask);
            audioTask.setVideoInfo(parentTask.getVideoInfo());
        } else {
            // 新目录结构: 日期/标题/audio/
            String safeTitle = sanitizeFileName(parentTask.getVideoInfo().getTitle());
            String datePath = DOWNLOAD_ROOT + File.separator + dateFolder;
            new File(datePath).mkdirs();

            String audioPath = resolveUniquePath(datePath, safeTitle, ".mp3");

            DownloadRecord audioRecord = DownloadRecord.builder()
                    .url(parentTask.getVideoInfo().getOrignalUrl())
                    .title(parentTask.getVideoInfo().getTitle() + " - Audio")
                    .localPath(audioPath)
                    .status(DownloadStatus.PROCESSING.getValue())
                    .platform(parentTask.getVideoInfo().getPlatform())
                    .mediaType(MediaType.AUDIO.getValue())
                    .batchId(parentTask.getBatchId())
                    .parentId(parentTask.getId())
                    .progress(0.0)
                    .build();
            audioRecord = saveRecord(audioRecord);

            audioTask = DownloadTask.builder()
                    .id(audioRecord.getId())
                    .batchId(parentTask.getBatchId())
                    .videoInfo(parentTask.getVideoInfo())
                    .mediaType(MediaType.AUDIO)
                    .status(DownloadStatus.PROCESSING)
                    .progress(0)
                    .localPath(audioPath)
                    .createdAt(LocalDateTime.now())
                    .parentTaskId(parentTask.getId())
                    .build();

            activeTasks.put(audioTask.getId(), audioTask);
            notifyProgressListeners(audioTask);
        }

        DownloadTask currentTask = audioTask;
        Object executionToken = beginTaskExecution(currentTask);
        synchronized (getTaskExecutionLock(currentTask)) {
            if (!isCurrentExecution(currentTask, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.PROCESSING, null)) {
                return;
            }

            boolean success = ffmpegService.extractAudio(
                    parentTask.getLocalPath(),
                    currentTask.getLocalPath(),
                    progress -> {
                        if (isCurrentExecution(currentTask, executionToken)) {
                            currentTask.setProgress(progress);
                            notifyProgressListeners(currentTask);
                        }
                    }
            );

            if (!isCurrentExecution(currentTask, executionToken)) {
                return;
            }
            if (success) {
                updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.COMPLETED, null);
                activeTasks.remove(currentTask.getId(), currentTask);
            } else {
                updateTaskStatusIfCurrent(currentTask, executionToken, DownloadStatus.FAILED,
                        "Failed to extract audio");
            }
            finishTaskExecution(currentTask, executionToken);
        }
    }

    private void updateTaskStatus(DownloadTask task, DownloadStatus status) {
        updateTaskStatus(task, status, null);
    }

    private void updateTaskStatus(DownloadTask task, DownloadStatus status, String errorMessage) {
        task.setStatus(status);
        task.setErrorMessage(errorMessage);

        updateRecord(task.getId(), record -> {
            record.setStatus(status.getValue());
            record.setErrorMessage(errorMessage);
            record.setProgress(task.getProgress());
            if (status == DownloadStatus.COMPLETED) {
                record.setCompletedAt(LocalDateTime.now());
                record.setProgress(100.0);
                File file = new File(task.getLocalPath());
                if (file.exists()) {
                    record.setFileSize(file.length());
                }
            }
        });

        notifyProgressListeners(task);
    }

    private void markMediaCompleted(DownloadTask task) {
        LocalDateTime completedAt = LocalDateTime.now();
        task.setMediaCompletedAt(completedAt);
        updateRecord(task.getId(), record -> {
            record.setMediaCompletedAt(completedAt);
            File file = new File(task.getLocalPath());
            if (file.isFile()) {
                record.setFileSize(file.length());
            }
        });
    }

    /**
     * 媒体下载完成后把耗时的模型等待和推理交给独立转写线程。
     */
    private void startTranscription(DownloadTask task, Object executionToken) {
        Path mediaPath = Path.of(task.getLocalPath());
        String fileName = mediaPath.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        Path txtPath = mediaPath.resolveSibling(baseName + ".txt");
        Path srtPath = mediaPath.resolveSibling(baseName + ".srt");
        task.setTranscriptTxtPath(txtPath.toString());
        task.setTranscriptSrtPath(srtPath.toString());
        updateTranscriptionState(task, TranscriptionStatus.WAITING_MODEL, 0, null);

        CompletableFuture<TranscriptionService.Result> future = transcriptionService.transcribe(
                mediaPath, txtPath, srtPath,
                progress -> {
                    if (isCurrentExecution(task, executionToken)) {
                        updateTranscriptionState(task, progress.status(), progress.progress(), null);
                    }
                },
                () -> !isCurrentExecution(task, executionToken));
        task.setFuture(future);
        future.whenComplete((result, throwable) -> {
            synchronized (databaseWriteLock) {
                if (!isCurrentExecution(task, executionToken)) {
                    return;
                }
                if (throwable == null) {
                    task.setTranscriptTxtPath(result.txtPath().toString());
                    task.setTranscriptSrtPath(result.srtPath().toString());
                    updateTranscriptionState(task, TranscriptionStatus.COMPLETED, 100,
                            result.noSpeechDetected() ? "NO_SPEECH" : null);
                } else {
                    String message = unwrapCompletionMessage(throwable);
                    updateTranscriptionState(task, TranscriptionStatus.FAILED,
                            task.getTranscriptionProgress(), message);
                }
                // 媒体文件本身已经成功，转写失败不能退化成 Download Error。
                updateTaskStatus(task, DownloadStatus.COMPLETED, null);
                activeTasks.remove(task.getId(), task);
                finishTaskExecution(task, executionToken);
            }
        });
    }

    private void updateTranscriptionState(DownloadTask task, TranscriptionStatus status,
                                          double progress, String error) {
        task.setTranscriptionStatus(status);
        task.setTranscriptionProgress(progress);
        task.setTranscriptionError(error);
        updateRecord(task.getId(), record -> {
            record.setExtractText(true);
            record.setTranscriptionStatus(status.name());
            record.setTranscriptionProgress(progress);
            record.setTranscriptionError(error);
            record.setTranscriptTxtPath(task.getTranscriptTxtPath());
            record.setTranscriptSrtPath(task.getTranscriptSrtPath());
        });
        notifyProgressListeners(task);
    }

    private String unwrapCompletionMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 在数据库写锁内再次核对执行令牌，关闭“校验通过后恰好被暂停”的竞态窗口。
     */
    private boolean updateTaskStatusIfCurrent(DownloadTask task, Object executionToken,
                                              DownloadStatus status, String errorMessage) {
        synchronized (databaseWriteLock) {
            if (!isCurrentExecution(task, executionToken)) {
                return false;
            }
            updateTaskStatus(task, status, errorMessage);
            return true;
        }
    }

    private void notifyProgressListeners(DownloadTask task) {
        Platform.runLater(() -> {
            for (Consumer<DownloadTask> listener : progressListeners) {
                try {
                    listener.accept(task);
                } catch (Exception e) {
                    log.error("Error notifying progress listener", e);
                }
            }
        });
    }

    /**
     * 为每次启动创建独立令牌，避免暂停前的旧线程覆盖恢复后的任务状态。
     */
    private Object beginTaskExecution(DownloadTask task) {
        Object executionToken = new Object();
        taskExecutionTokens.put(task.getId(), executionToken);
        return executionToken;
    }

    private Object getTaskExecutionLock(DownloadTask task) {
        return taskExecutionLocks.computeIfAbsent(task.getId(), ignored -> new Object());
    }

    private boolean isCurrentExecution(DownloadTask task, Object executionToken) {
        return task != null
                && executionToken != null
                && taskExecutionTokens.get(task.getId()) == executionToken;
    }

    private void finishTaskExecution(DownloadTask task, Object executionToken) {
        if (task != null) {
            taskExecutionTokens.remove(task.getId(), executionToken);
            if (!activeTasks.containsKey(task.getId())) {
                taskExecutionLocks.remove(task.getId());
            }
        }
    }

    private void invalidateTaskExecution(Long taskId) {
        if (taskId != null) {
            Object executionToken = taskExecutionTokens.remove(taskId);
            if (executionToken != null) {
                HttpURLConnection connection = executionConnections.remove(executionToken);
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    @Transactional
    public DownloadRecord saveRecord(DownloadRecord record) {
        synchronized (databaseWriteLock) {
            return downloadRecordRepository.save(record);
        }
    }

    @Transactional
    public void updateRecord(Long id, Consumer<DownloadRecord> updater) {
        synchronized (databaseWriteLock) {
            downloadRecordRepository.findById(id).ifPresent(record -> {
                updater.accept(record);
                downloadRecordRepository.save(record);
            });
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "untitled";
        }

        String sanitized = fileName;

        // Replace common path separators (including fullwidth variants) and Windows-invalid characters
        sanitized = sanitized.replaceAll("[\\\\/:*?\"<>|\\uFF0F\\uFF3C\\u2215\\uFE68]", "_");

        // Remove control characters (e.g. \\r, \\n, \\t) and normalize whitespace
        sanitized = sanitized.replaceAll("\\p{Cntrl}", " ");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();

        // Windows doesn't allow trailing dots/spaces in file or directory names
        sanitized = sanitized.replaceAll("[. ]+$", "").replaceAll("^[. ]+", "");

        // Avoid reserved device names on Windows
        String upper = sanitized.toUpperCase(Locale.ROOT);
        if (upper.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) {
            sanitized = "_" + sanitized;
        }

        // Keep path length safe (safeTitle is used in both folder and filename)
        int maxLen = 20;
        if (sanitized.length() > maxLen) {
            sanitized = sanitized.substring(0, maxLen).trim();
            sanitized = sanitized.replaceAll("[. ]+$", "");
        }

        return sanitized.isEmpty() ? "untitled" : sanitized;
    }

    private String buildNameWithSuffix(String safeTitle, String suffix) {
        String effectiveSuffix = suffix == null ? "" : suffix;
        int maxLen = 20;
        int allowedTitleLen = maxLen - effectiveSuffix.length();
        if (allowedTitleLen < 1) {
            allowedTitleLen = 1;
        }

        String base = safeTitle == null ? "untitled" : safeTitle;
        if (base.length() > allowedTitleLen) {
            base = base.substring(0, allowedTitleLen).trim();
            base = base.replaceAll("[. ]+$", "");
        }
        if (base.isBlank()) {
            base = "untitled";
        }
        return base + effectiveSuffix;
    }

    private String resolveUniquePath(String directory, String baseName, String extension) {
        String safeBase = (baseName == null || baseName.isBlank()) ? "untitled" : baseName;
        String ext = extension == null ? "" : extension;
        File candidate = new File(directory, safeBase + ext);
        if (!candidate.exists()) {
            return candidate.getPath();
        }

        int counter = 2;
        while (true) {
            String suffix = String.valueOf(counter);
            String nameWithSuffix = buildNameWithSuffix(safeBase, suffix);
            candidate = new File(directory, nameWithSuffix + ext);
            if (!candidate.exists()) {
                return candidate.getPath();
            }
            counter++;
        }
    }

    private String buildVideoDedupKey(VideoInfo videoInfo) {
        if (videoInfo == null) {
            return null;
        }
        if (videoInfo.getOrignalUrl() != null && !videoInfo.getOrignalUrl().isBlank()) {
            return videoInfo.getOrignalUrl();
        }
        if (videoInfo.getCover() != null && !videoInfo.getCover().isBlank()) {
            return videoInfo.getCover();
        }
        if (videoInfo.getTitle() != null && !videoInfo.getTitle().isBlank()) {
            return videoInfo.getTitle();
        }
        return null;
    }

    private void applyPlatformOverride(VideoInfo videoInfo, String sourceUrl) {
        if (videoInfo == null) {
            return;
        }
        String derived = derivePlatformFromUrl(sourceUrl);
        if (derived == null || derived.isBlank()) {
            return;
        }
        String existing = videoInfo.getPlatformName();
        if (existing == null || !existing.equalsIgnoreCase(derived)) {
            log.info("Override platform from url host: {} -> {}", existing, derived);
        }
        videoInfo.setPlatform(derived);
        if (videoInfo.getPlatformName() == null || videoInfo.getPlatformName().isBlank()) {
            videoInfo.setPlatformName(derived);
        }
    }

    private String derivePlatformFromUrl(String url) {
        String host = extractHost(url);
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("xiaohongshu.com") || normalized.endsWith("xhslink.com") || normalized.endsWith("xhs.cn")) {
            return "Xiaohongshu";
        }
        if (normalized.endsWith("douyin.com") || normalized.endsWith("iesdouyin.com")
                || normalized.endsWith("douyinvod.com") || normalized.endsWith("douyinpic.com")
                || normalized.endsWith("amemv.com")) {
            return "Douyin";
        }
        if (normalized.endsWith("tiktok.com") || normalized.endsWith("tiktokcdn.com")
                || normalized.endsWith("tiktokv.com")) {
            return "TikTok";
        }
        if (normalized.endsWith("kuaishou.com") || normalized.endsWith("kwai.com")) {
            return "Kuaishou";
        }
        if (normalized.endsWith("bilibili.com") || normalized.endsWith("b23.tv")) {
            return "Bilibili";
        }
        if (normalized.endsWith("ixigua.com")) {
            return "Xigua";
        }
        if (normalized.endsWith("toutiao.com")) {
            return "Toutiao";
        }
        if (normalized.endsWith("weibo.com") || normalized.endsWith("t.cn")) {
            return "Weibo";
        }
        if (normalized.endsWith("pipix.com") || normalized.endsWith("pipixia.com")) {
            return "Pipixia";
        }
        if (normalized.endsWith("zuiyou.com")) {
            return "Zuiyou";
        }
        if (normalized.endsWith("pearvideo.com")) {
            return "Pear Video";
        }
        if (normalized.endsWith("xinpianchang.com")) {
            return "Xinpianchang";
        }
        if (normalized.endsWith("haokan.baidu.com")) {
            return "Haokan Video";
        }
        if (normalized.endsWith("huya.com")) {
            return "Huya";
        }
        if (normalized.endsWith("acfun.cn")) {
            return "AcFun";
        }
        return null;
    }

    private String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            if (uri.getScheme() == null) {
                uri = java.net.URI.create("https://" + trimmed);
            }
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void cancelDownload(Long taskId) {
        DownloadTask task = activeTasks.get(taskId);
        if (task == null) {
            return;
        }
        // 先让旧执行失效，再中断线程，防止旧线程退出时把 CANCELLED 覆盖成 FAILED
        invalidateTaskExecution(taskId);
        if (task.getFuture() != null) {
            task.getFuture().cancel(true);
            task.setFuture(null);
        }
        synchronized (databaseWriteLock) {
            updateTaskStatus(task, DownloadStatus.CANCELLED);
        }
    }

    @Override
    public void cancelAllDownloads() {
        List<Long> taskIds = new ArrayList<>(activeTasks.keySet());
        for (Long taskId : taskIds) {
            cancelDownload(taskId);
        }
    }

    @Override
    public void resumeAllDownloads() {
        for (DownloadTask task : new ArrayList<>(activeTasks.values())) {
            if (!isTaskResumable(task)) {
                continue;
            }
            if (isRootTask(task)) {
                restartTask(task);
            } else if (isParentReady(task)) {
                restartChildTask(task);
            }
        }
    }

    @Override
    @Transactional
    public ClearQueueResult clearQueue() {
        // 让正在解析链接或主页分页的旧请求失效，清理后不能再创建新任务。
        queueEpoch.incrementAndGet();
        synchronized (databaseWriteLock) {
            List<String> statuses = List.of(
                DownloadStatus.PENDING.getValue(),
                DownloadStatus.DOWNLOADING.getValue(),
                DownloadStatus.PROCESSING.getValue(),
                DownloadStatus.FAILED.getValue(),
                DownloadStatus.CANCELLED.getValue()
        );
            List<DownloadRecord> records = downloadRecordRepository.findByStatusIn(statuses);
            Set<Long> clearedIds = new HashSet<>();
            for (DownloadRecord record : records) {
                if (record != null && record.getId() != null) {
                    clearedIds.add(record.getId());
                }
            }
            clearedIds.addAll(activeTasks.keySet());

            // 先统一使全部执行失效，再删除持久化记录，避免后台线程回写已清除任务。
            for (Long taskId : new ArrayList<>(activeTasks.keySet())) {
                invalidateTaskExecution(taskId);
                DownloadTask task = activeTasks.get(taskId);
                if (task != null && task.getFuture() != null) {
                    task.getFuture().cancel(true);
                    task.setFuture(null);
                }
            }
            for (DownloadRecord record : records) {
                if (record != null && record.getId() != null) {
                    downloadRecordRepository.deleteById(record.getId());
                }
            }
            activeTasks.keySet().removeAll(clearedIds);
            taskExecutionTokens.keySet().removeAll(clearedIds);
            taskExecutionLocks.keySet().removeAll(clearedIds);
            executionConnections.clear();
            transcriptionService.clearTemporaryFiles();
            return new ClearQueueResult(Set.copyOf(clearedIds), clearedIds.size());
        }
    }

    @Override
    public void removeTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        invalidateTaskExecution(taskId);
        activeTasks.remove(taskId);
        taskExecutionLocks.remove(taskId);
    }

    @Override
    @Transactional
    public void retryDownload(Long taskId) {
        DownloadTask task = activeTasks.get(taskId);
        if (task != null) {
            if (!isTaskResumable(task)) {
                return;
            }
            if (isRootTask(task)) {
                restartTask(task);
            } else {
                restartChildTask(task);
            }
            return;
        }

        downloadRecordRepository.findById(taskId).ifPresent(record -> {
            if (Boolean.TRUE.equals(record.getExtractText())
                    && record.getMediaCompletedAt() != null
                    && TranscriptionStatus.FAILED.name().equals(record.getTranscriptionStatus())
                    && record.getLocalPath() != null
                    && new File(record.getLocalPath()).isFile()) {
                DownloadTask restored = taskFromRecord(record);
                restored.setStatus(DownloadStatus.PROCESSING);
                restored.setTranscriptionError(null);
                activeTasks.put(restored.getId(), restored);
                updateRecord(restored.getId(), current -> {
                    current.setCompletedAt(null);
                    current.setTranscriptionError(null);
                });
                Object executionToken = beginTaskExecution(restored);
                updateTaskStatus(restored, DownloadStatus.PROCESSING, null);
                startTranscription(restored, executionToken);
                return;
            }
            record.setStatus(DownloadStatus.PENDING.getValue());
            record.setProgress(0.0);
            record.setErrorMessage(null);
            downloadRecordRepository.save(record);
        });
        log.info("Retry download for task: {}", taskId);
    }

    private void restoreInterruptedTranscriptions() {
        List<String> statuses = List.of(
                DownloadStatus.PENDING.getValue(),
                DownloadStatus.PROCESSING.getValue(),
                DownloadStatus.CANCELLED.getValue(),
                DownloadStatus.FAILED.getValue());
        for (DownloadRecord record : downloadRecordRepository.findByStatusIn(statuses)) {
            if (!Boolean.TRUE.equals(record.getExtractText()) || record.getMediaCompletedAt() == null
                    || record.getLocalPath() == null || !new File(record.getLocalPath()).isFile()) {
                continue;
            }
            DownloadTask task = taskFromRecord(record);
            activeTasks.put(task.getId(), task);
            if (task.getStatus() == DownloadStatus.PROCESSING || task.getStatus() == DownloadStatus.PENDING) {
                task.setStatus(DownloadStatus.PROCESSING);
                Object executionToken = beginTaskExecution(task);
                startTranscription(task, executionToken);
            }
        }
    }

    private DownloadTask taskFromRecord(DownloadRecord record) {
        VideoInfo info = new VideoInfo();
        info.setTitle(record.getTitle());
        info.setPlatform(record.getPlatform());
        info.setOrignalUrl(record.getUrl());
        info.setCover(record.getThumbnailUrl());
        return DownloadTask.builder()
                .id(record.getId())
                .batchId(record.getBatchId())
                .videoInfo(info)
                .mediaType(MediaType.VIDEO)
                .status(parseStatus(record.getStatus()))
                .progress(record.getProgress() == null ? 0 : record.getProgress())
                .localPath(record.getLocalPath())
                .thumbnailUrl(record.getThumbnailUrl())
                .createdAt(record.getCreatedAt())
                .extractText(Boolean.TRUE.equals(record.getExtractText()))
                .transcriptionStatus(parseTranscriptionStatus(record.getTranscriptionStatus()))
                .transcriptionProgress(record.getTranscriptionProgress() == null ? 0 : record.getTranscriptionProgress())
                .transcriptionError(record.getTranscriptionError())
                .transcriptTxtPath(record.getTranscriptTxtPath())
                .transcriptSrtPath(record.getTranscriptSrtPath())
                .mediaCompletedAt(record.getMediaCompletedAt())
                .build();
    }

    private TranscriptionStatus parseTranscriptionStatus(String status) {
        if (status == null || status.isBlank()) {
            return TranscriptionStatus.NONE;
        }
        try {
            return TranscriptionStatus.valueOf(status);
        } catch (Exception e) {
            return TranscriptionStatus.NONE;
        }
    }

    @Override
    public List<DownloadTask> getQueueTasks() {
        return new ArrayList<>(activeTasks.values());
    }

    @Override
    public int getQueueTaskCount() {
        List<String> statuses = List.of(
                DownloadStatus.PENDING.getValue(), DownloadStatus.DOWNLOADING.getValue(),
                DownloadStatus.PROCESSING.getValue(), DownloadStatus.FAILED.getValue(),
                DownloadStatus.CANCELLED.getValue());
        return Math.toIntExact(downloadRecordRepository.countByStatusIn(statuses));
    }

    private boolean isTaskResumable(DownloadTask task) {
        if (task == null) {
            return false;
        }
        DownloadStatus status = task.getStatus();
        return status == DownloadStatus.CANCELLED || status == DownloadStatus.FAILED;
    }

    private boolean isRootTask(DownloadTask task) {
        return task.getParentTaskId() == null;
    }

    private void restartTask(DownloadTask task) {
        if (task.getMediaType() == MediaType.VIDEO
                && task.getMediaCompletedAt() != null
                && task.isExtractText()
                && new File(task.getLocalPath()).isFile()) {
            task.setStatus(DownloadStatus.PROCESSING);
            task.setErrorMessage(null);
            Object executionToken = beginTaskExecution(task);
            updateTaskStatus(task, DownloadStatus.PROCESSING, null);
            startTranscription(task, executionToken);
            return;
        }
        resetTaskForRetry(task);
        if (task.getMediaType() == MediaType.VIDEO) {
            String dateFolder = resolveDateFolder(task.getLocalPath());
            // 平台媒体直链通常有时效，恢复时必须根据原始分享链接重新解析
            startVideoDownload(task, dateFolder, true);
            return;
        }
        if (task.getMediaType() == MediaType.IMAGE) {
            String imageUrl = resolveImageDownloadUrl(task);
            if (imageUrl == null || imageUrl.isBlank()) {
                updateTaskStatus(task, DownloadStatus.FAILED, "Missing image URL");
                return;
            }
            startImageDownload(task, imageUrl);
        }
    }

    private void restartChildTask(DownloadTask task) {
        if (!isParentReady(task)) {
            return;
        }
        resetTaskForRetry(task);

        if (task.getMediaType() == MediaType.AUDIO) {
            String parentPath = getParentLocalPath(task);
            if (parentPath == null || parentPath.isBlank()) {
                updateTaskStatus(task, DownloadStatus.FAILED, "Missing parent file");
                return;
            }
            Object executionToken = beginTaskExecution(task);
            Future<?> future = downloadExecutor.submit(() -> {
                synchronized (getTaskExecutionLock(task)) {
                    executeAudioRetry(task, parentPath, executionToken);
                }
            });
            task.setFuture(future);
            return;
        }

        if (task.getMediaType() == MediaType.IMAGE) {
            String parentPath = getParentLocalPath(task);
            if (parentPath == null || parentPath.isBlank()) {
                updateTaskStatus(task, DownloadStatus.FAILED, "Missing parent file");
                return;
            }
            String coverUrl = resolveCoverUrl(task);
            Object executionToken = beginTaskExecution(task);
            Future<?> future = downloadExecutor.submit(() -> {
                synchronized (getTaskExecutionLock(task)) {
                    executeCoverRetry(task, parentPath, coverUrl, executionToken);
                }
            });
            task.setFuture(future);
        }
    }

    private void resetTaskForRetry(DownloadTask task) {
        task.setStatus(DownloadStatus.PENDING);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setFuture(null);
        updateRecord(task.getId(), record -> {
            record.setStatus(DownloadStatus.PENDING.getValue());
            record.setProgress(0.0);
            record.setErrorMessage(null);
            record.setCompletedAt(null);
        });
        notifyProgressListeners(task);
    }

    private String resolveImageDownloadUrl(DownloadTask task) {
        if (task.getThumbnailUrl() != null && !task.getThumbnailUrl().isBlank()) {
            return task.getThumbnailUrl();
        }
        VideoInfo info = task.getVideoInfo();
        if (info == null) {
            return resolveImageUrlFromRecord(task.getId());
        }
        List<String> urls = info.getImageUrls();
        if (urls == null || urls.isEmpty()) {
            return resolveImageUrlFromRecord(task.getId());
        }
        return urls.get(0);
    }

    private boolean isParentReady(DownloadTask task) {
        if (task == null || task.getParentTaskId() == null) {
            return false;
        }
        DownloadRecord parent = downloadRecordRepository.findById(task.getParentTaskId()).orElse(null);
        if (parent == null || parent.getLocalPath() == null || parent.getLocalPath().isBlank()) {
            return false;
        }
        if (!DownloadStatus.COMPLETED.getValue().equals(parent.getStatus())) {
            return false;
        }
        return new File(parent.getLocalPath()).exists();
    }

    private String getParentLocalPath(DownloadTask task) {
        if (task == null || task.getParentTaskId() == null) {
            return null;
        }
        return downloadRecordRepository.findById(task.getParentTaskId())
                .map(DownloadRecord::getLocalPath)
                .orElse(null);
    }

    private String resolveCoverUrl(DownloadTask task) {
        if (task.getThumbnailUrl() != null && !task.getThumbnailUrl().isBlank()) {
            return task.getThumbnailUrl();
        }
        if (task.getParentTaskId() == null) {
            return null;
        }
        return downloadRecordRepository.findById(task.getParentTaskId())
                .map(DownloadRecord::getThumbnailUrl)
                .orElse(null);
    }

    private void executeAudioRetry(DownloadTask task, String parentPath, Object executionToken) {
        try {
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.PROCESSING, null)) {
                return;
            }
            boolean success = ffmpegService.extractAudio(parentPath, task.getLocalPath(),
                    progress -> {
                        if (isCurrentExecution(task, executionToken)) {
                            task.setProgress(progress);
                            notifyProgressListeners(task);
                        }
                    });
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (success) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.COMPLETED, null);
            } else {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        "Failed to extract audio");
            }
        } catch (Exception e) {
            if (isCurrentExecution(task, executionToken)) {
                log.error("Audio retry failed: {}", task.getId(), e);
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, e.getMessage());
            }
        } finally {
            if (isCurrentExecution(task, executionToken)) {
                if (task.getStatus() == DownloadStatus.COMPLETED) {
                    activeTasks.remove(task.getId(), task);
                }
                finishTaskExecution(task, executionToken);
            }
        }
    }

    private void executeCoverRetry(DownloadTask task, String parentPath, String coverUrl, Object executionToken) {
        try {
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.PROCESSING, null)) {
                return;
            }
            boolean success = false;
            if (coverUrl != null && !coverUrl.isBlank()) {
                success = downloadFile(coverUrl, task.getLocalPath(), null, executionToken);
            }
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (!success) {
                success = ffmpegService.extractCover(parentPath, task.getLocalPath());
            }
            if (!isCurrentExecution(task, executionToken)) {
                return;
            }
            if (success) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.COMPLETED, null);
            } else {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        "Failed to extract cover");
            }
        } catch (Exception e) {
            if (isCurrentExecution(task, executionToken)) {
                log.error("Cover retry failed: {}", task.getId(), e);
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, e.getMessage());
            }
        } finally {
            if (isCurrentExecution(task, executionToken)) {
                if (task.getStatus() == DownloadStatus.COMPLETED) {
                    activeTasks.remove(task.getId(), task);
                }
                finishTaskExecution(task, executionToken);
            }
        }
    }

    private String resolveImageUrlFromRecord(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return downloadRecordRepository.findById(taskId)
                .map(DownloadRecord::getUrl)
                .orElse(null);
    }

    private boolean refreshVideoInfo(DownloadTask task, Object executionToken) {
        if (!isCurrentExecution(task, executionToken)) {
            return false;
        }
        DownloadRecord record = downloadRecordRepository.findById(task.getId()).orElse(null);
        if (record == null || record.getUrl() == null || record.getUrl().isBlank()) {
            if (isCurrentExecution(task, executionToken)) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, "Missing video info");
                finishTaskExecution(task, executionToken);
            }
            return false;
        }
        try {
            VideoInfo parsed = videoParsingService.parseVideoUrl(record.getUrl());
            if (!isCurrentExecution(task, executionToken)) {
                return false;
            }
            if (parsed == null || parsed.getFirstVideoUrl() == null) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        "Failed to refresh video info");
                finishTaskExecution(task, executionToken);
                return false;
            }
            applyPlatformOverride(parsed, record.getUrl());
            task.setVideoInfo(parsed);
            if (task.getThumbnailUrl() == null || task.getThumbnailUrl().isBlank()) {
                task.setThumbnailUrl(parsed.getCover());
            }
            return true;
        } catch (VideoParsingService.ApiParseException e) {
            if (isCurrentExecution(task, executionToken)) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED, e.getMessage());
                finishTaskExecution(task, executionToken);
            }
            return false;
        } catch (Exception e) {
            if (isCurrentExecution(task, executionToken)) {
                updateTaskStatusIfCurrent(task, executionToken, DownloadStatus.FAILED,
                        "Failed to refresh video info");
                finishTaskExecution(task, executionToken);
            }
            return false;
        }
    }

    private String resolveDateFolder(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return LocalDateTime.now().format(DATE_FORMATTER);
        }
        File file = new File(localPath);
        File parent = file.getParentFile();
        if (parent == null) {
            return LocalDateTime.now().format(DATE_FORMATTER);
        }
        String name = parent.getName();
        return (name == null || name.isBlank()) ? LocalDateTime.now().format(DATE_FORMATTER) : name;
    }

    private DownloadTask findExistingChildTask(DownloadTask parentTask, MediaType mediaType) {
        if (parentTask == null || parentTask.getId() == null || mediaType == null) {
            return null;
        }
        for (DownloadTask task : activeTasks.values()) {
            if (task == null) {
                continue;
            }
            if (!Objects.equals(parentTask.getId(), task.getParentTaskId())) {
                continue;
            }
            if (task.getMediaType() != mediaType) {
                continue;
            }
            if (isTaskResumable(task) || task.getStatus() == DownloadStatus.PENDING) {
                return task;
            }
        }

        List<DownloadRecord> records = downloadRecordRepository.findByParentId(parentTask.getId());
        for (DownloadRecord record : records) {
            if (!matchesMediaType(record, mediaType)) {
                continue;
            }
            if (DownloadStatus.COMPLETED.getValue().equals(record.getStatus())) {
                continue;
            }
            DownloadTask task = DownloadTask.builder()
                    .id(record.getId())
                    .batchId(record.getBatchId())
                    .videoInfo(parentTask.getVideoInfo())
                    .mediaType(mediaType)
                    .status(parseStatus(record.getStatus()))
                    .progress(record.getProgress() == null ? 0 : record.getProgress())
                    .localPath(record.getLocalPath())
                    .thumbnailUrl(record.getThumbnailUrl())
                    .createdAt(record.getCreatedAt())
                    .parentTaskId(record.getParentId())
                    .build();
            activeTasks.put(task.getId(), task);
            notifyProgressListeners(task);
            return task;
        }
        return null;
    }

    private boolean matchesMediaType(DownloadRecord record, MediaType mediaType) {
        if (record == null || mediaType == null) {
            return false;
        }
        String recordType = record.getMediaType();
        return recordType != null && recordType.equalsIgnoreCase(mediaType.getValue());
    }

    private DownloadStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DownloadStatus.PENDING;
        }
        try {
            return DownloadStatus.valueOf(status);
        } catch (Exception e) {
            return DownloadStatus.PENDING;
        }
    }

    @Override
    public void addProgressListener(Consumer<DownloadTask> listener) {
        progressListeners.add(listener);
    }

    @Override
    public void removeProgressListener(Consumer<DownloadTask> listener) {
        progressListeners.remove(listener);
    }
}
