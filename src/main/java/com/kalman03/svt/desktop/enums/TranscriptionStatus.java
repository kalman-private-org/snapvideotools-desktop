package com.kalman03.svt.desktop.enums;

/**
 * 视频内部的语音转文字阶段，不作为独立媒体任务展示。
 */
public enum TranscriptionStatus {
    NONE,
    WAITING_MODEL,
    PREPARING_AUDIO,
    TRANSCRIBING,
    COMPLETED,
    FAILED
}
