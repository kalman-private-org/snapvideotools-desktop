package com.kalman03.svt.desktop.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kalman03.svt.desktop.entity.DownloadRecord;
import com.kalman03.svt.desktop.repository.DownloadRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadRecordService {

    private final DownloadRecordRepository repository;

    @Transactional
    public DownloadRecord createRecord(String url, String localPath, String title, String platform) {
        DownloadRecord record = DownloadRecord.builder()
                .url(url)
                .localPath(localPath)
                .title(title)
                .platform(platform)
                .status("PENDING")
                .build();
        return repository.save(record);
    }

    @Transactional
    public void updateStatus(Long id, String status) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(status);
            if ("COMPLETED".equals(status)) {
                record.setCompletedAt(LocalDateTime.now());
                File file = new File(record.getLocalPath());
                if (file.exists()) {
                    record.setFileSize(file.length());
                }
            }
            repository.save(record);
        });
    }

    @Transactional
    public void updateDownloadInfo(Long id, String title, String thumbnailUrl, Long fileSize) {
        repository.findById(id).ifPresent(record -> {
            if (title != null) {
                record.setTitle(title);
            }
            if (thumbnailUrl != null) {
                record.setThumbnailUrl(thumbnailUrl);
            }
            if (fileSize != null) {
                record.setFileSize(fileSize);
            }
            repository.save(record);
        });
    }

    @Transactional(readOnly = true)
    public List<DownloadRecord> getAllRecords() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<DownloadRecord> getRecordsByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public Optional<DownloadRecord> getRecordById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<DownloadRecord> getRecordByUrl(String url) {
        return repository.findByUrl(url);
    }

    @Transactional(readOnly = true)
    public boolean isUrlDownloaded(String url) {
        return repository.existsByUrl(url);
    }

    @Transactional
    public void deleteRecord(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public boolean deleteRecordWithFile(Long id) {
        Optional<DownloadRecord> recordOpt = repository.findById(id);
        if (recordOpt.isPresent()) {
            DownloadRecord record = recordOpt.get();
            boolean fileDeleted = deleteLocalArtifacts(record);
            repository.deleteById(id);
            return fileDeleted;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<DownloadRecord> getCompletedRecords() {
        return repository.findByStatusOrderByCreatedAtDesc("COMPLETED");
    }

    @Transactional(readOnly = true)
    public List<DownloadRecord> getDownloadingRecords() {
        return repository.findByStatus("DOWNLOADING");
    }

    /**
     * Clear all completed download records (history).
     *
     * @param deleteLocalFiles whether to delete local files as well
     * @return number of cleared records
     */
    @Transactional
    public int deleteCompletedRecords(boolean deleteLocalFiles) {
        List<DownloadRecord> records = repository.findByStatus("COMPLETED");
        int deleted = 0;
        for (DownloadRecord record : records) {
            if (record == null || record.getId() == null) {
                continue;
            }
            if (deleteLocalFiles) {
                // 单个文件删除失败不影响其余附件和历史记录的清理。
                deleteLocalArtifacts(record);
            }
            repository.deleteById(record.getId());
            deleted++;
        }
        return deleted;
    }

    /**
     * 删除记录关联的媒体、TXT 和 SRT；旧记录缺少转写路径时按媒体同名规则补全。
     */
    private boolean deleteLocalArtifacts(DownloadRecord record) {
        Set<Path> artifacts = new LinkedHashSet<>();
        addPath(artifacts, record.getLocalPath());
        addPath(artifacts, record.getTranscriptTxtPath());
        addPath(artifacts, record.getTranscriptSrtPath());

        if (Boolean.TRUE.equals(record.getExtractText()) && record.getLocalPath() != null
                && !record.getLocalPath().isBlank()) {
            Path mediaPath = Path.of(record.getLocalPath());
            String fileName = mediaPath.getFileName().toString();
            int extensionIndex = fileName.lastIndexOf('.');
            String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
            addPath(artifacts, mediaPath.resolveSibling(baseName + ".txt").toString());
            addPath(artifacts, mediaPath.resolveSibling(baseName + ".srt").toString());
        }

        boolean allDeleted = true;
        for (Path artifact : artifacts) {
            try {
                Files.deleteIfExists(artifact);
            } catch (IOException | SecurityException exception) {
                allDeleted = false;
                log.warn("Failed to delete local download artifact: {}", artifact, exception);
            }
        }
        return allDeleted;
    }

    private void addPath(Set<Path> artifacts, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            artifacts.add(Path.of(path).toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            log.warn("Ignored invalid local artifact path: {}", path);
        }
    }
}
