package com.kalman03.svt.desktop;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.Node;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.config.FxmlLoaderConfig;
import com.kalman03.svt.desktop.controller.LoginController;
import com.kalman03.svt.desktop.service.AuthService;
import com.kalman03.svt.desktop.service.LanguageService;
import com.kalman03.svt.desktop.service.DesktopUpdateService;
import com.kalman03.svt.desktop.util.DesktopUtils;
import com.kalman03.svt.desktop.util.HttpClientUtil;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class StageInitializer implements ApplicationListener<JavaFxApplication.StageReadyEvent> {

    private final FxmlLoaderConfig fxmlLoaderConfig;
    private final AuthService authService;
    private final LanguageService languageService;
    private final DesktopUpdateService desktopUpdateService;

    private Stage primaryStage;
    private Scene scene;
    private boolean mainViewShown = false;
    private final long startedAtMs = System.currentTimeMillis();

    @Override
    public void onApplicationEvent(JavaFxApplication.StageReadyEvent event) {
        try {
            primaryStage = event.getStage();
            languageService.addLanguageChangeListener(this, this::applyLanguageDirection);
            HttpClientUtil.setLanguageProvider(() -> languageService.getCurrentLocale().toLanguageTag());

            // 设置窗口图标
            primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/logo.png")));

            // 设置窗口最小尺寸
            primaryStage.setMinWidth(800);
            primaryStage.setMinHeight(600);

            // 设置 401 未授权回调，自动跳转登录页
            authService.setOnUnauthorized(() -> {
                Platform.runLater(() -> {
                    try {
                        showLoginView(shouldShowSessionExpiredMessage());
                    } catch (IOException e) {
                        log.error("Failed to show login view after 401", e);
                    }
                });
            });

            authService.setOnSubscriptionRequired(message -> Platform.runLater(() -> showSubscriptionRequiredDialog(message)));

            // 检查登录状态
            if (authService.isLoggedIn()) {
                // 已登录，显示主界面
                showMainView();
            } else {
                // 未登录，显示登录界面
                showLoginView(false);
            }

            primaryStage.show();
            desktopUpdateService.checkAsync(release -> Platform.runLater(() -> showUpdateDialog(release)));
        } catch (IOException e) {
            log.error("Failed to initialize stage", e);
            throw new RuntimeException("Failed to load FXML", e);
        }
    }

    /**
     * 显示登录界面
     */
    private void showLoginView(boolean showSessionExpiredMessage) throws IOException {
        FXMLLoader loader = fxmlLoaderConfig.loadWithLoader("/fxml/LoginView.fxml");
        Parent loginRoot = loader.getRoot();

        // 获取 LoginController 并设置登录成功回调
        LoginController loginController = loader.getController();
        loginController.prepareForDisplay(showSessionExpiredMessage);
        loginController.setOnLoginSuccess(() -> {
            try {
                showMainView();
            } catch (IOException e) {
                log.error("Failed to load MainView after login", e);
            }
        });

        if (scene == null) {
            scene = new Scene(loginRoot, 1000, 800);
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            primaryStage.setScene(scene);
        } else {
            scene.setRoot(loginRoot);
        }
        applyLanguageDirection();

        primaryStage.setTitle("SnapVideoTools Desktop - Login");
        // 登录界面也最大化显示
        primaryStage.setMaximized(true);
    }

    /**
     * 显示主界面
     */
    private void showMainView() throws IOException {
        long loadStartedAtNanos = System.nanoTime();
        Parent mainRoot = fxmlLoaderConfig.load("/fxml/MainView.fxml");
        log.info("Main view shell loaded on JavaFX thread in {} ms",
                (System.nanoTime() - loadStartedAtNanos) / 1_000_000);
        mainViewShown = true;

        if (scene == null) {
            scene = new Scene(mainRoot, 1000, 800);
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            primaryStage.setScene(scene);
        } else {
            scene.setRoot(mainRoot);
        }
        applyLanguageDirection();

        primaryStage.setTitle("SnapVideoTools Desktop");
        primaryStage.setMaximized(true);
    }

    /**
     * 登出并返回登录界面
     */
    public void logout() {
        authService.logout();
        try {
            showLoginView(false);
        } catch (IOException e) {
            log.error("Failed to show login view after logout", e);
        }
    }

    /** 根据当前语言统一设置整个场景的阅读方向。 */
    private void applyLanguageDirection() {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        Node root = scene.getRoot();
        root.setNodeOrientation(languageService.getNodeOrientation());
    }

    private void showSubscriptionRequiredDialog(String message) {
        String title = languageService.get("subscription.title");
        String content;
        if ("DAILY_QUOTA_EXHAUSTED".equals(message)) {
            content = languageService.get("subscription.quota.message");
        } else {
            content = (message == null || message.isBlank()) ? languageService.get("subscription.message") : message;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(content);

        ButtonType openButton = new ButtonType(languageService.get("subscription.open"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(languageService.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(openButton, cancelButton);
        alert.getDialogPane().setNodeOrientation(languageService.getNodeOrientation());

        alert.showAndWait().ifPresent(selected -> {
            if (selected == openButton) {
                openSubscriptionPage();
            }
        });
    }

    private void openSubscriptionPage() {
        String baseUrl = authService.getWebOrigin();
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = normalized + "/desktop#pricing";
        boolean opened = DesktopUtils.openWebPage(url);
        if (!opened) {
            log.warn("Failed to open subscription page: {}", url);
        }
    }

    private void showUpdateDialog(DesktopUpdateService.LatestRelease release) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.setTitle(languageService.get("update.title"));
        alert.setHeaderText(languageService.get("update.header") + " " + release.getLatestVersion());
        String notes = release.getReleaseNotes() == null ? "" : release.getReleaseNotes();
        alert.setContentText(notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        ButtonType download = new ButtonType(languageService.get("update.download"), ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(languageService.get("update.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(download, later);
        alert.getDialogPane().setNodeOrientation(languageService.getNodeOrientation());
        alert.showAndWait().ifPresent(selected -> {
            if (selected == download && release.getDownloadUrl() != null) {
                DesktopUtils.openWebPage(release.getDownloadUrl());
            }
        });
    }

    private boolean shouldShowSessionExpiredMessage() {
        // Avoid showing an "expired" error during startup redirects; only show after user has been in the main view
        // for a short while (i.e., a real session expiry during use).
        if (!mainViewShown) {
            return false;
        }
        return System.currentTimeMillis() - startedAtMs > 5000;
    }
}
