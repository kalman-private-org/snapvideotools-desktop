package com.kalman03.svt.desktop.config;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;

/**
 * Spring-aware FXML loader that uses Spring's ApplicationContext
 * to create controller instances, enabling dependency injection.
 */
@Component
@RequiredArgsConstructor
public class FxmlLoaderConfig {

    private final ApplicationContext applicationContext;

    /**
     * Load an FXML file and return the root node.
     * Controllers are created by Spring, enabling dependency injection.
     *
     * @param fxmlPath path to the FXML file (e.g., "/fxml/MainView.fxml")
     * @return the loaded Parent node
     * @throws IOException if the FXML file cannot be loaded
     */
    public Parent load(String fxmlPath) throws IOException {
        FXMLLoader loader = createLoader(fxmlPath);
        return loader.load();
    }

    /**
     * Load an FXML file and return the FXMLLoader for access to the controller.
     *
     * @param fxmlPath path to the FXML file
     * @return the configured FXMLLoader after loading
     * @throws IOException if the FXML file cannot be loaded
     */
    public FXMLLoader loadWithLoader(String fxmlPath) throws IOException {
        FXMLLoader loader = createLoader(fxmlPath);
        loader.load();
        return loader;
    }

    /**
     * Create a configured FXMLLoader with Spring controller factory.
     *
     * @param fxmlPath path to the FXML file
     * @return configured FXMLLoader (not yet loaded)
     */
    public FXMLLoader createLoader(String fxmlPath) {
        URL fxmlUrl = getClass().getResource(fxmlPath);
        if (fxmlUrl == null) {
            throw new IllegalArgumentException("FXML file not found: " + fxmlPath);
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        loader.setControllerFactory(applicationContext::getBean);
        return loader;
    }

    /**
     * Get the Spring ApplicationContext.
     *
     * @return the ApplicationContext
     */
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }
}

