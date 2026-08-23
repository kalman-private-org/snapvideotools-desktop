package com.kalman03.svt.desktop.controller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.kordamp.ikonli.javafx.FontIcon;

import com.kalman03.svt.desktop.enums.MediaType;
import com.kalman03.svt.desktop.util.DesktopUtils;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.geometry.Rectangle2D;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller for individual task item.
 * Note: This is not a Spring component as multiple instances are needed.
 */
@Slf4j
public class TaskItemController {

    private static final int TITLE_MAX_LINES = 2;
    private static final double THUMBNAIL_REQUESTED_HEIGHT = 320;

    // 媒体类型对应的边框颜色
    private static final String VIDEO_BORDER_COLOR = "#5B7CFA";  // 蓝色 - 视频
    private static final String AUDIO_BORDER_COLOR = "#10B981";  // 绿色 - 音频
    private static final String IMAGE_BORDER_COLOR = "#F59E0B";  // 橙色 - 图片

    // 媒体类型对应的图标
    private static final String VIDEO_ICON = "mdmz-videocam";
    private static final String AUDIO_ICON = "mdal-audiotrack";
    private static final String IMAGE_ICON = "mdal-image";

    private String pendingStatusText = "Waiting...";
    private String failedStatusText = "Download failed";

    @FXML
    private HBox rootContainer;

    @FXML
    private StackPane thumbnailContainer;

    @FXML
    private ImageView thumbnailImageView;

    @FXML
    private Rectangle thumbnailProgressRing;

    @FXML
    private Label thumbnailProgressLabel;

    @FXML
    private Label thumbnailPlaceholderLabel;

    @FXML
    private FontIcon mediaTypeIcon;

    @FXML
    private VBox infoBox;

    @FXML
    private HBox titleBox;

    @FXML
    private Label deletedTagLabel;

    @FXML
    private Label titleLabel;

    @FXML
    private Label platformLabel;

    @FXML
    private Label fileSizeLabel;

    @FXML
    private Label downloadTimeLabel;

    @FXML
    private HBox progressBox;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label percentLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private HBox actionsBox;

    @FXML
    private Button retryBtn;

    @FXML
    private Button openFolderBtn;

    @FXML
    private Button closeBtn;

    private Runnable onCloseCallback;
    private Runnable onRetryCallback;
    private MediaType mediaType = MediaType.VIDEO;
    private Long taskId;
    private String localPath;
    private String thumbnailUrl;
    private String localThumbnailPath;
    private String thumbnailFallbackText;
    private Rectangle thumbnailClip;
    private boolean fileDeleted = false;
    private String deletedTagText = "已删除";
    private double currentProgress = -1;

    @FXML
    public void initialize() {
        initThumbnailClip();
        initThumbnailProgressRing();
        configureTitleLabel();
        updateOpenFolderButtonState();
        updateMediaTypeIcon();

        if (thumbnailContainer != null) {
            thumbnailContainer.widthProperty().addListener((obs, oldV, newV) -> updateThumbnailViewport());
            thumbnailContainer.heightProperty().addListener((obs, oldV, newV) -> updateThumbnailViewport());
            thumbnailContainer.widthProperty().addListener((obs, oldV, newV) -> updateThumbnailProgress(currentProgress));
            thumbnailContainer.heightProperty().addListener((obs, oldV, newV) -> updateThumbnailProgress(currentProgress));
        }
        if (thumbnailImageView != null) {
            thumbnailImageView.imageProperty().addListener((obs, oldV, newV) -> updateThumbnailViewport());
        }
    }

    private void initThumbnailProgressRing() {
        if (thumbnailContainer == null || thumbnailProgressRing == null) {
            return;
        }

        thumbnailProgressRing.widthProperty().bind(thumbnailContainer.widthProperty());
        thumbnailProgressRing.heightProperty().bind(thumbnailContainer.heightProperty());
        thumbnailProgressRing.arcWidthProperty().set(24);
        thumbnailProgressRing.arcHeightProperty().set(24);
        thumbnailProgressRing.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        thumbnailProgressRing.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
    }

    private void configureTitleLabel() {
        if (titleLabel == null) {
            return;
        }

        titleLabel.setWrapText(true);
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        updateTitleMaxHeight();
        titleLabel.fontProperty().addListener((obs, oldFont, newFont) -> updateTitleMaxHeight());
    }

    private void updateTitleMaxHeight() {
        if (titleLabel == null || titleLabel.getFont() == null) {
            return;
        }

        Text probe = new Text("Ag");
        probe.setFont(titleLabel.getFont());
        double lineHeight = probe.getLayoutBounds().getHeight();
        double maxHeight = Math.ceil(lineHeight * TITLE_MAX_LINES + 2);
        titleLabel.setMaxHeight(maxHeight);
    }

    /**
     * 设置媒体类型，用于区分边框颜色和图标
     */
    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
        updateBorderColor();
        updateMediaTypeIcon();
    }

    /**
     * 更新媒体类型图标和颜色
     */
    private void updateMediaTypeIcon() {
        if (mediaTypeIcon == null) {
            return;
        }

        String iconLiteral;
        String iconColor;
        switch (mediaType) {
            case AUDIO:
                iconLiteral = AUDIO_ICON;
                iconColor = AUDIO_BORDER_COLOR;
                break;
            case IMAGE:
                iconLiteral = IMAGE_ICON;
                iconColor = IMAGE_BORDER_COLOR;
                break;
            case VIDEO:
            default:
                iconLiteral = VIDEO_ICON;
                iconColor = VIDEO_BORDER_COLOR;
                break;
        }
        mediaTypeIcon.setIconLiteral(iconLiteral);
        mediaTypeIcon.setIconColor(javafx.scene.paint.Color.web(iconColor));
    }

    /**
     * 设置文件大小显示
     */
    public void setFileSize(Long fileSize) {
        if (fileSizeLabel == null) {
            return;
        }
        if (fileSize != null && fileSize > 0) {
            String sizeText = formatFileSize(fileSize);
            fileSizeLabel.setText(sizeText);
            fileSizeLabel.setVisible(true);
            fileSizeLabel.setManaged(true);
        } else {
            fileSizeLabel.setVisible(false);
            fileSizeLabel.setManaged(false);
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 设置下载完成时间
     */
    public void setDownloadTime(LocalDateTime completedAt) {
        if (downloadTimeLabel == null) {
            return;
        }
        if (completedAt != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            downloadTimeLabel.setText(completedAt.format(formatter));
            downloadTimeLabel.setVisible(true);
            downloadTimeLabel.setManaged(true);
        } else {
            downloadTimeLabel.setVisible(false);
            downloadTimeLabel.setManaged(false);
        }
    }

    /**
     * 设置已删除标签文本（用于国际化）
     */
    public void setDeletedTagText(String text) {
        this.deletedTagText = text;
        if (fileDeleted && deletedTagLabel != null) {
            deletedTagLabel.setText(text);
        }
    }

    /**
     * 检查并更新文件删除状态
     */
    public void checkFileDeleted() {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        File file = new File(localPath);
        setFileDeleted(!file.exists());
    }

    /**
     * 设置文件已删除状态
     */
    public void setFileDeleted(boolean deleted) {
        this.fileDeleted = deleted;
        updateDeletedState();
    }

    /**
     * 更新已删除状态的UI显示
     */
    private void updateDeletedState() {
        if (deletedTagLabel == null || titleLabel == null || rootContainer == null) {
            return;
        }

        if (fileDeleted) {
            // 显示已删除标签
            deletedTagLabel.setText(deletedTagText);
            deletedTagLabel.setVisible(true);
            deletedTagLabel.setManaged(true);

            // 标题和整体变灰
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #9CA3AF;");
            rootContainer.setOpacity(0.6);
        } else {
            // 隐藏已删除标签
            deletedTagLabel.setVisible(false);
            deletedTagLabel.setManaged(false);

            // 恢复正常样式
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
            rootContainer.setOpacity(1.0);
        }
    }


    /**
     * 设置任务ID
     */
    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    /**
     * 获取任务ID
     */
    public Long getTaskId() {
        return taskId;
    }

    /**
     * 更新边框颜色
     */
    private void updateBorderColor() {
        if (rootContainer == null) {
            return;
        }

        String borderColor;
        String thumbnailColor;
        switch (mediaType) {
            case AUDIO:
                borderColor = AUDIO_BORDER_COLOR;
                thumbnailColor = "linear-gradient(to bottom right, #047857, #10B981)";
                break;
            case IMAGE:
                borderColor = IMAGE_BORDER_COLOR;
                thumbnailColor = "linear-gradient(to bottom right, #D97706, #F59E0B)";
                break;
            case VIDEO:
            default:
                borderColor = VIDEO_BORDER_COLOR;
                thumbnailColor = "linear-gradient(to bottom right, #005c45, #00876a)";
                break;
        }

        // 设置左边框颜色
        rootContainer.setStyle("-fx-border-color: transparent transparent transparent " + borderColor + "; " +
                "-fx-border-width: 0 0 0 4; -fx-border-radius: 12;");

        // 设置缩略图背景色
        if (thumbnailContainer != null) {
            thumbnailContainer.setStyle("-fx-background-color: " + thumbnailColor + "; -fx-background-radius: 12;");
        }

        if (thumbnailProgressRing != null) {
            thumbnailProgressRing.setStroke(javafx.scene.paint.Color.web(borderColor));
        }
    }

    public void setTaskData(String title, String platform, double progress) {
        titleLabel.setText(title);
        platformLabel.setText(platform);

        if (progress >= 0 && progress <= 1.0) {
            updateThumbnailProgress(progress);
            if (progressBox != null) {
                progressBox.setVisible(false);
                progressBox.setManaged(false);
            }

            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
            retryBtn.setVisible(false);
            retryBtn.setManaged(false);
        } else if (progress == -1) {
            // Pending 状态
            updateThumbnailProgress(-1);
            if (progressBox != null) {
                progressBox.setVisible(false);
                progressBox.setManaged(false);
            }

            statusLabel.setText(pendingStatusText);
            statusLabel.setStyle("-fx-text-fill: #5B7CFA;");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);

            retryBtn.setVisible(false);
            retryBtn.setManaged(false);
        } else {
            // Error 状态
            updateThumbnailProgress(-2);
            if (progressBox != null) {
                progressBox.setVisible(false);
                progressBox.setManaged(false);
            }

            statusLabel.setText(failedStatusText);
            statusLabel.setStyle("-fx-text-fill: #EF4444;");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);

            retryBtn.setVisible(true);
            retryBtn.setManaged(true);
        }
    }

    /**
     * 在下载状态下方显示视频内部的转写阶段。
     */
    public void setTranscriptionState(String text, boolean error, boolean showRetry) {
        if (text == null || text.isBlank()) {
            return;
        }
        statusLabel.setText(text);
        statusLabel.setStyle(error ? "-fx-text-fill: #BA1A1A;" : "-fx-text-fill: #005c45;");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        retryBtn.setVisible(showRetry);
        retryBtn.setManaged(showRetry);
    }

    /** 更新任务状态文案，并立即刷新当前等待或失败状态。 */
    public void setStatusTexts(String pendingText, String failedText) {
        pendingStatusText = pendingText;
        failedStatusText = failedText;
        if (currentProgress == -1) {
            statusLabel.setText(pendingStatusText);
        } else if (currentProgress == -2) {
            statusLabel.setText(failedStatusText);
        }
    }

    public void setLocalPath(String localPath) {
        if (Objects.equals(this.localPath, localPath)) {
            return;
        }
        this.localPath = localPath;
        updateOpenFolderButtonState();
        refreshFileSizeFromDisk();
    }

    /**
     * 使用后台线程预读取的文件状态初始化历史卡片，避免在 JavaFX 线程访问慢速磁盘或网络盘。
     */
    public void setLocalPathSnapshot(String localPath, boolean fileExists,
                                     boolean directoryExists, Long fileSize) {
        this.localPath = localPath;
        if (openFolderBtn != null) {
            openFolderBtn.setDisable(!directoryExists);
        }
        setFileSize(fileSize);
        setFileDeleted(localPath != null && !localPath.isBlank() && !fileExists);
    }

    public void setThumbnailUrl(String thumbnailUrl, String fallbackText) {
        setThumbnailUrl(thumbnailUrl, null, fallbackText);
    }

    public void setThumbnailUrl(String thumbnailUrl, String localThumbnailPath, String fallbackText) {
        if (Objects.equals(this.thumbnailUrl, thumbnailUrl)
                && Objects.equals(this.localThumbnailPath, localThumbnailPath)
                && Objects.equals(this.thumbnailFallbackText, fallbackText)) {
            return;
        }

        this.thumbnailUrl = thumbnailUrl;
        this.localThumbnailPath = localThumbnailPath;
        this.thumbnailFallbackText = fallbackText;
        updateThumbnail();
    }

    private void initThumbnailClip() {
        if (thumbnailContainer == null || thumbnailImageView == null) {
            return;
        }

        if (thumbnailClip != null) {
            return;
        }

        thumbnailClip = new Rectangle();
        thumbnailClip.setArcWidth(24);
        thumbnailClip.setArcHeight(24);
        thumbnailClip.widthProperty().bind(thumbnailContainer.widthProperty());
        thumbnailClip.heightProperty().bind(thumbnailContainer.heightProperty());
        thumbnailImageView.setClip(thumbnailClip);

        thumbnailImageView.fitWidthProperty().bind(thumbnailContainer.widthProperty());
        thumbnailImageView.fitHeightProperty().bind(thumbnailContainer.heightProperty());
    }

    private void updateThumbnail() {
        if (thumbnailImageView == null || thumbnailPlaceholderLabel == null) {
            return;
        }

        String fallback = (thumbnailFallbackText == null || thumbnailFallbackText.isBlank())
                ? "No Thumbnail"
                : thumbnailFallbackText.trim();
        if (fallback.length() > 24) {
            fallback = fallback.substring(0, 24) + "...";
        }
        thumbnailPlaceholderLabel.setText(fallback);

        // 优先使用本地缩略图
        String effectiveThumbnailUrl = getEffectiveThumbnailUrl();

        if (effectiveThumbnailUrl == null || effectiveThumbnailUrl.isBlank()) {
            showThumbnailPlaceholder();
            thumbnailImageView.setImage(null);
            thumbnailImageView.setViewport(null);
            return;
        }

        Image image = new Image(effectiveThumbnailUrl, 0, THUMBNAIL_REQUESTED_HEIGHT, true, true, true);
        image.errorProperty().addListener((obs, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                // 如果本地缩略图加载失败，尝试远程URL
                if (effectiveThumbnailUrl.startsWith("file:") && thumbnailUrl != null && !thumbnailUrl.isBlank()) {
                    loadRemoteThumbnail();
                } else {
                    showThumbnailPlaceholder();
                    thumbnailImageView.setViewport(null);
                }
            }
        });
        image.progressProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && newV.doubleValue() >= 1.0 && !image.isError()) {
                updateThumbnailViewport();
                showThumbnailImage();
            }
        });

        thumbnailImageView.setImage(image);

        if (image.getProgress() >= 1.0 && !image.isError()) {
            updateThumbnailViewport();
            showThumbnailImage();
        } else {
            showThumbnailPlaceholder();
        }
    }

    /**
     * 获取有效的缩略图URL，优先本地资源
     */
    private String getEffectiveThumbnailUrl() {
        // 优先检查本地缩略图
        if (localThumbnailPath != null && !localThumbnailPath.isBlank()) {
            File localFile = new File(localThumbnailPath);
            if (localFile.exists()) {
                return localFile.toURI().toString();
            }
        }
        // 回退到远程URL
        return thumbnailUrl;
    }

    /**
     * 加载远程缩略图（当本地缩略图加载失败时）
     */
    private void loadRemoteThumbnail() {
        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            showThumbnailPlaceholder();
            thumbnailImageView.setViewport(null);
            return;
        }

        Image remoteImage = new Image(thumbnailUrl, 0, THUMBNAIL_REQUESTED_HEIGHT, true, true, true);
        remoteImage.errorProperty().addListener((obs, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                showThumbnailPlaceholder();
                thumbnailImageView.setViewport(null);
            }
        });
        remoteImage.progressProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && newV.doubleValue() >= 1.0 && !remoteImage.isError()) {
                updateThumbnailViewport();
                showThumbnailImage();
            }
        });

        thumbnailImageView.setImage(remoteImage);

        if (remoteImage.getProgress() >= 1.0 && !remoteImage.isError()) {
            updateThumbnailViewport();
            showThumbnailImage();
        } else {
            showThumbnailPlaceholder();
        }
    }

    private void updateThumbnailViewport() {
        if (thumbnailImageView == null || thumbnailContainer == null) {
            return;
        }

        Image image = thumbnailImageView.getImage();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            thumbnailImageView.setViewport(null);
            return;
        }

        double targetWidth = thumbnailContainer.getWidth() > 0 ? thumbnailContainer.getWidth() : thumbnailContainer.getPrefWidth();
        double targetHeight = thumbnailContainer.getHeight() > 0 ? thumbnailContainer.getHeight() : thumbnailContainer.getPrefHeight();
        if (targetWidth <= 0 || targetHeight <= 0) {
            targetWidth = 9;
            targetHeight = 16;
        }

        double targetRatio = targetWidth / targetHeight;
        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        double imageRatio = imageWidth / imageHeight;

        double viewportWidth = imageWidth;
        double viewportHeight = imageHeight;
        double minX = 0;
        double minY = 0;

        if (imageRatio > targetRatio) {
            viewportWidth = imageHeight * targetRatio;
            minX = (imageWidth - viewportWidth) / 2.0;
        } else if (imageRatio < targetRatio) {
            viewportHeight = imageWidth / targetRatio;
            minY = (imageHeight - viewportHeight) / 2.0;
        }

        thumbnailImageView.setViewport(new Rectangle2D(minX, minY, viewportWidth, viewportHeight));
    }

    private void showThumbnailImage() {
        thumbnailImageView.setVisible(true);
        thumbnailImageView.setManaged(true);
        thumbnailPlaceholderLabel.setVisible(false);
        thumbnailPlaceholderLabel.setManaged(false);
    }

    private void showThumbnailPlaceholder() {
        thumbnailImageView.setVisible(false);
        thumbnailImageView.setManaged(false);
        thumbnailPlaceholderLabel.setVisible(true);
        thumbnailPlaceholderLabel.setManaged(true);
    }

    private void updateThumbnailProgress(double progress) {
        if (thumbnailProgressRing == null || thumbnailProgressLabel == null) {
            return;
        }

        this.currentProgress = progress;

        if (progress < 0) {
            thumbnailProgressRing.setVisible(false);
            thumbnailProgressRing.setManaged(false);
            thumbnailProgressLabel.setVisible(false);
            thumbnailProgressLabel.setManaged(false);
            return;
        }

        double p = Math.max(0, Math.min(1.0, progress));
        updateThumbnailProgressRingDash(p);
        thumbnailProgressLabel.setText((int) Math.round(p * 100) + "%");

        thumbnailProgressRing.setVisible(true);
        thumbnailProgressRing.setManaged(true);
        thumbnailProgressLabel.setVisible(true);
        thumbnailProgressLabel.setManaged(true);
    }

    private void updateThumbnailProgressRingDash(double progress) {
        if (thumbnailProgressRing == null || thumbnailContainer == null) {
            return;
        }

        double w = thumbnailContainer.getWidth() > 0 ? thumbnailContainer.getWidth() : thumbnailContainer.getPrefWidth();
        double h = thumbnailContainer.getHeight() > 0 ? thumbnailContainer.getHeight() : thumbnailContainer.getPrefHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        double perimeter = 2.0 * (w + h);
        double dash = Math.max(0.0001, perimeter * progress);

        thumbnailProgressRing.getStrokeDashArray().setAll(dash, perimeter);
        thumbnailProgressRing.setStrokeDashOffset(perimeter * (1.0 - progress));
    }

    private void refreshFileSizeFromDisk() {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        try {
            File file = new File(localPath);
            if (file.exists() && file.isFile()) {
                setFileSize(file.length());
            }
        } catch (Exception ignored) {
        }
    }

    private void updateOpenFolderButtonState() {
        if (openFolderBtn == null) {
            return;
        }
        if (localPath == null || localPath.isBlank()) {
            openFolderBtn.setDisable(true);
            return;
        }

        try {
            Path path = Path.of(localPath);
            Path directory = Files.isDirectory(path) ? path : path.getParent();
            openFolderBtn.setDisable(directory == null || !Files.exists(directory));
        } catch (Exception e) {
            openFolderBtn.setDisable(true);
        }
    }

    public void setOnCloseCallback(Runnable callback) {
        this.onCloseCallback = callback;
    }

    public void setOnRetryCallback(Runnable callback) {
        this.onRetryCallback = callback;
    }

    @FXML
    public void handleClose() {
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    @FXML
    public void handleRetry() {
        if (onRetryCallback != null) {
            onRetryCallback.run();
        }
    }

    @FXML
    public void handleOpenFolder() {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        boolean opened = DesktopUtils.openDownloadDirectory(localPath);
        if (!opened) {
            log.warn("Failed to open download directory: {}", localPath);
        }
    }
}
