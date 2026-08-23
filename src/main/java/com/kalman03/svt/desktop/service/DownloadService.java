package com.kalman03.svt.desktop.service;

import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.Set;

import com.kalman03.svt.desktop.model.DownloadRequest;
import com.kalman03.svt.desktop.model.DownloadTask;

public interface DownloadService {

    /**
     * 提交下载请求
     *
     * @param request 下载请求参数
     */
    void submitDownload(DownloadRequest request);

    /**
     * Submit a download request asynchronously and complete when parsing/acceptance finishes.
     */
    default CompletableFuture<SubmitResult> submitDownloadAsync(DownloadRequest request) {
        submitDownload(request);
        return CompletableFuture.completedFuture(new SubmitResult(0, null));
    }

    record SubmitResult(int createdTasks, String errorMessage) {}

    /**
     * 取消下载任务
     *
     * @param taskId 任务ID
     */
    void cancelDownload(Long taskId);

    /**
     * 取消所有下载任务
     */
    void cancelAllDownloads();

    /**
     * 重新启用所有可恢复任务
     */
    void resumeAllDownloads();

    /**
     * 清空队列任务
     */
    ClearQueueResult clearQueue();

    record ClearQueueResult(Set<Long> clearedTaskIds, int clearedCount) {}

    /**
     * 移除指定任务
     *
     * @param taskId 任务ID
     */
    void removeTask(Long taskId);

    /**
     * 重试下载任务
     *
     * @param taskId 任务ID
     */
    void retryDownload(Long taskId);

    /**
     * 获取当前下载队列中的任务
     *
     * @return 下载任务列表
     */
    List<DownloadTask> getQueueTasks();

    /** 获取完整队列数量，不受界面筛选影响。 */
    int getQueueTaskCount();

    /**
     * 注册任务进度监听器
     *
     * @param listener 进度监听器
     */
    void addProgressListener(Consumer<DownloadTask> listener);

    /**
     * 移除任务进度监听器
     *
     * @param listener 进度监听器
     */
    void removeProgressListener(Consumer<DownloadTask> listener);
}
