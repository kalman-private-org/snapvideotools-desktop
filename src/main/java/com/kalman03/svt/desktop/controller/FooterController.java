package com.kalman03.svt.desktop.controller;

import org.springframework.stereotype.Component;

import com.kalman03.svt.desktop.service.LanguageService;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FooterController {

    private final LanguageService languageService;
    private final SupportedPlatformsDialog supportedPlatformsDialog;

    private static final String TOOLTIP_KEY = "platformTooltip";

    @FXML
    private Label platformsLabel;

    @FXML
    private Label disclaimerLabel;

    @FXML
    private Button morePlatformsBtn;

    @FXML
    private Label tiktokIcon;

    @FXML
    private Label douyinIcon;

    @FXML
    private Label xiaohongshuIcon;

    @FXML
    private Label kuaishouIcon;

    @FXML
    private Label bilibiliIcon;

    @FXML
    private Label xiguaIcon;

    @FXML
    private Label toutiaoIcon;

    @FXML
    private Label weiboIcon;

    @FXML
    private Label pipixiaIcon;

    @FXML
    private Label zuiyouIcon;

    @FXML
    private Label pearvideoIcon;

    @FXML
    private Label xinpianchangIcon;

    @FXML
    private Label haokanIcon;

    @FXML
    private Label huyaIcon;

    @FXML
    private Label acfunIcon;

    @FXML
    public void initialize() {
        // 添加语言变更监听
        languageService.addLanguageChangeListener(this, this::updateUI);

        // 初始化时应用当前语言
        updateUI();
    }

    private void updateUI() {
        if (platformsLabel != null) {
            platformsLabel.setText(languageService.get("footer.platforms"));
        }
        if (disclaimerLabel != null) {
            disclaimerLabel.setText(languageService.get("footer.disclaimer"));
        }
        if (morePlatformsBtn != null) {
            String moreText = languageService.get("footer.platforms.more");
            Tooltip tooltip = morePlatformsBtn.getTooltip();
            if (tooltip == null) {
                tooltip = new Tooltip();
                morePlatformsBtn.setTooltip(tooltip);
            }
            tooltip.setText(moreText);
            morePlatformsBtn.setAccessibleText(moreText);
        }
        updatePlatformTooltips();
    }

    @FXML
    private void handleShowAllPlatforms() {
        supportedPlatformsDialog.show(morePlatformsBtn.getScene() == null
                ? null : morePlatformsBtn.getScene().getWindow());
    }

    private void updatePlatformTooltips() {
        applyTooltip(tiktokIcon, languageService.get("footer.platform.tiktok"));
        applyTooltip(douyinIcon, languageService.get("footer.platform.douyin"));
        applyTooltip(xiaohongshuIcon, languageService.get("footer.platform.xiaohongshu"));
        applyTooltip(kuaishouIcon, languageService.get("footer.platform.kuaishou"));
        applyTooltip(bilibiliIcon, languageService.get("footer.platform.bilibili"));
        applyTooltip(xiguaIcon, languageService.get("footer.platform.xigua"));
        applyTooltip(toutiaoIcon, languageService.get("footer.platform.toutiao"));
        applyTooltip(weiboIcon, languageService.get("footer.platform.weibo"));
        applyTooltip(pipixiaIcon, languageService.get("footer.platform.pipixia"));
        applyTooltip(zuiyouIcon, languageService.get("footer.platform.zuiyou"));
        applyTooltip(pearvideoIcon, languageService.get("footer.platform.pearvideo"));
        applyTooltip(xinpianchangIcon, languageService.get("footer.platform.xinpianchang"));
        applyTooltip(haokanIcon, languageService.get("footer.platform.haokan"));
        applyTooltip(huyaIcon, languageService.get("footer.platform.huya"));
        applyTooltip(acfunIcon, languageService.get("footer.platform.acfun"));
    }

    private void applyTooltip(Label icon, String text) {
        if (icon == null) {
            return;
        }
        Tooltip tooltip = (Tooltip) icon.getProperties().get(TOOLTIP_KEY);
        if (tooltip == null) {
            tooltip = new Tooltip(text);
            icon.getProperties().put(TOOLTIP_KEY, tooltip);
            installTooltipHandlers(icon);
            return;
        }
        tooltip.setText(text);
    }

    private void installTooltipHandlers(Label icon) {
        icon.setOnMouseEntered(event -> {
            Tooltip tooltip = (Tooltip) icon.getProperties().get(TOOLTIP_KEY);
            if (tooltip != null) {
                tooltip.show(icon, event.getScreenX() + 12, event.getScreenY() + 12);
            }
        });
        icon.setOnMouseMoved(event -> {
            Tooltip tooltip = (Tooltip) icon.getProperties().get(TOOLTIP_KEY);
            if (tooltip != null && tooltip.isShowing()) {
                tooltip.setAnchorX(event.getScreenX() + 12);
                tooltip.setAnchorY(event.getScreenY() + 12);
            }
        });
        icon.setOnMouseExited(event -> {
            Tooltip tooltip = (Tooltip) icon.getProperties().get(TOOLTIP_KEY);
            if (tooltip != null) {
                tooltip.hide();
            }
        });
    }
}
