package com.kalman03.svt.desktop.model;

import com.kalman03.svt.desktop.enums.TabType;

import lombok.Builder;
import lombok.Data;

/**
 * 下载请求参数
 */
@Data
@Builder
public class DownloadRequest {

    /**
     * 输入内容（链接或用户主页URL）
     */
    private String content;

    /**
     * Tab类型
     */
    private TabType tabType;

    /**
     * 是否提取封面
     */
    private boolean extractCover;

    /**
     * 是否提取音频
     */
    private boolean extractAudio;

    /**
     * 是否提取文本
     */
    private boolean extractText;
}
