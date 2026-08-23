package com.kalman03.svt.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 视频/图集信息（从远程接口获取）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoInfo {

    /**
     * 原始链接
     */
    private String orignalUrl;

    /**
     * 封面图片地址
     */
    private String cover;

    /**
     * 视频标题
     */
    private String title;

    /**
     * 媒体URL列表（视频/图片下载地址）
     */
    private List<MediaUrl> mediaUrls;

    /**
     * 平台显示名称（如：Xiaohongshu, TikTok）
     */
    private String platformName;

    /**
     * 平台标识（如：DOUYIN, TIKTOK）
     */
    private String platform;

    /**
     * 是否为图集类型（无视频，只有图片）
     */
    public boolean isImageSet() {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return false;
        }
        return mediaUrls.stream().allMatch(m -> "image".equalsIgnoreCase(m.getType()));
    }

    /**
     * 是否有封面
     */
    public boolean hasCover() {
        return cover != null && !cover.isEmpty();
    }

    /**
     * 是否有媒体URL
     */
    public boolean hasMediaUrls() {
        return mediaUrls != null && !mediaUrls.isEmpty();
    }

    /**
     * 获取第一个视频下载地址
     */
    public String getFirstVideoUrl() {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return null;
        }
        return mediaUrls.stream()
                .filter(m -> "video".equalsIgnoreCase(m.getType()))
                .map(MediaUrl::getUrl)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有图片URL
     */
    public List<String> getImageUrls() {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }
        return mediaUrls.stream()
                .filter(m -> "image".equalsIgnoreCase(m.getType()))
                .map(MediaUrl::getUrl)
                .toList();
    }
}
