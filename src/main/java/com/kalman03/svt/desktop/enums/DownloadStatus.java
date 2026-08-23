package com.kalman03.svt.desktop.enums;

/**
 * 下载状态枚举
 */
public enum DownloadStatus {
    /**
     * 等待中
     */
    PENDING("PENDING"),

    /**
     * 下载中
     */
    DOWNLOADING("DOWNLOADING"),

    /**
     * 处理中（如提取音频、封面）
     */
    PROCESSING("PROCESSING"),

    /**
     * 已完成
     */
    COMPLETED("COMPLETED"),

    /**
     * 失败
     */
    FAILED("FAILED"),

    /**
     * 已取消
     */
    CANCELLED("CANCELLED");

    private final String value;

    DownloadStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
