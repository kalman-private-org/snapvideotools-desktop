package com.kalman03.svt.desktop.controller;

import com.kalman03.svt.desktop.enums.SpeechModelStatus;
import com.kalman03.svt.desktop.enums.SpeechModelFailureReason;
import com.kalman03.svt.desktop.model.SpeechModelSnapshot;
import com.kalman03.svt.desktop.service.LanguageService;
import com.kalman03.svt.desktop.service.SpeechModelService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音转文字设置窗口。模型任务由服务持有，窗口关闭不会影响后台执行。
 */
@Component
public class SpeechSettingsDialog {

    private final SpeechModelService speechModelService;
    private final LanguageService languageService;

    public SpeechSettingsDialog(SpeechModelService speechModelService, LanguageService languageService) {
        this.speechModelService = speechModelService;
        this.languageService = languageService;
    }

    public void show(Window owner) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(languageService.get("settings.title"));
        dialog.setResizable(false);

        Label title = new Label(languageService.get("settings.speech.title"));
        title.getStyleClass().add("settings-title");
        Label description = new Label(languageService.get("settings.speech.description"));
        description.setWrapText(true);
        description.getStyleClass().add("settings-description");

        Label capability = new Label(languageService.get("settings.speech.capability"));
        capability.getStyleClass().add("settings-feature-label");
        Label status = new Label();
        status.getStyleClass().add("settings-status");
        HBox capabilityRow = new HBox(12, capability, new Region(), status);
        HBox.setHgrow(capabilityRow.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
        capabilityRow.setAlignment(Pos.CENTER_LEFT);

        Label languages = new Label(languageService.get("settings.speech.languages"));
        languages.getStyleClass().add("settings-languages");
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        Label progressText = new Label();
        progressText.getStyleClass().add("settings-progress-text");
        Label hint = new Label();
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        Button enable = new Button(languageService.get("speech.enable.action"));
        enable.getStyleClass().add("button-primary");
        Button close = new Button(languageService.get("common.close"));
        close.getStyleClass().add("button-secondary");
        close.setOnAction(event -> dialog.close());
        HBox actions = new HBox(10, new Region(), enable, close);
        HBox.setHgrow(actions.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14, title, description, capabilityRow, languages,
                progress, progressText, hint, actions);
        content.setPadding(new Insets(28));
        content.getStyleClass().add("settings-dialog");
        content.setNodeOrientation(languageService.getNodeOrientation());

        AtomicReference<Consumer<SpeechModelSnapshot>> listenerRef = new AtomicReference<>();
        listenerRef.set(snapshot -> Platform.runLater(() -> update(snapshot, status, progress,
                progressText, hint, enable)));
        speechModelService.addStatusListener(listenerRef.get());
        enable.setOnAction(event -> {
            enable.setDisable(true);
            speechModelService.ensureReady();
        });
        dialog.setOnHidden(event -> speechModelService.removeStatusListener(listenerRef.get()));

        Scene scene = new Scene(content, 560, 420);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        dialog.setScene(scene);
        dialog.show();
    }

    private void update(SpeechModelSnapshot snapshot, Label status, ProgressBar progress,
                        Label progressText, Label hint, Button enable) {
        SpeechModelStatus current = snapshot.status();
        status.setText(languageService.get("settings.speech.status." + current.name().toLowerCase()));
        status.getStyleClass().removeAll("ready", "error", "working");
        status.getStyleClass().add(current == SpeechModelStatus.READY ? "ready"
                : current == SpeechModelStatus.ERROR ? "error" : "working");

        boolean downloading = current == SpeechModelStatus.DOWNLOADING;
        boolean processing = current == SpeechModelStatus.VERIFYING || current == SpeechModelStatus.LOADING;
        progress.setVisible(downloading || processing);
        progress.setManaged(downloading || processing);
        progress.setProgress(processing ? ProgressBar.INDETERMINATE_PROGRESS : snapshot.progress());
        progressText.setVisible(downloading);
        progressText.setManaged(downloading);
        if (downloading) {
            progressText.setText(languageService.get("settings.speech.progress",
                    formatBytes(snapshot.downloadedBytes()), formatBytes(snapshot.totalBytes())));
        }

        enable.setVisible(current == SpeechModelStatus.NOT_INSTALLED || current == SpeechModelStatus.ERROR);
        enable.setManaged(enable.isVisible());
        enable.setDisable(false);
        enable.setText(current == SpeechModelStatus.ERROR
                ? languageService.get("common.retry") : languageService.get("speech.enable.action"));
        if (current == SpeechModelStatus.ERROR) {
            hint.setText(resolveFailureMessage(snapshot));
        } else if (current == SpeechModelStatus.READY) {
            hint.setText(languageService.get("settings.speech.readyHint"));
        } else if (current == SpeechModelStatus.NOT_INSTALLED) {
            hint.setText(languageService.get("settings.speech.notInstalledHint"));
        } else {
            hint.setText(languageService.get("settings.speech.backgroundHint"));
        }
    }

    /** 将底层异常分类转换为用户可理解、可操作的失败原因。 */
    private String resolveFailureMessage(SpeechModelSnapshot snapshot) {
        SpeechModelFailureReason reason = snapshot.failureReason() == null
                ? SpeechModelFailureReason.UNKNOWN : snapshot.failureReason();
        return switch (reason) {
            case NETWORK -> languageService.get("settings.speech.error.network");
            case TIMEOUT -> languageService.get("settings.speech.error.timeout");
            case SERVER -> languageService.get("settings.speech.error.server",
                    fallbackDetail(snapshot.errorMessage()));
            case DISK_SPACE -> languageService.get("settings.speech.error.diskSpace");
            case PERMISSION -> languageService.get("settings.speech.error.permission");
            case CHECKSUM -> languageService.get("settings.speech.error.checksum");
            case ARCHIVE -> languageService.get("settings.speech.error.archive");
            case MODEL_LOAD -> languageService.get("settings.speech.error.modelLoad");
            case INTERRUPTED -> languageService.get("settings.speech.error.interrupted");
            case NONE -> languageService.get("settings.speech.error");
            case UNKNOWN -> languageService.get("settings.speech.error.unknown",
                    fallbackDetail(snapshot.errorMessage()));
        };
    }

    private String fallbackDetail(String detail) {
        return detail == null || detail.isBlank()
                ? languageService.get("settings.speech.error.unknownDetail") : detail;
    }

    private String formatBytes(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
