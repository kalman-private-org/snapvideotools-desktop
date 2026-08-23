package com.kalman03.svt.desktop.model;

import com.kalman03.svt.desktop.enums.SpeechModelStatus;
import com.kalman03.svt.desktop.enums.SpeechModelFailureReason;

/**
 * 设置窗口使用的不可变模型状态快照。
 */
public record SpeechModelSnapshot(
        SpeechModelStatus status,
        double progress,
        long downloadedBytes,
        long totalBytes,
        SpeechModelFailureReason failureReason,
        String errorMessage
) {
    public boolean isReady() {
        return status == SpeechModelStatus.READY;
    }
}
