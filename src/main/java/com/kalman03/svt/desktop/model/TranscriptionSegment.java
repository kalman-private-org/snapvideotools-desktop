package com.kalman03.svt.desktop.model;

/**
 * 一段带时间范围的识别文本。
 */
public record TranscriptionSegment(double startSeconds, double endSeconds, String text) {
}
