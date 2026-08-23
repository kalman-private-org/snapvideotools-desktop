package com.kalman03.svt.desktop.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.entity.DownloadRecord;
import com.kalman03.svt.desktop.enums.DownloadStatus;
import com.kalman03.svt.desktop.enums.MediaType;
import com.kalman03.svt.desktop.enums.TranscriptionStatus;
import com.kalman03.svt.desktop.model.DownloadTask;
import com.kalman03.svt.desktop.service.DownloadRecordService;
import com.kalman03.svt.desktop.service.DownloadService;
import com.kalman03.svt.desktop.service.LanguageService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskListController {

    private static final int HISTORY_ITEMS_PER_FRAME = 3;
    private static final int HISTORY_PAGE_SIZE = 30;

    private final LanguageService languageService;
    private final DownloadService downloadService;
    private final DownloadRecordService downloadRecordService;

    @FXML
    private Label queueLabel;

    @FXML
    private Label historyLabel;

    @FXML
    private VBox taskContainer;

    @FXML
    private StackPane stickyHeaderSlot;

    @FXML
    private VBox stickyHeader;

    @FXML
    private Label mediaFilterLabel;

    @FXML
    private ToggleButton allFilterBtn;

    @FXML
    private ToggleButton videoFilterBtn;

    @FXML
    private ToggleButton audioFilterBtn;

    @FXML
    private ToggleButton imageFilterBtn;

    @FXML
    private VBox emptyState;

    @FXML
    private Label emptyStateTitle;

    @FXML
    private Label emptyStateHint;

    @FXML
    private Button clearHistoryBtn;

    @FXML
    private Button stopAllBtn;

    @FXML
    private Button resumeAllBtn;

    @FXML
    private Button clearQueueBtn;

    // 存储队列和历史任务
    private final ObservableList<Parent> queueTasks = FXCollections.observableArrayList();
    private final ObservableList<Parent> historyTasks = FXCollections.observableArrayList();

    // 任务ID到UI组件的映射
    private final Map<Long, Parent> taskItemMap = new HashMap<>();
    private final Map<Long, TaskItemController> taskControllerMap = new HashMap<>();
    private final Map<Long, DownloadStatus> taskStatusMap = new HashMap<>();
    private final Map<Long, MediaType> taskMediaTypeMap = new HashMap<>();

    // 队列和历史分别保留自己的筛选状态
    private boolean showingQueue = true;
    private MediaFilter queueMediaFilter = MediaFilter.ALL;
    private MediaFilter historyMediaFilter = MediaFilter.ALL;
    private boolean stickyHeaderFloating;
    private boolean listenersRegistered;
    private final AtomicLong historyLoadGeneration = new AtomicLong();
    private final ExecutorService historyLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "desktop-history-loader");
        thread.setDaemon(true);
        return thread;
    });
    private Timeline historyRenderTimeline;
    private List<HistorySnapshot> historySnapshots = List.of();
    private int nextHistorySnapshotIndex;
    private int totalHistoryCount;

    @FXML
    public void initialize() {
        // 添加语言变更监听
        languageService.addLanguageChangeListener(this, this::updateUI);
        if (!listenersRegistered) {
            downloadService.addProgressListener(this::onTaskProgress);
            listenersRegistered = true;
        }

        long loadGeneration = resetViewState();
        loadQueueTasks();

        // 初始化时应用当前语言
        updateTabLabels();
        updateClearHistoryButtonText();
        updateStopAllButtonText();
        updateResumeAllButtonText();
        updateClearQueueButtonText();
        updateClearHistoryButtonState();
        updateStopAllButtonState();
        updateResumeAllButtonState();
        updateClearQueueButtonState();
        updateMediaFilterSelection();
        refreshTaskList();
        loadHistoryRecords(loadGeneration);
    }

    /**
     * 清理上一次视图留下的节点和索引，避免重新登录后重复加载并重复刷新。
     */
    private long resetViewState() {
        if (historyRenderTimeline != null) {
            historyRenderTimeline.stop();
            historyRenderTimeline = null;
        }
        queueTasks.clear();
        historyTasks.clear();
        taskItemMap.clear();
        taskControllerMap.clear();
        taskStatusMap.clear();
        taskMediaTypeMap.clear();
        showingQueue = true;
        queueMediaFilter = MediaFilter.ALL;
        historyMediaFilter = MediaFilter.ALL;
        stickyHeaderFloating = false;
        historySnapshots = List.of();
        nextHistorySnapshotIndex = 0;
        totalHistoryCount = 0;
        return historyLoadGeneration.incrementAndGet();
    }

    /** 数据库与文件系统读取放到后台，避免登录成功后阻塞 JavaFX 应用线程。 */
    private void loadHistoryRecords(long loadGeneration) {
        long startedAtNanos = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> downloadRecordService.getCompletedRecords().stream()
                        .map(this::createHistorySnapshot)
                        .toList(), historyLoader)
                .whenComplete((snapshots, error) -> Platform.runLater(() -> {
                    if (loadGeneration != historyLoadGeneration.get()) {
                        return;
                    }
                    if (error != null) {
                        log.error("Failed to load download history", error);
                        return;
                    }
                    historySnapshots = snapshots == null ? List.of() : snapshots;
                    log.info("Loaded {} history records off the JavaFX thread in {} ms",
                            historySnapshots.size(), (System.nanoTime() - startedAtNanos) / 1_000_000);
                    nextHistorySnapshotIndex = 0;
                    java.util.Set<Long> snapshotIds = historySnapshots.stream()
                            .map(HistorySnapshot::record)
                            .map(DownloadRecord::getId)
                            .collect(java.util.stream.Collectors.toSet());
                    long newlyCompleted = historyTasks.stream()
                            .map(Parent::getUserData)
                            .filter(Long.class::isInstance)
                            .map(Long.class::cast)
                            .filter(id -> !snapshotIds.contains(id))
                            .count();
                    totalHistoryCount = Math.toIntExact(historySnapshots.size() + newlyCompleted);
                    updateTabLabels();
                    renderNextHistoryPage(loadGeneration);
                }));
    }

    /** 每个 JavaFX 帧只创建少量卡片，给输入、滚动和窗口绘制留出时间。 */
    private void renderNextHistoryPage(long loadGeneration) {
        if (historyRenderTimeline != null || nextHistorySnapshotIndex >= historySnapshots.size()) {
            return;
        }
        int pageEnd = Math.min(nextHistorySnapshotIndex + HISTORY_PAGE_SIZE, historySnapshots.size());
        Timeline timeline = new Timeline();
        timeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(16), event -> {
            if (loadGeneration != historyLoadGeneration.get()) {
                timeline.stop();
                return;
            }
            int frameEnd = Math.min(nextHistorySnapshotIndex + HISTORY_ITEMS_PER_FRAME, pageEnd);
            while (nextHistorySnapshotIndex < frameEnd) {
                HistorySnapshot snapshot = historySnapshots.get(nextHistorySnapshotIndex++);
                addTaskItemFromRecord(snapshot.record(), false, snapshot, false);
            }
            updateTabLabels();
            if (!showingQueue) {
                refreshTaskList();
            }
            if (nextHistorySnapshotIndex >= pageEnd) {
                timeline.stop();
                historyRenderTimeline = null;
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        historyRenderTimeline = timeline;
        timeline.play();
    }

    /** 主滚动区接近底部时按页追加旧记录，避免一次挂载全部历史节点。 */
    public void loadMoreHistory() {
        if (!showingQueue) {
            renderNextHistoryPage(historyLoadGeneration.get());
        }
    }

    private HistorySnapshot createHistorySnapshot(DownloadRecord record) {
        String localPath = record.getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return new HistorySnapshot(record, false, false, false, record.getFileSize());
        }
        try {
            Path path = Path.of(localPath);
            boolean fileExists = Files.isRegularFile(path);
            Path directory = Files.isDirectory(path) ? path : path.getParent();
            boolean directoryExists = directory != null && Files.isDirectory(directory);
            Long fileSize = fileExists ? Files.size(path) : record.getFileSize();
            return new HistorySnapshot(record, true, fileExists, directoryExists, fileSize);
        } catch (Exception exception) {
            log.debug("Unable to read local history file state: {}", localPath, exception);
            return new HistorySnapshot(record, true, false, false, record.getFileSize());
        }
    }

    private void loadQueueTasks() {
        for (DownloadTask task : downloadService.getQueueTasks()) {
            addNewTaskItem(task);
        }
    }

    /**
     * 从数据库记录创建任务项
     */
    private void addTaskItemFromRecord(DownloadRecord record, boolean isQueue,
                                       HistorySnapshot snapshot, boolean refreshVisible) {
        if (record == null || record.getId() == null || taskItemMap.containsKey(record.getId())) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TaskItemView.fxml"));
            Parent taskItem = loader.load();

            TaskItemController controller = loader.getController();
            applyLocalizedTaskTexts(controller);
            taskItem.setUserData(record.getId());
            try {
                taskStatusMap.put(record.getId(), DownloadStatus.valueOf(record.getStatus()));
            } catch (Exception e) {
                taskStatusMap.put(record.getId(), DownloadStatus.PENDING);
            }

            // 兼容数据库中的小写值和枚举名称
            MediaType mediaType = resolveMediaType(record.getMediaType());
            controller.setMediaType(mediaType);

            // 计算进度值
            double progress;
            String status = record.getStatus();
            if (DownloadStatus.COMPLETED.getValue().equals(status)) {
                progress = 1.0;
            } else if (DownloadStatus.FAILED.getValue().equals(status)) {
                progress = -2;
            } else if (DownloadStatus.PENDING.getValue().equals(status)) {
                progress = -1;
            } else if (record.getProgress() != null) {
                progress = record.getProgress() / 100.0;
            } else {
                progress = 0;
            }

            controller.setTaskData(record.getTitle(), record.getPlatform(), progress);
            applyTranscriptionState(controller, record.getTranscriptionStatus(),
                    record.getTranscriptionProgress(), record.getTranscriptionError());
            controller.setTaskId(record.getId());
            applyThumbnailFromRecord(controller, record, mediaType);
            controller.setLocalPathSnapshot(record.getLocalPath(), snapshot.fileExists(),
                    snapshot.directoryExists(), snapshot.fileSize());

            // 设置文件大小和下载时间
            controller.setFileSize(snapshot.fileSize());
            controller.setDownloadTime(record.getCompletedAt());

            // 设置已删除标签文本
            controller.setDeletedTagText(languageService.get("task.deleted"));

            // 检查文件是否已删除
            controller.setFileDeleted(snapshot.hasLocalPath() && !snapshot.fileExists());

            ObservableList<Parent> targetList = isQueue ? queueTasks : historyTasks;

            // 设置删除回调（带确认对话框）
            controller.setOnCloseCallback(() -> {
                showDeleteConfirmDialog(record.getId(), record.getLocalPath(), taskItem);
            });
            controller.setOnRetryCallback(() -> handleRetry(record.getId()));

            targetList.add(taskItem);
            taskItemMap.put(record.getId(), taskItem);
            taskControllerMap.put(record.getId(), controller);
            taskMediaTypeMap.put(record.getId(), mediaType);

            // 只刷新当前可见的列表
            if (refreshVisible && ((isQueue && showingQueue) || (!isQueue && !showingQueue))) {
                refreshTaskList();
            }

            if (isQueue) {
                resortQueueTasks();
            }
        } catch (IOException e) {
            log.error("Failed to create task item", e);
        }
    }

    @PreDestroy
    public void shutdownHistoryLoader() {
        historyLoadGeneration.incrementAndGet();
        historyLoader.shutdownNow();
    }

    /**
     * 显示删除确认对话框
     */
    private void showDeleteConfirmDialog(Long recordId, String localPath, Parent taskItem) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);

        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));
        content.setStyle("-fx-background-color: white; -fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 20, 0, 0, 4);");

        Label titleLabel = new Label(languageService.get("dialog.delete.title"));
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label messageLabel = new Label(languageService.get("dialog.delete.message"));
        messageLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #49454F;");
        messageLabel.setWrapText(true);

        // 检查本地文件是否存在
        File file = localPath != null ? new File(localPath) : null;
        boolean fileExists = file != null && file.exists();

        CheckBox deleteFileCheckBox = new CheckBox(languageService.get("dialog.delete.alsoDeleteFile"));
        deleteFileCheckBox.setStyle("-fx-font-size: 13px;");
        deleteFileCheckBox.setVisible(fileExists);
        deleteFileCheckBox.setManaged(fileExists);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(languageService.get("common.cancel"));
        cancelBtn.setStyle("-fx-background-color: #F0F5F3; -fx-text-fill: #3D4F49; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> dialog.close());

        Button confirmBtn = new Button(languageService.get("common.confirm"));
        confirmBtn.setStyle("-fx-background-color: #BA1A1A; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> {
            boolean deleteFile = deleteFileCheckBox.isSelected();
            performDelete(recordId, localPath, deleteFile, taskItem);
            dialog.close();
        });

        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        content.getChildren().addAll(titleLabel, messageLabel, deleteFileCheckBox, buttonBox);

        content.setNodeOrientation(languageService.getNodeOrientation());
        Scene scene = new Scene(content, 360, fileExists ? 220 : 180);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    /**
     * 执行删除操作
     */
    private void performDelete(Long recordId, String localPath, boolean deleteFile, Parent taskItem) {
        if (deleteFile && localPath != null) {
            downloadRecordService.deleteRecordWithFile(recordId);
        } else {
            downloadRecordService.deleteRecord(recordId);
        }
        boolean wasQueued = queueTasks.remove(taskItem);
        boolean wasHistory = historyTasks.remove(taskItem);
        if (wasHistory && totalHistoryCount > 0) {
            totalHistoryCount--;
        }
        if (wasQueued) {
            downloadService.removeTask(recordId);
        }
        taskContainer.getChildren().remove(taskItem);
        taskItemMap.remove(recordId);
        taskControllerMap.remove(recordId);
        taskStatusMap.remove(recordId);
        taskMediaTypeMap.remove(recordId);
        updateTabLabels();
        refreshTaskList();
    }

    /**
     * 任务进度回调
     */
    private void onTaskProgress(DownloadTask task) {
        Long taskId = task.getId();
        taskStatusMap.put(taskId, task.getStatus());

        // 检查任务是否已存在
        if (taskItemMap.containsKey(taskId)) {
            // 更新现有任务
            TaskItemController controller = taskControllerMap.get(taskId);
            if (controller != null) {
                MediaType mediaType = normalizeMediaType(task.getMediaType());
                controller.setMediaType(mediaType);
                taskMediaTypeMap.put(taskId, mediaType);
                double progress;
                switch (task.getStatus()) {
                    case COMPLETED:
                        progress = 1.0;
                        try {
                            controller.setDownloadTime(java.time.LocalDateTime.now());
                            String localPath = task.getLocalPath();
                            if (localPath != null && !localPath.isBlank()) {
                                File file = new File(localPath);
                                if (file.exists() && file.isFile()) {
                                    controller.setFileSize(file.length());
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        // 移动到历史记录
                        moveTaskToHistory(taskId);
                        break;
                    case FAILED:
                        progress = -2;
                        break;
                    case PENDING:
                        progress = -1;
                        break;
                    case CANCELLED:
                        progress = -2;
                        break;
                    default:
                        progress = task.getProgress() / 100.0;
                }
                controller.setTaskData(
                        task.getVideoInfo() != null ? task.getVideoInfo().getTitle() : "Unknown",
                        task.getVideoInfo() != null ? task.getVideoInfo().getPlatform() : "Unknown",
                        progress
                );
                applyTranscriptionState(controller,
                        task.getTranscriptionStatus() == null ? null : task.getTranscriptionStatus().name(),
                        task.getTranscriptionProgress(), task.getTranscriptionError());
                applyThumbnailFromTask(controller, task);
                controller.setLocalPath(task.getLocalPath());
                if (task.getStatus() != DownloadStatus.COMPLETED) {
                    moveTaskToQueue(taskId);
                }
            }
        } else {
            // 创建新任务项
            addNewTaskItem(task);
        }

        resortQueueTasks();
        updateTabLabels();
    }

    /**
     * 添加新任务项
     */
    private void addNewTaskItem(DownloadTask task) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TaskItemView.fxml"));
            Parent taskItem = loader.load();

            TaskItemController controller = loader.getController();
            applyLocalizedTaskTexts(controller);
            MediaType mediaType = normalizeMediaType(task.getMediaType());
            controller.setMediaType(mediaType);
            controller.setTaskId(task.getId());
            taskItem.setUserData(task.getId());
            taskStatusMap.put(task.getId(), task.getStatus());
            taskMediaTypeMap.put(task.getId(), mediaType);

            double progress;
            switch (task.getStatus()) {
                case COMPLETED:
                    progress = 1.0;
                    break;
                case FAILED:
                case CANCELLED:
                    progress = -2;
                    break;
                case PENDING:
                    progress = -1;
                    break;
                default:
                    progress = task.getProgress() / 100.0;
            }

            String title = task.getVideoInfo() != null ? task.getVideoInfo().getTitle() : "Unknown";
            String platform = task.getVideoInfo() != null ? task.getVideoInfo().getPlatform() : "Unknown";
            controller.setTaskData(title, platform, progress);
            applyTranscriptionState(controller,
                    task.getTranscriptionStatus() == null ? null : task.getTranscriptionStatus().name(),
                    task.getTranscriptionProgress(), task.getTranscriptionError());
            applyThumbnailFromTask(controller, task);
            controller.setLocalPath(task.getLocalPath());

            // 设置已删除标签文本
            controller.setDeletedTagText(languageService.get("task.deleted"));

            boolean isQueue = task.getStatus() != DownloadStatus.COMPLETED;
            ObservableList<Parent> targetList = isQueue ? queueTasks : historyTasks;

            // 设置删除回调（带确认对话框）
            controller.setOnCloseCallback(() -> {
                showDeleteConfirmDialog(task.getId(), task.getLocalPath(), taskItem);
            });
            controller.setOnRetryCallback(() -> handleRetry(task.getId()));

            if (isQueue) {
                // Downloading items should stay on top of the queue
                int insertIndex = task.getStatus() == DownloadStatus.DOWNLOADING ? 0 : countDownloadingQueueItems();
                targetList.add(insertIndex, taskItem);
            } else {
                targetList.add(0, taskItem);
                totalHistoryCount++;
            }
            taskItemMap.put(task.getId(), taskItem);
            taskControllerMap.put(task.getId(), controller);

            // 只刷新当前可见的列表
            if ((isQueue && showingQueue) || (!isQueue && !showingQueue)) {
                if (isQueue) {
                    resortQueueTasks();
                } else {
                    refreshTaskList();
                }
            }
        } catch (IOException e) {
            log.error("Failed to create task item", e);
        }
    }

    /**
     * 将任务从队列移动到历史记录
     */
    private void moveTaskToHistory(Long taskId) {
        Parent taskItem = taskItemMap.get(taskId);
        if (taskItem != null && queueTasks.contains(taskItem)) {
            queueTasks.remove(taskItem);
            historyTasks.add(0, taskItem);
            totalHistoryCount++;
            taskStatusMap.put(taskId, DownloadStatus.COMPLETED);
            refreshTaskList();
        }
    }

    private void moveTaskToQueue(Long taskId) {
        Parent taskItem = taskItemMap.get(taskId);
        if (taskItem != null && historyTasks.remove(taskItem)) {
            queueTasks.add(0, taskItem);
            refreshTaskList();
        }
    }

    private void applyTranscriptionState(TaskItemController controller, String statusValue,
                                         Double progress, String error) {
        TranscriptionStatus status;
        try {
            status = statusValue == null ? TranscriptionStatus.NONE : TranscriptionStatus.valueOf(statusValue);
        } catch (Exception ignored) {
            status = TranscriptionStatus.NONE;
        }
        switch (status) {
            case WAITING_MODEL -> controller.setTranscriptionState(
                    languageService.get("transcription.waitingModel"), false, false);
            case PREPARING_AUDIO -> controller.setTranscriptionState(
                    languageService.get("transcription.preparingAudio"), false, false);
            case TRANSCRIBING -> controller.setTranscriptionState(
                    languageService.get("transcription.transcribing",
                            Math.round(progress == null ? 0 : progress)), false, false);
            case COMPLETED -> controller.setTranscriptionState(
                    "NO_SPEECH".equals(error) ? languageService.get("transcription.noSpeech")
                            : languageService.get("transcription.completed"), false, false);
            case FAILED -> controller.setTranscriptionState(
                    languageService.get("transcription.failed"), true, true);
            case NONE -> {
                // 非转写任务继续使用原下载状态展示。
            }
        }
    }

    @FXML
    private void handleQueueTabClick() {
        if (!showingQueue) {
            showingQueue = true;
            refreshTabStyles();
            updateMediaFilterSelection();
            updateMediaFilterLabels();
            refreshTaskList();
        }
    }

    @FXML
    private void handleHistoryTabClick() {
        if (showingQueue) {
            showingQueue = false;
            refreshTabStyles();
            updateMediaFilterSelection();
            updateMediaFilterLabels();
            refreshTaskList();
        }
    }

    private void refreshTabStyles() {
        if (showingQueue) {
            queueLabel.getStyleClass().removeAll("list-header-label-inactive");
            queueLabel.setStyle("-fx-cursor: hand;");
            historyLabel.getStyleClass().add("list-header-label-inactive");
            historyLabel.setStyle("-fx-border-color: transparent; -fx-cursor: hand;");
        } else {
            historyLabel.getStyleClass().removeAll("list-header-label-inactive");
            historyLabel.setStyle("-fx-cursor: hand;");
            queueLabel.getStyleClass().add("list-header-label-inactive");
            queueLabel.setStyle("-fx-border-color: transparent; -fx-cursor: hand;");
        }
        updateClearHistoryButtonState();
        updateStopAllButtonState();
        updateResumeAllButtonState();
        updateClearQueueButtonState();
    }

    private void refreshTaskList() {
        ObservableList<Parent> source = getCurrentTaskSource();
        MediaFilter filter = getCurrentMediaFilter();
        List<Parent> filteredTasks = new java.util.ArrayList<>();

        for (Parent taskItem : source) {
            if (matchesMediaFilter(taskItem, filter)) {
                filteredTasks.add(taskItem);
            }
        }

        if (filteredTasks.isEmpty()) {
            updateEmptyState(source.isEmpty(), filter);
            taskContainer.getChildren().setAll(emptyState);
        } else {
            taskContainer.getChildren().setAll(filteredTasks);
        }
    }

    @FXML
    private void handleAllMediaFilter() {
        applyMediaFilter(MediaFilter.ALL);
    }

    @FXML
    private void handleVideoMediaFilter() {
        applyMediaFilter(MediaFilter.VIDEO);
    }

    @FXML
    private void handleAudioMediaFilter() {
        applyMediaFilter(MediaFilter.AUDIO);
    }

    @FXML
    private void handleImageMediaFilter() {
        applyMediaFilter(MediaFilter.IMAGE);
    }

    /**
     * 将筛选状态保存在当前 Tab，切换 Tab 后可恢复之前的选择。
     */
    private void applyMediaFilter(MediaFilter filter) {
        if (showingQueue) {
            queueMediaFilter = filter;
        } else {
            historyMediaFilter = filter;
        }
        updateMediaFilterSelection();
        refreshTaskList();
    }

    private void updateMediaFilterSelection() {
        MediaFilter filter = getCurrentMediaFilter();
        allFilterBtn.setSelected(filter == MediaFilter.ALL);
        videoFilterBtn.setSelected(filter == MediaFilter.VIDEO);
        audioFilterBtn.setSelected(filter == MediaFilter.AUDIO);
        imageFilterBtn.setSelected(filter == MediaFilter.IMAGE);
    }

    private void updateMediaFilterLabels() {
        ObservableList<Parent> source = getCurrentTaskSource();
        mediaFilterLabel.setText(languageService.get("tasklist.filter.label"));
        allFilterBtn.setText(formatMediaFilterLabel("tasklist.filter.all", source.size()));
        videoFilterBtn.setText(formatMediaFilterLabel(
                "tasklist.filter.video", countTasksByMediaType(source, MediaType.VIDEO)));
        audioFilterBtn.setText(formatMediaFilterLabel(
                "tasklist.filter.audio", countTasksByMediaType(source, MediaType.AUDIO)));
        imageFilterBtn.setText(formatMediaFilterLabel(
                "tasklist.filter.image", countTasksByMediaType(source, MediaType.IMAGE)));
    }

    private String formatMediaFilterLabel(String languageKey, int count) {
        return languageService.get(languageKey) + " (" + count + ")";
    }

    private int countTasksByMediaType(List<Parent> source, MediaType mediaType) {
        int count = 0;
        for (Parent taskItem : source) {
            if (getTaskMediaType(taskItem) == mediaType) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesMediaFilter(Parent taskItem, MediaFilter filter) {
        if (filter == MediaFilter.ALL) {
            return true;
        }
        return getTaskMediaType(taskItem) == filter.getMediaType();
    }

    private MediaType getTaskMediaType(Parent taskItem) {
        if (taskItem != null && taskItem.getUserData() instanceof Long taskId) {
            return taskMediaTypeMap.getOrDefault(taskId, MediaType.VIDEO);
        }
        return MediaType.VIDEO;
    }

    private ObservableList<Parent> getCurrentTaskSource() {
        return showingQueue ? queueTasks : historyTasks;
    }

    private MediaFilter getCurrentMediaFilter() {
        return showingQueue ? queueMediaFilter : historyMediaFilter;
    }

    private void updateEmptyState(boolean sourceEmpty, MediaFilter filter) {
        if (!sourceEmpty && filter != MediaFilter.ALL) {
            emptyStateTitle.setText(languageService.get("tasklist.empty.filtered"));
            emptyStateHint.setText(languageService.get("tasklist.empty.hint.filtered"));
            return;
        }

        if (showingQueue) {
            emptyStateTitle.setText(languageService.get("tasklist.empty.queue"));
            emptyStateHint.setText(languageService.get("tasklist.empty.hint.queue"));
        } else {
            emptyStateTitle.setText(languageService.get("tasklist.empty.history"));
            emptyStateHint.setText(languageService.get("tasklist.empty.hint.history"));
        }
    }

    private void resortQueueTasks() {
        if (queueTasks.isEmpty()) {
            return;
        }

        List<Parent> downloading = new java.util.ArrayList<>();
        List<Parent> others = new java.util.ArrayList<>();

        for (Parent item : queueTasks) {
            if (isDownloadingItem(item)) {
                downloading.add(item);
            } else {
                others.add(item);
            }
        }

        downloading.addAll(others);
        queueTasks.setAll(downloading);

        if (showingQueue) {
            refreshTaskList();
        }
    }

    private int countDownloadingQueueItems() {
        int count = 0;
        for (Parent item : queueTasks) {
            if (isDownloadingItem(item)) {
                count++;
            }
        }
        return count;
    }

    private boolean isDownloadingItem(Parent item) {
        if (item == null) {
            return false;
        }
        Object userData = item.getUserData();
        if (!(userData instanceof Long id)) {
            return false;
        }
        return taskStatusMap.get(id) == DownloadStatus.DOWNLOADING;
    }

    private void updateTabLabels() {
        String queueText = languageService.get("tasklist.queue");
        String historyText = languageService.get("tasklist.history");
        queueLabel.setText(queueText + " (" + queueTasks.size() + ")");
        String historyCount = totalHistoryCount > historyTasks.size()
                ? historyTasks.size() + "/" + totalHistoryCount
                : Integer.toString(historyTasks.size());
        historyLabel.setText(historyText + " (" + historyCount + ")");
        updateMediaFilterLabels();
        updateClearHistoryButtonState();
        updateStopAllButtonState();
        updateResumeAllButtonState();
        updateClearQueueButtonState();
    }

    private void applyThumbnailFromRecord(TaskItemController controller, DownloadRecord record, MediaType mediaType) {
        if (controller == null || record == null) {
            return;
        }
        if (mediaType == MediaType.IMAGE) {
            controller.setThumbnailUrl(record.getThumbnailUrl(), record.getLocalPath(), record.getTitle());
        } else {
            controller.setThumbnailUrl(record.getThumbnailUrl(), record.getTitle());
        }
    }

    private MediaType resolveMediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.VIDEO;
        }
        for (MediaType mediaType : MediaType.values()) {
            if (mediaType.name().equalsIgnoreCase(value) || mediaType.getValue().equalsIgnoreCase(value)) {
                return mediaType;
            }
        }
        return MediaType.VIDEO;
    }

    private MediaType normalizeMediaType(MediaType mediaType) {
        return mediaType == null ? MediaType.VIDEO : mediaType;
    }

    private void applyThumbnailFromTask(TaskItemController controller, DownloadTask task) {
        if (controller == null || task == null) {
            return;
        }
        String title = task.getVideoInfo() != null ? task.getVideoInfo().getTitle() : "Unknown";
        String thumbnailUrl = task.getThumbnailUrl();
        if ((thumbnailUrl == null || thumbnailUrl.isBlank()) && task.getVideoInfo() != null) {
            thumbnailUrl = task.getVideoInfo().getCover();
        }
        if (task.getMediaType() == MediaType.IMAGE) {
            controller.setThumbnailUrl(thumbnailUrl, task.getLocalPath(), title);
        } else {
            controller.setThumbnailUrl(thumbnailUrl, title);
        }
    }

    private void updateUI() {
        updateTabLabels();
        updateClearHistoryButtonText();
        updateStopAllButtonText();
        updateResumeAllButtonText();
        updateClearQueueButtonText();
        taskControllerMap.values().forEach(this::applyLocalizedTaskTexts);
        refreshTaskList();
    }

    private void applyLocalizedTaskTexts(TaskItemController controller) {
        controller.setDeletedTagText(languageService.get("task.deleted"));
        controller.setStatusTexts(languageService.get("task.status.pending"),
                languageService.get("task.status.failed"));
    }

    private void updateClearHistoryButtonText() {
        if (clearHistoryBtn == null) {
            return;
        }
        String text = languageService.get("tasklist.clear");
        clearHistoryBtn.setText(text);

        Tooltip tooltip = clearHistoryBtn.getTooltip();
        if (tooltip == null) {
            clearHistoryBtn.setTooltip(new Tooltip(text));
        } else {
            tooltip.setText(text);
        }
    }

    private void updateStopAllButtonText() {
        if (stopAllBtn == null) {
            return;
        }
        String text = languageService.get("tasklist.stopAll");
        stopAllBtn.setText(text);

        Tooltip tooltip = stopAllBtn.getTooltip();
        if (tooltip == null) {
            stopAllBtn.setTooltip(new Tooltip(text));
        } else {
            tooltip.setText(text);
        }
    }

    private void updateResumeAllButtonText() {
        if (resumeAllBtn == null) {
            return;
        }
        String text = languageService.get("tasklist.resumeAll");
        resumeAllBtn.setText(text);

        Tooltip tooltip = resumeAllBtn.getTooltip();
        if (tooltip == null) {
            resumeAllBtn.setTooltip(new Tooltip(text));
        } else {
            tooltip.setText(text);
        }
    }

    private void updateClearQueueButtonText() {
        if (clearQueueBtn == null) {
            return;
        }
        String text = languageService.get("tasklist.clearQueue");
        clearQueueBtn.setText(text);

        Tooltip tooltip = clearQueueBtn.getTooltip();
        if (tooltip == null) {
            clearQueueBtn.setTooltip(new Tooltip(text));
        } else {
            tooltip.setText(text);
        }
    }

    private void updateClearHistoryButtonState() {
        if (clearHistoryBtn == null) {
            return;
        }
        boolean show = !showingQueue;
        clearHistoryBtn.setVisible(show);
        clearHistoryBtn.setManaged(show);
        clearHistoryBtn.setDisable(historyTasks.isEmpty());
    }

    private void updateStopAllButtonState() {
        if (stopAllBtn == null) {
            return;
        }
        boolean show = showingQueue && hasActiveQueueTasks();
        stopAllBtn.setVisible(show);
        stopAllBtn.setManaged(show);
        stopAllBtn.setDisable(!show);
    }

    private void updateResumeAllButtonState() {
        if (resumeAllBtn == null) {
            return;
        }
        boolean show = showingQueue && !hasActiveQueueTasks() && hasResumableQueueTasks();
        resumeAllBtn.setVisible(show);
        resumeAllBtn.setManaged(show);
        resumeAllBtn.setDisable(!show);
    }

    private void updateClearQueueButtonState() {
        if (clearQueueBtn == null) {
            return;
        }
        boolean show = showingQueue && !hasActiveQueueTasks();
        clearQueueBtn.setVisible(show);
        clearQueueBtn.setManaged(show);
        clearQueueBtn.setDisable(queueTasks.isEmpty());
    }

    private boolean hasResumableQueueTasks() {
        for (Parent item : queueTasks) {
            if (!(item.getUserData() instanceof Long id)) {
                continue;
            }
            DownloadStatus status = taskStatusMap.get(id);
            if (status == DownloadStatus.CANCELLED || status == DownloadStatus.FAILED) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveQueueTasks() {
        for (Parent item : queueTasks) {
            if (!(item.getUserData() instanceof Long id)) {
                continue;
            }
            DownloadStatus status = taskStatusMap.get(id);
            if (status == DownloadStatus.PENDING
                    || status == DownloadStatus.DOWNLOADING
                    || status == DownloadStatus.PROCESSING) {
                return true;
            }
        }
        return false;
    }

    @FXML
    private void handleStopAll() {
        if (queueTasks.isEmpty()) {
            return;
        }
        showStopAllConfirmDialog();
    }

    @FXML
    private void handleResumeAll() {
        if (!hasResumableQueueTasks()) {
            return;
        }
        downloadService.resumeAllDownloads();
        updateResumeAllButtonState();
    }

    @FXML
    private void handleClearQueue() {
        if (queueTasks.isEmpty()) {
            return;
        }
        showClearQueueConfirmDialog();
    }

    @FXML
    private void handleClearHistory() {
        if (historyTasks.isEmpty()) {
            return;
        }
        showClearHistoryConfirmDialog();
    }

    private void showStopAllConfirmDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);

        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));
        content.setStyle(
                "-fx-background-color: white; -fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 20, 0, 0, 4);");

        Label titleLabel = new Label(languageService.get("dialog.stopAll.title"));
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label messageLabel = new Label(languageService.get("dialog.stopAll.message"));
        messageLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #49454F;");
        messageLabel.setWrapText(true);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(languageService.get("common.cancel"));
        cancelBtn.setStyle(
                "-fx-background-color: #F0F5F3; -fx-text-fill: #3D4F49; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> dialog.close());

        Button confirmBtn = new Button(languageService.get("common.confirm"));
        confirmBtn.setStyle(
                "-fx-background-color: #BA1A1A; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> {
            performStopAll();
            dialog.close();
        });

        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        content.getChildren().addAll(titleLabel, messageLabel, buttonBox);

        content.setNodeOrientation(languageService.getNodeOrientation());
        Scene scene = new Scene(content, 360, 180);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void performStopAll() {
        downloadService.cancelAllDownloads();
        updateStopAllButtonState();
        updateResumeAllButtonState();
        updateClearQueueButtonState();
    }

    private void showClearQueueConfirmDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);

        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));
        content.setStyle(
                "-fx-background-color: white; -fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 20, 0, 0, 4);");

        Label titleLabel = new Label(languageService.get("dialog.clearQueue.title"));
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label messageLabel = new Label(languageService.get("dialog.clearQueue.message",
                downloadService.getQueueTaskCount()));
        messageLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #49454F;");
        messageLabel.setWrapText(true);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(languageService.get("common.cancel"));
        cancelBtn.setStyle(
                "-fx-background-color: #F0F5F3; -fx-text-fill: #3D4F49; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> dialog.close());

        Button confirmBtn = new Button(languageService.get("common.confirm"));
        confirmBtn.setStyle(
                "-fx-background-color: #BA1A1A; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> {
            performClearQueue();
            dialog.close();
        });

        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        content.getChildren().addAll(titleLabel, messageLabel, buttonBox);

        content.setNodeOrientation(languageService.getNodeOrientation());
        Scene scene = new Scene(content, 360, 180);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void performClearQueue() {
        DownloadService.ClearQueueResult result = downloadService.clearQueue();
        java.util.Set<Long> clearedIds = new java.util.HashSet<>(result.clearedTaskIds());
        java.util.Set<Parent> toRemove = new java.util.HashSet<>(queueTasks);
        for (Parent item : queueTasks) {
            if (item.getUserData() instanceof Long id) {
                clearedIds.add(id);
            }
        }
        for (Long taskId : clearedIds) {
            Parent item = taskItemMap.get(taskId);
            if (item != null) {
                toRemove.add(item);
            }
        }
        queueTasks.clear();

        if (showingQueue) {
            taskContainer.getChildren().removeIf(toRemove::contains);
        }

        for (Long taskId : clearedIds) {
            taskItemMap.remove(taskId);
            taskControllerMap.remove(taskId);
            taskStatusMap.remove(taskId);
            taskMediaTypeMap.remove(taskId);
        }

        updateTabLabels();
        refreshTaskList();
    }

    private void handleRetry(Long taskId) {
        if (taskId == null) {
            return;
        }
        downloadService.retryDownload(taskId);
        updateResumeAllButtonState();
        updateStopAllButtonState();
        updateClearQueueButtonState();
    }

    private void showClearHistoryConfirmDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);

        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));
        content.setStyle(
                "-fx-background-color: white; -fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 20, 0, 0, 4);");

        Label titleLabel = new Label(languageService.get("dialog.clearHistory.title"));
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label messageLabel = new Label(languageService.get("dialog.clearHistory.message"));
        messageLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #49454F;");
        messageLabel.setWrapText(true);

        CheckBox deleteFilesCheckBox = new CheckBox(languageService.get("dialog.clearHistory.alsoDeleteFiles"));
        deleteFilesCheckBox.setStyle("-fx-font-size: 13px;");

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(languageService.get("common.cancel"));
        cancelBtn.setStyle(
                "-fx-background-color: #F0F5F3; -fx-text-fill: #3D4F49; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> dialog.close());

        Button confirmBtn = new Button(languageService.get("common.confirm"));
        confirmBtn.setStyle(
                "-fx-background-color: #BA1A1A; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 100; -fx-padding: 10 24; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> {
            boolean deleteFiles = deleteFilesCheckBox.isSelected();
            performClearHistory(deleteFiles);
            dialog.close();
        });

        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        content.getChildren().addAll(titleLabel, messageLabel, deleteFilesCheckBox, buttonBox);

        content.setNodeOrientation(languageService.getNodeOrientation());
        Scene scene = new Scene(content, 380, 240);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void performClearHistory(boolean deleteFiles) {
        downloadRecordService.deleteCompletedRecords(deleteFiles);

        historyLoadGeneration.incrementAndGet();
        if (historyRenderTimeline != null) {
            historyRenderTimeline.stop();
            historyRenderTimeline = null;
        }
        historySnapshots = List.of();
        nextHistorySnapshotIndex = 0;
        totalHistoryCount = 0;

        java.util.Set<Parent> toRemove = new java.util.HashSet<>(historyTasks);
        historyTasks.clear();

        if (!showingQueue) {
            taskContainer.getChildren().removeIf(toRemove::contains);
        }

        taskItemMap.entrySet().removeIf(entry -> {
            if (toRemove.contains(entry.getValue())) {
                taskControllerMap.remove(entry.getKey());
                taskStatusMap.remove(entry.getKey());
                taskMediaTypeMap.remove(entry.getKey());
                return true;
            }
            return false;
        });

        updateTabLabels();
        refreshTaskList();
    }

    public StackPane getStickyHeaderSlot() {
        return stickyHeaderSlot;
    }

    public double getStickyHeaderHeight() {
        double height = stickyHeader.getHeight();
        return height > 0 ? height : stickyHeader.prefHeight(-1);
    }

    /**
     * 在原位与主界面吸顶容器之间移动同一个标题节点，保留所有交互状态。
     */
    public void setStickyHeaderFloating(VBox floatingHost, boolean floating) {
        if (stickyHeaderFloating == floating) {
            return;
        }

        // 换父容器可能触发布局监听，先更新状态以避免同一布局周期重复迁移
        stickyHeaderFloating = floating;

        if (floating) {
            double placeholderHeight = getStickyHeaderHeight();
            stickyHeaderSlot.setMinHeight(placeholderHeight);
            stickyHeaderSlot.setPrefHeight(placeholderHeight);
            stickyHeaderSlot.setMaxHeight(placeholderHeight);

            stickyHeaderSlot.getChildren().remove(stickyHeader);
            if (!stickyHeader.getStyleClass().contains("floating")) {
                stickyHeader.getStyleClass().add("floating");
            }
            floatingHost.getChildren().setAll(stickyHeader);
            floatingHost.setManaged(true);
            floatingHost.setVisible(true);
        } else {
            floatingHost.getChildren().remove(stickyHeader);
            stickyHeader.getStyleClass().remove("floating");
            stickyHeaderSlot.getChildren().setAll(stickyHeader);
            stickyHeaderSlot.setMinHeight(Region.USE_COMPUTED_SIZE);
            stickyHeaderSlot.setPrefHeight(Region.USE_COMPUTED_SIZE);
            stickyHeaderSlot.setMaxHeight(Region.USE_COMPUTED_SIZE);
            floatingHost.setVisible(false);
            floatingHost.setManaged(false);
        }
    }

    private enum MediaFilter {
        ALL(null),
        VIDEO(MediaType.VIDEO),
        AUDIO(MediaType.AUDIO),
        IMAGE(MediaType.IMAGE);

        private final MediaType mediaType;

        MediaFilter(MediaType mediaType) {
            this.mediaType = mediaType;
        }

        public MediaType getMediaType() {
            return mediaType;
        }
    }

    private record HistorySnapshot(DownloadRecord record, boolean hasLocalPath, boolean fileExists,
                                   boolean directoryExists, Long fileSize) {
    }
}
