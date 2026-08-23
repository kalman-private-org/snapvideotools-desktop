package com.kalman03.svt.desktop.controller;

import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.JavaFxApplication;
import com.kalman03.svt.desktop.service.AuthService;
import com.kalman03.svt.desktop.service.LanguageService;
import com.kalman03.svt.desktop.service.LanguageService.SupportedLanguage;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoginController {

    private static final long POLL_INTERVAL_MS = 1500;
    private static final long POLL_TIMEOUT_MS = 10 * 60 * 1000;

    private final AuthService authService;
    private final LanguageService languageService;

    private Runnable onLoginSuccess;
    private volatile boolean authInProgress = false;

    @FXML
    private VBox loginCard;

    @FXML
    private Label titleLabel;

    @FXML
    private Label subtitleLabel;

    @FXML
    private Label errorLabel;

    @FXML
    private Button submitBtn;

    @FXML
    private Label accountHintLabel;

    @FXML
    private ComboBox<SupportedLanguage> languageComboBox;

    public void prepareForDisplay(boolean showSessionExpiredMessage) {
        authInProgress = false;
        clearError();
        resetSubmitButton();
        if (showSessionExpiredMessage) {
            showError(languageService.get("login.error.session.expired"));
        }
    }

    @FXML
    public void initialize() {
        loginCard.setMaxHeight(Region.USE_PREF_SIZE);
        languageService.addLanguageChangeListener(this, this::updateUI);
        initLanguageComboBox();
        updateUI();
    }

    private void initLanguageComboBox() {
        languageComboBox.getItems().setAll(languageService.getSupportedLanguages());
        languageComboBox.getSelectionModel().select(languageService.getCurrentLanguage());
    }

    public void setOnLoginSuccess(Runnable callback) {
        this.onLoginSuccess = callback;
    }

    @FXML
    public void handleLanguageChange() {
        SupportedLanguage selected = languageComboBox.getSelectionModel().getSelectedItem();
        if (selected != null) {
            languageService.setLanguage(selected);
        }
    }

    @FXML
    public void handleSubmit() {
        if (authInProgress) {
            return;
        }

        clearError();
        authInProgress = true;

        AuthService.DeviceAuthorization authorization = authService.beginDeviceAuthorization();
        if (authorization == null || authorization.getDeviceCode() == null) {
            authInProgress = false;
            showError(languageService.get("login.error.network"));
            return;
        }
        String authorizeUrl = authorization.getVerificationUriComplete();

        if (!openAuthorizePage(authorizeUrl)) {
            authInProgress = false;
            showError(languageService.get("login.error.browser.open") + "\n" + authorizeUrl);
            return;
        }

        submitBtn.setDisable(true);
        submitBtn.setText(languageService.get("login.authorize.waiting"));

        CompletableFuture.runAsync(() -> pollForToken(authorization));
    }

    private boolean openAuthorizePage(String url) {
        try {
            HostServices hostServices = JavaFxApplication.getHostServicesInstance();
            if (hostServices == null) {
                return false;
            }
            hostServices.showDocument(url);
            return true;
        } catch (Exception e) {
            log.error("Failed to open authorize page: {}", url, e);
            return false;
        }
    }

    private void pollForToken(AuthService.DeviceAuthorization authorization) {
        long timeout = Math.min(POLL_TIMEOUT_MS, Math.max(30_000L, authorization.getExpiresIn() * 1_000L));
        long deadline = System.currentTimeMillis() + timeout;
        long interval = Math.max(POLL_INTERVAL_MS, authorization.getInterval() * 1_000L);

        while (authInProgress && System.currentTimeMillis() < deadline) {
            try {
                AuthService.PollTokenResult result = authService.pollDeviceToken(authorization.getDeviceCode());
                if (result != null && result.tokenPair() != null) {
                    Platform.runLater(() -> {
                        authInProgress = false;
                        if (onLoginSuccess != null) {
                            onLoginSuccess.run();
                        }
                    });
                    return;
                }
                if (result != null && !result.pending() && result.error() != null) {
                    Platform.runLater(() -> {
                        authInProgress = false;
                        resetSubmitButton();
                        showError(result.error());
                    });
                    return;
                }
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Polling failed", e);
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Platform.runLater(() -> {
            authInProgress = false;
            resetSubmitButton();
            showError(languageService.get("login.error.authorization.timeout"));
        });
    }

    private void resetSubmitButton() {
        submitBtn.setDisable(false);
        submitBtn.setText(languageService.get("login.button.authorize"));
    }

    private void updateUI() {
        titleLabel.setText(languageService.get("app.title"));
        subtitleLabel.setText(languageService.get("login.subtitle.signin"));
        accountHintLabel.setText(languageService.get("login.account.hint"));

        if (authInProgress) {
            submitBtn.setDisable(true);
            submitBtn.setText(languageService.get("login.authorize.waiting"));
            return;
        }

        submitBtn.setDisable(false);
        submitBtn.setText(languageService.get("login.button.authorize"));
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
