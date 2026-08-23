package com.kalman03.svt.desktop.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.kalman03.svt.desktop.entity.DownloadRecord;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class DownloadRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<DownloadRecord> rowMapper = new RowMapper<>() {
        @Override
        public DownloadRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return DownloadRecord.builder()
                    .id(rs.getLong("id"))
                    .url(rs.getString("url"))
                    .title(rs.getString("title"))
                    .localPath(rs.getString("local_path"))
                    .fileSize(rs.getObject("file_size", Long.class))
                    .status(rs.getString("status"))
                    .platform(rs.getString("platform"))
                    .thumbnailUrl(rs.getString("thumbnail_url"))
                    .mediaType(rs.getString("media_type"))
                    .batchId(rs.getString("batch_id"))
                    .parentId(rs.getObject("parent_id", Long.class))
                    .progress(rs.getObject("progress", Double.class))
                    .errorMessage(rs.getString("error_message"))
                    .extractText(rs.getObject("extract_text", Boolean.class))
                    .mediaCompletedAt(toLocalDateTime(rs.getTimestamp("media_completed_at")))
                    .transcriptionStatus(rs.getString("transcription_status"))
                    .transcriptionProgress(rs.getObject("transcription_progress", Double.class))
                    .transcriptionError(rs.getString("transcription_error"))
                    .transcriptTxtPath(rs.getString("transcript_txt_path"))
                    .transcriptSrtPath(rs.getString("transcript_srt_path"))
                    .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                    .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                    .completedAt(toLocalDateTime(rs.getTimestamp("completed_at")))
                    .build();
        }

        private LocalDateTime toLocalDateTime(Timestamp timestamp) {
            return timestamp != null ? timestamp.toLocalDateTime() : null;
        }
    };

    public DownloadRecord save(DownloadRecord record) {
        if (record.getId() == null) {
            return insert(record);
        } else {
            return update(record);
        }
    }

    private DownloadRecord insert(DownloadRecord record) {
        Long id = jdbcTemplate.queryForObject("SELECT nextval('download_records_id_seq')", Long.class);
        record.setId(id);

        LocalDateTime now = LocalDateTime.now();
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        if (record.getProgress() == null) {
            record.setProgress(0.0);
        }

        String sql = """
            INSERT INTO download_records (id, url, title, local_path, file_size, status, platform,
                thumbnail_url, media_type, batch_id, parent_id, progress, error_message,
                extract_text, media_completed_at, transcription_status, transcription_progress,
                transcription_error, transcript_txt_path, transcript_srt_path,
                created_at, updated_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                record.getId(),
                record.getUrl(),
                record.getTitle(),
                record.getLocalPath(),
                record.getFileSize(),
                record.getStatus(),
                record.getPlatform(),
                record.getThumbnailUrl(),
                record.getMediaType(),
                record.getBatchId(),
                record.getParentId(),
                record.getProgress(),
                record.getErrorMessage(),
                Boolean.TRUE.equals(record.getExtractText()),
                toTimestamp(record.getMediaCompletedAt()),
                record.getTranscriptionStatus() == null ? "NONE" : record.getTranscriptionStatus(),
                record.getTranscriptionProgress() == null ? 0.0 : record.getTranscriptionProgress(),
                record.getTranscriptionError(),
                record.getTranscriptTxtPath(),
                record.getTranscriptSrtPath(),
                Timestamp.valueOf(record.getCreatedAt()),
                record.getUpdatedAt() != null ? Timestamp.valueOf(record.getUpdatedAt()) : null,
                record.getCompletedAt() != null ? Timestamp.valueOf(record.getCompletedAt()) : null
        );

        return record;
    }

    private DownloadRecord update(DownloadRecord record) {
        record.setUpdatedAt(LocalDateTime.now());

        String sql = """
            UPDATE download_records SET
                url = ?, title = ?, local_path = ?, file_size = ?, status = ?, platform = ?,
                thumbnail_url = ?, media_type = ?, batch_id = ?, parent_id = ?, progress = ?,
                error_message = ?, extract_text = ?, media_completed_at = ?, transcription_status = ?,
                transcription_progress = ?, transcription_error = ?, transcript_txt_path = ?,
                transcript_srt_path = ?, updated_at = ?, completed_at = ?
            WHERE id = ?
            """;

        jdbcTemplate.update(sql,
                record.getUrl(),
                record.getTitle(),
                record.getLocalPath(),
                record.getFileSize(),
                record.getStatus(),
                record.getPlatform(),
                record.getThumbnailUrl(),
                record.getMediaType(),
                record.getBatchId(),
                record.getParentId(),
                record.getProgress(),
                record.getErrorMessage(),
                Boolean.TRUE.equals(record.getExtractText()),
                toTimestamp(record.getMediaCompletedAt()),
                record.getTranscriptionStatus() == null ? "NONE" : record.getTranscriptionStatus(),
                record.getTranscriptionProgress() == null ? 0.0 : record.getTranscriptionProgress(),
                record.getTranscriptionError(),
                record.getTranscriptTxtPath(),
                record.getTranscriptSrtPath(),
                Timestamp.valueOf(record.getUpdatedAt()),
                record.getCompletedAt() != null ? Timestamp.valueOf(record.getCompletedAt()) : null,
                record.getId()
        );

        return record;
    }

    private Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public Optional<DownloadRecord> findById(Long id) {
        String sql = "SELECT * FROM download_records WHERE id = ?";
        List<DownloadRecord> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<DownloadRecord> findByUrl(String url) {
        String sql = "SELECT * FROM download_records WHERE url = ?";
        List<DownloadRecord> results = jdbcTemplate.query(sql, rowMapper, url);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<DownloadRecord> findByLocalPath(String localPath) {
        String sql = "SELECT * FROM download_records WHERE local_path = ?";
        List<DownloadRecord> results = jdbcTemplate.query(sql, rowMapper, localPath);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<DownloadRecord> findByStatus(String status) {
        String sql = "SELECT * FROM download_records WHERE status = ?";
        return jdbcTemplate.query(sql, rowMapper, status);
    }

    public List<DownloadRecord> findByPlatform(String platform) {
        String sql = "SELECT * FROM download_records WHERE platform = ?";
        return jdbcTemplate.query(sql, rowMapper, platform);
    }

    public List<DownloadRecord> findByCreatedAtAfter(LocalDateTime dateTime) {
        String sql = "SELECT * FROM download_records WHERE created_at > ?";
        return jdbcTemplate.query(sql, rowMapper, Timestamp.valueOf(dateTime));
    }

    public List<DownloadRecord> findAllByOrderByCreatedAtDesc() {
        String sql = "SELECT * FROM download_records ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    public List<DownloadRecord> findByStatusOrderByCreatedAtDesc(String status) {
        String sql = "SELECT * FROM download_records WHERE status = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper, status);
    }

    public boolean existsByUrl(String url) {
        String sql = "SELECT COUNT(*) FROM download_records WHERE url = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, url);
        return count != null && count > 0;
    }

    public boolean existsByLocalPath(String localPath) {
        String sql = "SELECT COUNT(*) FROM download_records WHERE local_path = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, localPath);
        return count != null && count > 0;
    }

    public List<DownloadRecord> findByBatchIdOrderByCreatedAtAsc(String batchId) {
        String sql = "SELECT * FROM download_records WHERE batch_id = ? ORDER BY created_at ASC";
        return jdbcTemplate.query(sql, rowMapper, batchId);
    }

    public List<DownloadRecord> findByMediaTypeOrderByCreatedAtDesc(String mediaType) {
        String sql = "SELECT * FROM download_records WHERE media_type = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper, mediaType);
    }

    public List<DownloadRecord> findByParentId(Long parentId) {
        String sql = "SELECT * FROM download_records WHERE parent_id = ?";
        return jdbcTemplate.query(sql, rowMapper, parentId);
    }

    public List<DownloadRecord> findByStatusIn(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = "SELECT * FROM download_records WHERE status IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, rowMapper, statuses.toArray());
    }

    public long countByStatusIn(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM download_records WHERE status IN (" + placeholders + ")",
                Long.class, statuses.toArray());
        return count == null ? 0 : count;
    }

    public List<DownloadRecord> findByStatusAndMediaTypeOrderByCreatedAtDesc(String status, String mediaType) {
        String sql = "SELECT * FROM download_records WHERE status = ? AND media_type = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper, status, mediaType);
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM download_records WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    public List<DownloadRecord> findAll() {
        String sql = "SELECT * FROM download_records";
        return jdbcTemplate.query(sql, rowMapper);
    }

    public long count() {
        String sql = "SELECT COUNT(*) FROM download_records";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0;
    }
}
