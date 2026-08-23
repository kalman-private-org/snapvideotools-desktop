package com.kalman03.svt.desktop.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.StageInitializer;
import com.kalman03.svt.desktop.service.LanguageService;
import com.kalman03.svt.desktop.service.AuthService;
import com.kalman03.svt.desktop.util.DesktopUtils;

@Component
@RequiredArgsConstructor
public class HeaderController {

    private final LanguageService languageService;
    private final StageInitializer stageInitializer;
    private final SpeechSettingsDialog speechSettingsDialog;
    private final AuthService authService;

    @FXML
    private ImageView logoIcon;

    @FXML
    private MenuButton langMenuBtn;

    @FXML
    private MenuButton profileMenuBtn;

    @FXML
    private MenuItem logoutMenuItem;

    @FXML
    private MenuItem settingsMenuItem;

    @FXML
    private MenuItem accountMenuItem;

    @FXML
    private Label titleLabel;

    @FXML
    public void initialize() {
        // 添加语言变更监听
        languageService.addLanguageChangeListener(this, this::updateUI);
        initializeLanguageMenu();

        // 初始化时应用当前语言
        updateUI();
        authService.addAccountChangeListener(this,
                () -> javafx.application.Platform.runLater(this::updateAccountBadge));
        updateAccountBadge();
    }

    private void initializeLanguageMenu() {
        langMenuBtn.getItems().clear();
        languageService.getSupportedLanguages().forEach(language -> {
            MenuItem item = new MenuItem(language.nativeName());
            item.setOnAction(event -> languageService.setLanguage(language));
            langMenuBtn.getItems().add(item);
        });
    }

    private void updateLanguageButton() {
        langMenuBtn.setText(languageService.getLanguageCode());
    }

    private void updateUI() {
        if (titleLabel != null) {
            titleLabel.setText(languageService.get("app.title"));
        }
        updateLanguageButton();
        if (logoutMenuItem != null) {
            logoutMenuItem.setText(languageService.get("header.logout"));
        }
        if (settingsMenuItem != null) {
            settingsMenuItem.setText(languageService.get("header.settings"));
        }
        if (accountMenuItem != null) {
            accountMenuItem.setText(languageService.get("header.account"));
        }
        updateAccountBadge();
    }

    @FXML
    private void handleLogout() {
        stageInitializer.logout();
    }

    @FXML
    private void handleSettings() {
        speechSettingsDialog.show(profileMenuBtn.getScene() == null
                ? null : profileMenuBtn.getScene().getWindow());
    }

    @FXML
    private void handleAccount() {
        DesktopUtils.openWebPage(authService.getWebOrigin() + "/desktop/account");
    }

    private void updateAccountBadge() {
        AuthService.AccountInfo account = authService.getAccountInfo();
        if (account == null) {
            profileMenuBtn.setText("");
            return;
        }
        if (account.getSubscription() != null && account.getSubscription().isActive()) {
            profileMenuBtn.setText("PRO · ∞");
        } else if (account.getQuota() != null) {
            profileMenuBtn.setText("FREE · " + account.getQuota().getRemaining() + "/" + account.getQuota().getLimit());
        }
    }
}
