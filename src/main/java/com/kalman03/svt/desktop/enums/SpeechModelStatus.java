package com.kalman03.svt.desktop.enums;

/**
 * 本地语音模型的生命周期状态。
 */
public enum SpeechModelStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    LOADING,
    READY,
    ERROR
}
