package com.kalman03.svt.desktop.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.Future;

import com.kalman03.svt.desktop.enums.DownloadStatus;
import com.kalman03.svt.desktop.enums.MediaType;
import com.kalman03.svt.desktop.enums.TranscriptionStatus;

/**
 * 下载任务（内存中的任务对象）
 */
@Data
@Builder
public class DownloadTask {

    /**
     * 任务ID（对应数据库记录ID）
     */
    private Long id;

    /**
     * 批次ID（同一次下载操作的任务共享同一个批次ID）
     */
    private String batchId;

    /**
     * 视频信息
     */
    private VideoInfo videoInfo;

    /**
     * 媒体类型
     */
    private MediaType mediaType;

    /**
     * 下载状态
     */
    private DownloadStatus status;

    /**
     * 下载进度（0-100）
     */
    private double progress;

    /**
     * 本地保存路径
     */
    private String localPath;

    /**
     * 缩略图URL（多图时保存当前图片URL）
     */
    private String thumbnailUrl;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 关联的Future对象（用于取消任务）
     */
    private transient Future<?> future;

    /**
     * 是否需要提取封面
     */
    private boolean extractCover;

    /**
     * 是否需要提取音频
     */
    private boolean extractAudio;

    /**
     * 是否需要提取文本
     */
    private boolean extractText;

    /** 语音转文字的独立阶段与进度。 */
    @Builder.Default
    private TranscriptionStatus transcriptionStatus = TranscriptionStatus.NONE;

    private double transcriptionProgress;

    private String transcriptionError;

    private String transcriptTxtPath;

    private String transcriptSrtPath;

    private LocalDateTime mediaCompletedAt;

    /**
     * 父任务ID（如果是从视频提取的音频/封面，则关联到视频任务）
     */
    private Long parentTaskId;
}
