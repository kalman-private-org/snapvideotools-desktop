package com.kalman03.svt.desktop.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.HBox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.enums.TabType;
import com.kalman03.svt.desktop.model.DownloadRequest;
import com.kalman03.svt.desktop.service.DownloadService;
import com.kalman03.svt.desktop.service.LanguageService;
import com.kalman03.svt.desktop.service.VideoParsingService;
import com.kalman03.svt.desktop.service.SpeechModelService;
import com.kalman03.svt.desktop.service.AuthService;
import com.kalman03.svt.desktop.enums.SpeechModelStatus;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DownloadAreaController {

    private final DownloadService downloadService;
    private final LanguageService languageService;
    private final VideoParsingService videoParsingService;
    private final SpeechModelService speechModelService;
    private final AuthService authService;

    @FXML
    private HBox tabGroup;

    @FXML
    private Button videoLinkBtn;

    @FXML
    private Button userProfileBtn;

    @FXML
    private Label userProfileProBadge;

    @FXML
    private TextArea linkInput;

    @FXML
    private CheckBox videoCheck;

    @FXML
    private CheckBox coverCheck;

    @FXML
    private CheckBox audioCheck;

    @FXML
    private CheckBox textCheck;

    @FXML
    private Button downloadBtn;

    @FXML
    private Label errorLabel;

    private boolean submitInProgress = false;
    private Node defaultDownloadBtnGraphic;
    private ProgressIndicator downloadSpinner;
    private final Tooltip userProfileProTooltip = new Tooltip();
    private boolean profileEnabled;

    @FXML
    public void initialize() {
        // 设置标签按钮的点击事件
        setupTabButtons();

        // 添加语言变更监听
        languageService.addLanguageChangeListener(this, this::updateUI);

        // 输入内容变化时隐藏错误提示
        linkInput.textProperty().addListener((observable, oldValue, newValue) -> hideError());

        // 初始化时应用当前语言
        updateUI();

        authService.addAccountChangeListener(this, () -> Platform.runLater(this::updateCapabilities));
        updateCapabilities();

        defaultDownloadBtnGraphic = downloadBtn.getGraphic();
        downloadSpinner = new ProgressIndicator();
        downloadSpinner.setProgress(-1);
        downloadSpinner.setPrefSize(16, 16);
        downloadSpinner.setMaxSize(16, 16);
        downloadSpinner.getStyleClass().add("button-spinner");
    }

    // 当前选中的 tab
    private Button currentTab;

    private void setupTabButtons() {
        videoLinkBtn.setOnAction(e -> selectTab(videoLinkBtn));
        userProfileBtn.setOnAction(e -> selectTab(userProfileBtn));
        // 默认选中视频链接 tab
        currentTab = videoLinkBtn;
    }

    private void selectTab(Button selectedBtn) {
        if (selectedBtn == userProfileBtn && !profileEnabled) {
            authService.handleSubscriptionRequired(languageService.get("subscription.profile.message"));
            return;
        }
        // 移除所有按钮的 selected 样式
        videoLinkBtn.getStyleClass().remove("selected");
        userProfileBtn.getStyleClass().remove("selected");

        // 添加选中样式
        selectedBtn.getStyleClass().add("selected");

        // 更新当前 tab 并切换 placeholder
        currentTab = selectedBtn;
        updatePlaceholder();
    }

    private void updatePlaceholder() {
        if (currentTab == videoLinkBtn) {
            linkInput.setPromptText(languageService.get("download.input.placeholder.video"));
        } else if (currentTab == userProfileBtn) {
            linkInput.setPromptText(languageService.get("download.input.placeholder.profile"));
        }
    }

    @FXML
    public void handleDownload() {
        if (submitInProgress) {
            return;
        }
        // 隐藏之前的错误提示
        hideError();

        String content = linkInput.getText();
        if (content == null || content.trim().isEmpty()) {
            showError(languageService.get("error.no.links"));
            return;
        }

        // 验证是否包含有效链接
        List<String> urls = videoParsingService.extractUrls(content);
        if (urls.isEmpty()) {
            showError(languageService.get("error.no.links"));
            return;
        }

        // 构建下载请求
        DownloadRequest request = DownloadRequest.builder()
                .content(content.trim())
                .tabType(getCurrentTabType())
                .extractCover(coverCheck.isSelected())
                .extractAudio(audioCheck.isSelected())
                .extractText(textCheck.isSelected())
                .build();

        if (request.isExtractText() && requiresModelConfirmation()) {
            if (!confirmSpeechModelEnable()) {
                return;
            }
            // 下载和媒体解析并行，模型失败会由任务卡片和设置窗口分别呈现。
            speechModelService.ensureReady();
        }

        setSubmitInProgress(true);
        downloadService.submitDownloadAsync(request).whenComplete((result, throwable) ->
                Platform.runLater(() -> {
                    setSubmitInProgress(false);
                    if (throwable != null) {
                        String message = throwable.getMessage();
                        showError(message == null || message.isBlank() ? "Unknown error" : message);
                        return;
                    }
                    if (result != null && result.errorMessage() != null && !result.errorMessage().isBlank()) {
                        showError(result.errorMessage());
                    }
                    if (result != null && result.createdTasks() > 0) {
                        linkInput.clear();
                    }
                })
        );
    }

    private boolean requiresModelConfirmation() {
        SpeechModelStatus status = speechModelService.getSnapshot().status();
        return status == SpeechModelStatus.NOT_INSTALLED || status == SpeechModelStatus.ERROR;
    }

    private boolean confirmSpeechModelEnable() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (downloadBtn.getScene() != null) {
            alert.initOwner(downloadBtn.getScene().getWindow());
            alert.getDialogPane().getStylesheets().addAll(downloadBtn.getScene().getStylesheets());
        }
        alert.setTitle(languageService.get("speech.enable.title"));
        alert.setHeaderText(languageService.get("speech.enable.title"));
        alert.setContentText(languageService.get("speech.enable.message"));
        ButtonType enable = new ButtonType(languageService.get("speech.enable.action"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(languageService.get("common.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(enable, cancel);
        alert.getDialogPane().setNodeOrientation(languageService.getNodeOrientation());
        return alert.showAndWait().filter(enable::equals).isPresent();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void setSubmitInProgress(boolean inProgress) {
        submitInProgress = inProgress;
        downloadBtn.setDisable(inProgress);
        if (inProgress) {
            downloadBtn.setText(languageService.get("download.button.loading"));
            downloadBtn.setGraphic(downloadSpinner);
        } else {
            downloadBtn.setText(languageService.get("download.button"));
            downloadBtn.setGraphic(defaultDownloadBtnGraphic);
        }
    }

    /**
     * 获取当前选中的Tab类型
     */
    private TabType getCurrentTabType() {
        if (currentTab == userProfileBtn) {
            return TabType.USER_PROFILE;
        }
        return TabType.VIDEO_LINK;
    }

    private void updateUI() {
        videoLinkBtn.setText(languageService.get("download.tab.video"));
        userProfileBtn.setText(languageService.get("download.tab.profile"));
        userProfileProTooltip.setText(languageService.get("download.tab.profile.pro.tooltip"));
        updatePlaceholder();
        videoCheck.setText(languageService.get("download.option.video"));
        videoCheck.setSelected(true);
        coverCheck.setText(languageService.get("download.option.cover"));
        audioCheck.setText(languageService.get("download.option.audio"));
        textCheck.setText(languageService.get("download.option.text"));
        if (submitInProgress) {
            downloadBtn.setText(languageService.get("download.button.loading"));
        } else {
            downloadBtn.setText(languageService.get("download.button"));
        }
        updateCapabilities();
    }

    private void updateCapabilities() {
        AuthService.AccountInfo account = authService.getAccountInfo();
        profileEnabled = account != null && account.isProfileExtraction();
        userProfileBtn.setTooltip(profileEnabled ? null : userProfileProTooltip);
        userProfileBtn.setAccessibleHelp(profileEnabled ? null : userProfileProTooltip.getText());
        userProfileProBadge.setVisible(!profileEnabled);
        userProfileProBadge.setManaged(!profileEnabled);
        if (profileEnabled) {
            userProfileBtn.getStyleClass().remove("pro-restricted");
        } else if (!userProfileBtn.getStyleClass().contains("pro-restricted")) {
            userProfileBtn.getStyleClass().add("pro-restricted");
        }
        if (!profileEnabled && currentTab == userProfileBtn) {
            selectTab(videoLinkBtn);
        }
    }
}
