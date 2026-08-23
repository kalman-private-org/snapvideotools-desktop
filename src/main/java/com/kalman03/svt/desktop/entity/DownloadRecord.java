package com.kalman03.svt.desktop.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRecord {

    private Long id;

    /**
     * 原始下载URL
     */
    private String url;

    /**
     * 视频标题
     */
    private String title;

    /**
     * 本地保存路径
     */
    private String localPath;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 下载状态: PENDING, DOWNLOADING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 来源平台（如 youtube, tiktok 等）
     */
    private String platform;

    /**
     * 缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 媒体类型: VIDEO, AUDIO, IMAGE
     */
    private String mediaType;

    /**
     * 批次ID（同一次下载操作的任务共享同一个批次ID）
     */
    private String batchId;

    /**
     * 父记录ID（如果是从视频提取的音频/封面，则关联到视频记录）
     */
    private Long parentId;

    /**
     * 下载进度（0-100）
     */
    private Double progress;

    /**
     * 错误信息
     */
    private String errorMessage;

    /** 是否要求从视频中提取文字。 */
    private Boolean extractText;

    /** 媒体文件已经下载完成的时间，转写失败时仍保留该事实。 */
    private LocalDateTime mediaCompletedAt;

    /** 视频内部的语音转文字阶段。 */
    private String transcriptionStatus;

    /** 语音转文字进度（0-100）。 */
    private Double transcriptionProgress;

    /** 语音转文字错误，不与媒体下载错误混用。 */
    private String transcriptionError;

    private String transcriptTxtPath;

    private String transcriptSrtPath;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 下载完成时间
     */
    private LocalDateTime completedAt;
}
