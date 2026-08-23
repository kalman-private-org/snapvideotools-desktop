package com.kalman03.svt.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 媒体URL信息（视频/图片下载地址）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaUrl {

    /**
     * 下载地址
     */
    private String url;

    /**
     * 媒体类型：video, image
     */
    private String type;

    /**
     * 文件大小（字节）
     */
    private Long size;

    /**
     * 文件后缀：mp4, jpg, png 等
     */
    private String suffix;
}
