package com.kalman03.svt.desktop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * FFmpeg服务，用于提取音频和封面
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FFmpegService {

    private final FFmpegManager ffmpegManager;

    /**
     * 从视频中提取音频
     *
     * @param videoPath        视频文件路径
     * @param outputPath       输出音频文件路径
     * @param progressCallback 进度回调（0-100）
     * @return 是否成功
     */
    public boolean extractAudio(String videoPath, String outputPath, Consumer<Double> progressCallback) {
        try {
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                log.error("Video file not found: {}", videoPath);
                return false;
            }

            // 确保输出目录存在
            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 首先获取视频时长
            double duration = getVideoDuration(videoPath);

            // 构建FFmpeg命令
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegManager.getFFmpegPath(),
                    "-i", videoPath,
                    "-vn",                    // 不处理视频
                    "-acodec", "libmp3lame",  // 使用MP3编码
                    "-ab", "192k",            // 比特率
                    "-ar", "44100",           // 采样率
                    "-y",                     // 覆盖输出文件
                    "-progress", "pipe:1",    // 输出进度到stdout
                    outputPath
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取进度
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("out_time_ms=")) {
                        try {
                            long timeMs = Long.parseLong(line.substring(12));
                            double progress = duration > 0 ? (timeMs / 1000.0 / duration) * 100 : 0;
                            progress = Math.min(progress, 100);
                            if (progressCallback != null) {
                                progressCallback.accept(progress);
                            }
                        } catch (NumberFormatException e) {
                            // 忽略解析错误
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                if (progressCallback != null) {
                    progressCallback.accept(100.0);
                }
                log.info("Audio extracted successfully: {}", outputPath);
                return true;
            } else {
                log.error("FFmpeg exited with code: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to extract audio from {}", videoPath, e);
            return false;
        }
    }

    /**
     * 从视频中提取封面（第一帧）
     *
     * @param videoPath  视频文件路径
     * @param outputPath 输出图片文件路径
     * @return 是否成功
     */
    public boolean extractCover(String videoPath, String outputPath) {
        try {
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                log.error("Video file not found: {}", videoPath);
                return false;
            }

            // 确保输出目录存在
            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 构建FFmpeg命令 - 提取第1秒的帧作为封面
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegManager.getFFmpegPath(),
                    "-i", videoPath,
                    "-ss", "00:00:01",        // 从第1秒开始
                    "-vframes", "1",          // 只提取1帧
                    "-q:v", "2",              // 高质量
                    "-y",                     // 覆盖输出文件
                    outputPath
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出（用于调试）
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("FFmpeg: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && outputFile.exists()) {
                log.info("Cover extracted successfully: {}", outputPath);
                return true;
            } else {
                log.error("FFmpeg exited with code: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to extract cover from {}", videoPath, e);
            return false;
        }
    }

    /**
     * 将媒体转换成 SenseVoice 所需的 16kHz 单声道 PCM WAV。
     */
    public boolean prepareTranscriptionAudio(Path mediaPath, Path outputPath) {
        try {
            File mediaFile = mediaPath.toFile();
            if (!mediaFile.isFile()) {
                return false;
            }
            Files.createDirectories(outputPath.getParent());
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegManager.getFFmpegPath(),
                    "-i", mediaPath.toString(),
                    "-vn", "-ac", "1", "-ar", "16000",
                    "-c:a", "pcm_s16le", "-y", outputPath.toString());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // 消费 FFmpeg 输出，避免进程管道阻塞。
                }
            }
            return process.waitFor() == 0 && Files.isRegularFile(outputPath);
        } catch (Exception e) {
            log.error("Failed to prepare transcription audio: {}", mediaPath, e);
            return false;
        }
    }

    /**
     * 获取视频时长（秒）
     */
    private double getVideoDuration(String videoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegManager.getFFprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    return Double.parseDouble(line.trim());
                }
            }

            process.waitFor();
        } catch (Exception e) {
            log.warn("Failed to get video duration: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 检查FFmpeg是否可用
     */
    public boolean isFFmpegAvailable() {
        return ffmpegManager.isAvailable();
    }

    /**
     * 获取FFmpeg版本信息
     */
    public String getFFmpegVersion() {
        return ffmpegManager.getVersion();
    }
}
