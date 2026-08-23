package com.kalman03.svt.desktop.enums;

/**
 * 媒体类型枚举
 */
public enum MediaType {
    /**
     * 视频
     */
    VIDEO("video"),

    /**
     * 音频
     */
    AUDIO("audio"),

    /**
     * 图片/封面
     */
    IMAGE("image");

    private final String value;

    MediaType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
