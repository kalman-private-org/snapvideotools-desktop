package com.kalman03.svt.desktop.util;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.kalman03.svt.desktop.JavaFxApplication;

import javafx.application.HostServices;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DesktopUtils {

    public static boolean openDownloadDirectory(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return false;
        }

        try {
            Path path = Path.of(localPath);
            Path directory = Files.isDirectory(path) ? path : path.getParent();
            if (directory == null) {
                directory = path;
            }

            if (directory == null || !Files.exists(directory)) {
                return false;
            }

            openInFileExplorer(path, directory);
            return true;
        } catch (Exception e) {
            log.error("Failed to open download directory for path: {}", localPath, e);
            return false;
        }
    }

    public static boolean openWebPage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(url));
                    return true;
                }
            }

            HostServices hostServices = JavaFxApplication.getHostServicesInstance();
            if (hostServices != null) {
                hostServices.showDocument(url);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to open web page: {}", url, e);
        }
        return false;
    }

    private static void openInFileExplorer(Path originalPath, Path directory) throws Exception {
        // Prefer native file explorers (supports folder + file selection on Windows/macOS)
        if (tryOpenViaProcess(originalPath, directory)) {
            return;
        }

        // Fallback to Java Desktop API
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(directory.toFile());
                return;
            }
        }

        // Last resort: JavaFX HostServices
        HostServices hostServices = JavaFxApplication.getHostServicesInstance();
        if (hostServices != null) {
            hostServices.showDocument(directory.toUri().toString());
            return;
        }

        throw new UnsupportedOperationException("No supported method to open: " + directory);
    }

    private static boolean tryOpenViaProcess(Path originalPath, Path directory) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Path absOriginal = originalPath.toAbsolutePath().normalize();
            Path absDir = directory.toAbsolutePath().normalize();

            boolean originalExists = Files.exists(absOriginal);
            boolean isDir = Files.isDirectory(absOriginal);

            if (os.contains("win")) {
                // Explorer is the most reliable option on Windows; use proper argument separation.
                if (originalExists && !isDir) {
                    log.info("Opening folder via explorer.exe /select: file={}", absOriginal);
                    new ProcessBuilder("explorer.exe", "/select,", absOriginal.toString()).start();
                } else {
                    log.info("Opening folder via explorer.exe: dir={}", absDir);
                    new ProcessBuilder("explorer.exe", absDir.toString()).start();
                }
                return true;
            }

            if (os.contains("mac")) {
                if (originalExists && !isDir) {
                    new ProcessBuilder("open", "-R", absOriginal.toString()).start();
                } else {
                    new ProcessBuilder("open", absDir.toString()).start();
                }
                return true;
            }

            // Linux/others
            new ProcessBuilder("xdg-open", absDir.toString()).start();
            return true;
        } catch (Exception e) {
            log.debug("Failed to open via process, falling back to Desktop/HostServices", e);
            return false;
        }
    }
}
