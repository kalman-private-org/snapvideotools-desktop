package com.kalman03.svt.desktop.enums;

/** 语音模型准备失败时可向用户解释的原因分类。 */
public enum SpeechModelFailureReason {
    NONE,
    NETWORK,
    TIMEOUT,
    SERVER,
    DISK_SPACE,
    PERMISSION,
    CHECKSUM,
    ARCHIVE,
    MODEL_LOAD,
    INTERRUPTED,
    UNKNOWN
}
