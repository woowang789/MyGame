package mygame.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Migrations} 의 멱등성·버전 추적·중복 적용 방지를 검증.
 *
 * <p>인메모리 H2 를 사용해 매 테스트가 격리된 DB 위에서 실행되도록 한다.
 */
class MigrationsTest {

    private Database db;

    @BeforeEach
    void setUp() {
        // 매 테스트마다 새 인메모리 DB. UUID 로 이름 분리해 병렬 충돌 방지.
        String url = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        db = new Database(url, "sa", "");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("최초 실행: 모든 단계가 적용되고 schema_version 이 최신 버전을 기록")
    void firstRun_appliesAllSteps() throws Exception {
        Migrations.apply(db);

        int version = Migrations.currentVersion(db);
        assertTrue(version >= 7, "현재 STEPS 마지막 버전이 적용되어야 함 — got " + version);

        // 기대 컬럼이 실제로 존재하는지 확인
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT hp, mp, meso, account_id FROM players LIMIT 0")) {
            // SELECT 가 예외 없이 실행되면 컬럼이 모두 존재.
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.getMetaData().getColumnCount() == 4);
            }
        }
    }

    @Test
    @DisplayName("백오피스 테이블(v6/v7) 도 같이 적용된다 — admin_accounts, audit_log")
    void adminTables_created() throws Exception {
        Migrations.apply(db);
        try (Connection conn = db.getConnection();
             var ps1 = conn.prepareStatement(
                     "SELECT id, username, password_hash, salt, role FROM admin_accounts LIMIT 0");
             var ps2 = conn.prepareStatement(
                     "SELECT id, admin_id, admin_username, action, payload FROM audit_log LIMIT 0")) {
            // 컬럼 모두 존재해야 SELECT 가 성공.
            assertTrue(ps1.executeQuery().getMetaData().getColumnCount() == 5);
            assertTrue(ps2.executeQuery().getMetaData().getColumnCount() == 5);
        }
    }

    @Test
    @DisplayName("재실행: 이미 적용된 단계는 스킵되고 schema_version 이 중복 기록되지 않음")
    void rerun_isIdempotent() throws Exception {
        Migrations.apply(db);
        int firstVersion = Migrations.currentVersion(db);

        Migrations.apply(db); // 두 번째 호출
        int secondVersion = Migrations.currentVersion(db);

        assertEquals(firstVersion, secondVersion);

        // schema_version 테이블 행 수도 STEPS 길이와 일치해야 한다(중복 INSERT 없음)
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM schema_version");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            int rows = rs.getInt(1);
            assertEquals(firstVersion, rows, "버전 행 수와 최신 버전 번호 일치");
        }
    }

    @Test
    @DisplayName("v0(빈 상태) 에서 시작하면 currentVersion 은 0")
    void emptyDatabase_versionZero() {
        // ensureVersionTable 만 호출되도록 currentVersion 사용 — 빈 DB → 0
        assertEquals(0, Migrations.currentVersion(db));
    }
}
