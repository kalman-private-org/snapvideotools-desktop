package com.kalman03.svt.desktop;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;

public class JavaFxApplication extends Application {

    private ConfigurableApplicationContext applicationContext;
    private static HostServices hostServicesInstance;

    @Override
    public void init() {
        applicationContext = new SpringApplicationBuilder(SnapVideoApplication.class).run();
    }

    @Override
    public void start(Stage stage) {
        hostServicesInstance = getHostServices();
        applicationContext.publishEvent(new StageReadyEvent(stage));
    }

    public static HostServices getHostServicesInstance() {
        return hostServicesInstance;
    }

    @Override
    public void stop() {
        applicationContext.close();
        Platform.exit();
    }

    public static class StageReadyEvent extends ApplicationEvent {
        private static final long serialVersionUID = 1L;

        public Stage getStage() {
            return (Stage) getSource();
        }

        public StageReadyEvent(Stage source) {
            super(source);
        }
    }
}
