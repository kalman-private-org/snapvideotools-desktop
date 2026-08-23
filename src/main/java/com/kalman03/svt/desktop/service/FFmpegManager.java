package com.kalman03.svt.desktop.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

/**
 * FFmpeg管理器 - 负责从资源中提取并管理内嵌的FFmpeg二进制文件
 */
@Slf4j
@Component
public class FFmpegManager {

    private String ffmpegPath;
    private String ffprobePath;
    private boolean initialized = false;

    /**
     * 初始化FFmpeg，从资源中提取到临时目录
     */
    @PostConstruct
    public void init() {
        try {
            // 首先检查系统是否已安装FFmpeg
            if (isSystemFFmpegAvailable()) {
                ffmpegPath = "ffmpeg";
                ffprobePath = "ffprobe";
                initialized = true;
                log.info("Using system FFmpeg");
                return;
            }

            // 从内嵌资源中提取FFmpeg
            extractEmbeddedFFmpeg();
        } catch (Exception e) {
            log.error("Failed to initialize FFmpeg", e);
        }
    }

    /**
     * 检查系统FFmpeg是否可用
     */
    private boolean isSystemFFmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从资源中提取内嵌的FFmpeg
     */
    private void extractEmbeddedFFmpeg() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String platform = getPlatformDir(os, arch);
        String ffmpegName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";
        String ffprobeName = os.contains("win") ? "ffprobe.exe" : "ffprobe";

        // 创建应用数据目录
        Path appDataDir = getAppDataDir();
        Path ffmpegDir = appDataDir.resolve("ffmpeg");
        Files.createDirectories(ffmpegDir);

        // 提取FFmpeg
        Path ffmpegFile = ffmpegDir.resolve(ffmpegName);
        Path ffprobeFile = ffmpegDir.resolve(ffprobeName);

        // 检查是否已经提取过
        if (Files.exists(ffmpegFile) && Files.isExecutable(ffmpegFile)) {
            ffmpegPath = ffmpegFile.toString();
            ffprobePath = ffprobeFile.toString();
            initialized = true;
            log.info("Using cached FFmpeg from: {}", ffmpegDir);
            return;
        }

        // 从资源中提取
        String resourcePath = "/ffmpeg/" + platform + "/" + ffmpegName;
        String probeResourcePath = "/ffmpeg/" + platform + "/" + ffprobeName;

        if (!extractResource(resourcePath, ffmpegFile)) {
            log.warn("Embedded FFmpeg not found for platform: {}", platform);
            return;
        }

        extractResource(probeResourcePath, ffprobeFile);

        // 设置可执行权限（Unix系统）
        if (!os.contains("win")) {
            ffmpegFile.toFile().setExecutable(true);
            ffprobeFile.toFile().setExecutable(true);
        }

        ffmpegPath = ffmpegFile.toString();
        ffprobePath = ffprobeFile.toString();
        initialized = true;
        log.info("FFmpeg extracted to: {}", ffmpegDir);
    }

    /**
     * 从资源中提取文件
     */
    private boolean extractResource(String resourcePath, Path targetPath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Resource not found: {}", resourcePath);
                return false;
            }
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.error("Failed to extract resource: {}", resourcePath, e);
            return false;
        }
    }

    /**
     * 获取平台目录名
     */
    private String getPlatformDir(String os, String arch) {
        if (os.contains("win")) {
            return "win";
        } else if (os.contains("mac")) {
            if (arch.contains("aarch64") || arch.contains("arm")) {
                return "mac-aarch64";
            } else {
                return "mac-x64";
            }
        } else {
            return "linux";
        }
    }

    /**
     * 获取应用数据目录
     */
    private Path getAppDataDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null) {
                return Paths.get(appData, "SnapVideoTools");
            }
            return Paths.get(userHome, "AppData", "Local", "SnapVideoTools");
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "SnapVideoTools");
        } else {
            return Paths.get(userHome, ".snapvideotools");
        }
    }

    /**
     * 获取FFmpeg可执行文件路径
     */
    public String getFFmpegPath() {
        return ffmpegPath;
    }

    /**
     * 获取FFprobe可执行文件路径
     */
    public String getFFprobePath() {
        return ffprobePath;
    }

    /**
     * 检查FFmpeg是否可用
     */
    public boolean isAvailable() {
        return initialized && ffmpegPath != null;
    }

    /**
     * 获取FFmpeg版本信息
     */
    public String getVersion() {
        if (!isAvailable()) {
            return "Not available";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line : "Unknown";
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
