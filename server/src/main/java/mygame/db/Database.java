package mygame.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * H2 파일 기반 DB 연결 풀.
 *
 * <p>애플리케이션 생애에 걸쳐 1개 인스턴스를 공유한다. 트랜잭션 경계는
 * 호출자가 {@code try-with-resources} 로 관리하도록 단순한 API 만 제공.
 *
 * <p>학습 메모: Spring 의 {@code DataSource} 자동 설정 없이도 풀을 직접
 * 꾸려 본다. 구체 드라이버(H2)와 {@link javax.sql.DataSource} 인터페이스의
 * 경계를 직접 체감할 수 있다.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final JdbcConnectionPool pool;

    public Database(String url, String user, String password) {
        this.pool = JdbcConnectionPool.create(url, user, password);
        this.pool.setMaxConnections(10);
        log.info("H2 연결 풀 생성: {}", url);
    }

    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    /** DDL 실행. 스키마는 최초 1회 생성되며, 이후 IF NOT EXISTS 로 안전. */
    public void runMigrations() {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL UNIQUE,
                    level INT NOT NULL DEFAULT 1,
                    exp INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_items (
                    player_id BIGINT NOT NULL,
                    item_id VARCHAR(64) NOT NULL,
                    qty INT NOT NULL,
                    PRIMARY KEY (player_id, item_id),
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
            """);
            // Phase I: 슬롯별 장착 아이템. 한 슬롯에 최대 1점. unequip 시 행 삭제.
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_equipment (
                    player_id BIGINT NOT NULL,
                    slot VARCHAR(16) NOT NULL,
                    item_id VARCHAR(64) NOT NULL,
                    PRIMARY KEY (player_id, slot),
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
            """);
            // Phase L: 계정 테이블. username UNIQUE 로 로그인 키 보호.
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(32) NOT NULL UNIQUE,
                    password_hash VARCHAR(256) NOT NULL,
                    salt VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            // 기존 players 테이블에 account_id 컬럼을 추가(재실행 안전).
            // H2 는 IF NOT EXISTS on ADD COLUMN 을 지원.
            st.execute("""
                ALTER TABLE players ADD COLUMN IF NOT EXISTS account_id BIGINT
            """);
            log.info("DB 마이그레이션 완료.");
        } catch (SQLException e) {
            throw new RuntimeException("DB 마이그레이션 실패", e);
        }
    }

    @Override
    public void close() {
        pool.dispose();
    }
}
