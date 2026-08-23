package com.kalman03.svt.desktop.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

@Component
public class MainController {

    private boolean stickyUpdatePending;

    // fx:include 注入的是 FXML 的根元素
    // HeaderView.fxml 根元素是 HBox
    @FXML
    private HBox header;

    // DownloadAreaView.fxml 根元素是 VBox
    @FXML
    private VBox downloadArea;

    // TaskListView.fxml 根元素是 VBox
    @FXML
    private VBox taskList;

    // FooterView.fxml 根元素是 VBox，第一行平台图标、第二行免责声明
    @FXML
    private VBox footer;

    @FXML
    private ScrollPane mainScrollPane;

    @FXML
    private VBox stickyTaskHeaderHost;

    // 子控制器会通过 fx:include 自动注入
    @FXML
    private HeaderController headerController;

    @FXML
    private DownloadAreaController downloadAreaController;

    @FXML
    private TaskListController taskListController;

    @FXML
    private FooterController footerController;

    @FXML
    public void initialize() {
        // 子 FXML 完成布局后再计算吸顶阈值
        Platform.runLater(this::configureStickyTaskHeader);
    }

    private void configureStickyTaskHeader() {
        stickyTaskHeaderHost.setMaxHeight(Region.USE_PREF_SIZE);

        mainScrollPane.vvalueProperty().addListener((observable, oldValue, newValue) -> {
            requestStickyTaskHeaderUpdate();
            if (newValue.doubleValue() >= 0.92 && taskListController != null) {
                taskListController.loadMoreHistory();
            }
        });
        mainScrollPane.viewportBoundsProperty().addListener((observable, oldValue, newValue) -> requestStickyTaskHeaderUpdate());
        taskList.layoutBoundsProperty().addListener((observable, oldValue, newValue) -> requestStickyTaskHeaderUpdate());
        stickyTaskHeaderHost.addEventHandler(ScrollEvent.SCROLL, this::forwardStickyHeaderScroll);

        updateStickyTaskHeader();
    }

    /** 同一 JavaFX 脉冲中的多次布局通知只计算一次吸顶位置。 */
    private void requestStickyTaskHeaderUpdate() {
        if (stickyUpdatePending) {
            return;
        }
        stickyUpdatePending = true;
        Platform.runLater(() -> {
            stickyUpdatePending = false;
            updateStickyTaskHeader();
        });
    }

    /**
     * 悬浮栏位于滚动容器上层，在其上滚动时需将位移继续交给主滚动区。
     */
    private void forwardStickyHeaderScroll(ScrollEvent event) {
        double contentHeight = mainScrollPane.getContent().getBoundsInLocal().getHeight();
        double viewportHeight = mainScrollPane.getViewportBounds().getHeight();
        double scrollableHeight = contentHeight - viewportHeight;
        if (scrollableHeight <= 0 || event.getDeltaY() == 0) {
            return;
        }

        double range = mainScrollPane.getVmax() - mainScrollPane.getVmin();
        double nextValue = mainScrollPane.getVvalue() - event.getDeltaY() / scrollableHeight * range;
        nextValue = Math.max(mainScrollPane.getVmin(), Math.min(mainScrollPane.getVmax(), nextValue));
        mainScrollPane.setVvalue(nextValue);
        event.consume();
    }

    /**
     * 当任务标题区到达主滚动视口顶部时启用吸顶，离开任务模块后复位。
     */
    private void updateStickyTaskHeader() {
        if (mainScrollPane.getScene() == null || taskListController == null) {
            return;
        }

        Bounds slotBounds = taskListController.getStickyHeaderSlot().localToScene(
                taskListController.getStickyHeaderSlot().getBoundsInLocal());
        Bounds taskListBounds = taskList.localToScene(taskList.getBoundsInLocal());
        Bounds viewportBounds = mainScrollPane.localToScene(mainScrollPane.getBoundsInLocal());
        if (slotBounds == null || taskListBounds == null || viewportBounds == null) {
            return;
        }

        double viewportTop = viewportBounds.getMinY();
        double headerHeight = taskListController.getStickyHeaderHeight();
        boolean reachedViewportTop = slotBounds.getMinY() <= viewportTop;
        boolean taskListStillVisible = taskListBounds.getMaxY() > viewportTop + headerHeight;

        taskListController.setStickyHeaderFloating(
                stickyTaskHeaderHost,
                reachedViewportTop && taskListStillVisible);
    }

    public HeaderController getHeaderController() {
        return headerController;
    }

    public DownloadAreaController getDownloadAreaController() {
        return downloadAreaController;
    }

    public TaskListController getTaskListController() {
        return taskListController;
    }

    public FooterController getFooterController() {
        return footerController;
    }
}
