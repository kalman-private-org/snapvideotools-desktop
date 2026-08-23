package com.kalman03.svt.desktop.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;

@Slf4j
@Configuration
public class DatabaseConfig {

    private static final String DB_DIR_NAME = ".snapvideotools";
    private static final String DB_FILE_NAME = "data.duckdb";

    @Bean
    public DataSource dataSource() {
        String userHome = System.getProperty("user.home");
        File dbDir = new File(userHome, DB_DIR_NAME);
        if (!dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (created) {
                log.info("Created database directory: {}", dbDir.getAbsolutePath());
            } else {
                log.warn("Failed to create database directory: {}", dbDir.getAbsolutePath());
            }
        }

        String dbPath = new File(dbDir, DB_FILE_NAME).getAbsolutePath();
        String jdbcUrl = "jdbc:duckdb:" + dbPath;

        log.info("Database path: {}", dbPath);

        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl)
                .driverClassName("org.duckdb.DuckDBDriver")
                .build();

        // DuckDB 支持并发读写，可以使用更大的连接池
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(30000);

        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        initializeDatabase(jdbcTemplate);
        return jdbcTemplate;
    }

    private void initializeDatabase(JdbcTemplate jdbcTemplate) {
        log.info("Initializing database tables...");

        String createTableSql = """
            CREATE TABLE IF NOT EXISTS download_records (
                id BIGINT PRIMARY KEY,
                url VARCHAR(2048) NOT NULL,
                title VARCHAR(500),
                local_path VARCHAR(1024) NOT NULL,
                file_size BIGINT,
                status VARCHAR(20) NOT NULL,
                platform VARCHAR(50),
                thumbnail_url VARCHAR(2048),
                media_type VARCHAR(20),
                batch_id VARCHAR(50),
                parent_id BIGINT,
                progress DOUBLE,
                error_message VARCHAR(1024),
                extract_text BOOLEAN DEFAULT FALSE,
                media_completed_at TIMESTAMP,
                transcription_status VARCHAR(30) DEFAULT 'NONE',
                transcription_progress DOUBLE DEFAULT 0,
                transcription_error VARCHAR(1024),
                transcript_txt_path VARCHAR(1024),
                transcript_srt_path VARCHAR(1024),
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP,
                completed_at TIMESTAMP
            )
            """;

        jdbcTemplate.execute(createTableSql);

        // DuckDB 没有单独的迁移框架，这些语句可重复执行并兼容已有用户数据库。
        addColumnIfMissing(jdbcTemplate, "extract_text", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(jdbcTemplate, "media_completed_at", "TIMESTAMP");
        addColumnIfMissing(jdbcTemplate, "transcription_status", "VARCHAR(30) DEFAULT 'NONE'");
        addColumnIfMissing(jdbcTemplate, "transcription_progress", "DOUBLE DEFAULT 0");
        addColumnIfMissing(jdbcTemplate, "transcription_error", "VARCHAR(1024)");
        addColumnIfMissing(jdbcTemplate, "transcript_txt_path", "VARCHAR(1024)");
        addColumnIfMissing(jdbcTemplate, "transcript_srt_path", "VARCHAR(1024)");

        // 创建序列用于自增ID
        try {
            jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS download_records_id_seq START 1");
        } catch (Exception e) {
            log.debug("Sequence may already exist: {}", e.getMessage());
        }

        log.info("Database tables initialized successfully");
    }

    private void addColumnIfMissing(JdbcTemplate jdbcTemplate, String columnName, String definition) {
        jdbcTemplate.execute("ALTER TABLE download_records ADD COLUMN IF NOT EXISTS "
                + columnName + " " + definition);
    }
}
