package com.kalman03.svt.desktop.controller;

import com.kalman03.svt.desktop.service.LanguageService;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;

/** 展示与 Web 端公开平台注册表一致的完整平台清单。 */
@Component
public class SupportedPlatformsDialog {

    private static final int COLUMN_COUNT = 4;
    private static final List<PlatformItem> PLATFORMS = List.of(
            new PlatformItem("Facebook", "facebook.png"),
            new PlatformItem("Instagram", "instagram.png"),
            new PlatformItem("YouTube", "youtube.png"),
            new PlatformItem("TikTok", "tiktok.png"),
            new PlatformItem("LinkedIn", "linkedin.png"),
            new PlatformItem("Snapchat", "snapchat.png"),
            new PlatformItem("Reddit", "reddit.png"),
            new PlatformItem("Spotify", "spotify.png"),
            new PlatformItem("Douyin", "douyin.png"),
            new PlatformItem("Kuaishou", "kuaishou.png"),
            new PlatformItem("Weibo", "weibo.png"),
            new PlatformItem("Pinterest", "pinterest.png"),
            new PlatformItem("Twitter (X)", "twitter.png"),
            new PlatformItem("CapCut", "capcut.png"),
            new PlatformItem("Toutiao", "toutiao.png"),
            new PlatformItem("Dailymotion", "dailymotion.png"),
            new PlatformItem("Threads", "threads.png"),
            new PlatformItem("Terabox", null),
            new PlatformItem("Bilibili", "bilibili.png"),
            new PlatformItem("Xiaohongshu", "xiaohongshu.png"),
            new PlatformItem("SoundCloud", "soundcloud.png"),
            new PlatformItem("Huya", "huya.png"),
            new PlatformItem("Ixigua", "ixigua.png"),
            new PlatformItem("Haokan", "haokan.png"),
            new PlatformItem("Pipix", "pipix.png"),
            new PlatformItem("Tumblr", "tumblr.png"),
            new PlatformItem("Izuiyou", "izuiyou.png"),
            new PlatformItem("Bluesky", "bluesky.png"),
            new PlatformItem("AcFun", "acfun.png"),
            new PlatformItem("PearVideo", "pearvideo.png"),
            new PlatformItem("Xinpianchang", "xinpianchang.png")
    );

    private final LanguageService languageService;

    public SupportedPlatformsDialog(LanguageService languageService) {
        this.languageService = languageService;
    }

    public void show(Window owner) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(trimTrailingColon(languageService.get("footer.platforms")));
        dialog.setMinWidth(660);
        dialog.setMinHeight(500);

        Label eyebrow = new Label("SNAPVIDEOTOOLS DESKTOP");
        eyebrow.getStyleClass().add("platforms-dialog-eyebrow");
        Label title = new Label(trimTrailingColon(languageService.get("footer.platforms")));
        title.getStyleClass().add("platforms-dialog-title");
        VBox heading = new VBox(4, eyebrow, title);

        Label count = new Label(String.valueOf(PLATFORMS.size()));
        count.getStyleClass().add("platforms-dialog-count");
        HBox header = new HBox(16, heading, new Region(), count);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("platforms-dialog-header");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.getStyleClass().add("platforms-dialog-grid");
        for (int index = 0; index < PLATFORMS.size(); index++) {
            Node card = createPlatformCard(PLATFORMS.get(index));
            int column = index % COLUMN_COUNT;
            int row = index / COLUMN_COUNT;
            grid.add(card, column, row);
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setFillWidth(card, true);
        }

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.getStyleClass().add("platforms-dialog-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Button close = new Button(languageService.get("common.close"));
        close.getStyleClass().add("button-primary");
        close.setCancelButton(true);
        close.setOnAction(event -> dialog.close());
        HBox actions = new HBox(close);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("platforms-dialog-actions");

        VBox content = new VBox(header, scrollPane, actions);
        content.getStyleClass().add("platforms-dialog");
        content.setNodeOrientation(languageService.getNodeOrientation());

        Scene scene = new Scene(content, 780, 610);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private Node createPlatformCard(PlatformItem platform) {
        Node logo = createPlatformLogo(platform);
        Label name = new Label(platform.name());
        name.getStyleClass().add("platform-card-name");
        HBox card = new HBox(12, logo, name);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().add("platform-card");
        return card;
    }

    private Node createPlatformLogo(PlatformItem platform) {
        if (platform.imageName() != null) {
            URL resource = getClass().getResource("/icons/platforms/" + platform.imageName());
            if (resource != null) {
                ImageView imageView = new ImageView(new Image(resource.toExternalForm(), true));
                imageView.setFitWidth(28);
                imageView.setFitHeight(28);
                imageView.setPreserveRatio(true);
                HBox shell = new HBox(imageView);
                shell.setAlignment(Pos.CENTER);
                shell.getStyleClass().add("platform-card-logo");
                return shell;
            }
        }
        Label fallback = new Label(platform.name().substring(0, 1));
        fallback.setAlignment(Pos.CENTER);
        fallback.getStyleClass().addAll("platform-card-logo", "platform-card-logo-fallback");
        return fallback;
    }

    private String trimTrailingColon(String value) {
        return value == null ? "" : value.replaceFirst("[\\s:：]+$", "");
    }

    private record PlatformItem(String name, String imageName) {
    }
}
