package mygame.db;

import java.sql.Connection;
import java.sql.SQLException;
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

    /**
     * 스키마 마이그레이션 실행. 실제 단계 정의는 {@link Migrations} 가 갖고,
     * 본 메서드는 진입점일 뿐이다(런타임 호환성을 위해 시그니처는 유지).
     */
    public void runMigrations() {
        Migrations.apply(this);
    }

    @Override
    public void close() {
        pool.dispose();
    }
}
