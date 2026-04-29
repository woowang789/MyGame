package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 버전 관리형 DB 마이그레이션.
 *
 * <p>이전 구현은 모든 ALTER TABLE 을 한 메서드에 누적 호출하는 방식이었다.
 * 동작은 했지만 다음 한계가 있었다.
 * <ul>
 *   <li>"이 컬럼이 언제 추가됐는가" 가 코드에서 보이지 않음
 *   <li>중간 마이그레이션 실패 시 부분 적용 상태에서 다시 실행해도 원자성 보장이 어려움
 *   <li>롤백·테스트 환경 격리 불가
 * </ul>
 *
 * <p>본 클래스는 {@code schema_version} 테이블에 적용된 버전을 기록하고,
 * 각 버전을 단일 트랜잭션 안에서 실행한다. 이미 적용된 버전은 건너뛴다.
 * 신규 환경에서도, 기존 누적식 스키마가 이미 있는 환경에서도 동일하게 작동한다.
 */
public final class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    /**
     * 단일 마이그레이션 단계. version 은 단조 증가, description 은 로그용,
     * sql 은 한 단계 안에서 순차 실행할 DDL/DML 목록.
     */
    public record Step(int version, String description, List<String> sql) {}

    /**
     * 적용 순서대로의 모든 단계. 버전 추가는 항상 끝에 append. 기존 단계는 수정 금지
     * (배포된 환경에 이미 적용된 버전이 다시 실행되면 실패하기 때문).
     */
    private static final List<Step> STEPS = List.of(
            new Step(1, "초기 스키마(players, player_items)", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS players (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(64) NOT NULL UNIQUE,
                        level INT NOT NULL DEFAULT 1,
                        exp INT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS player_items (
                        player_id BIGINT NOT NULL,
                        item_id VARCHAR(64) NOT NULL,
                        qty INT NOT NULL,
                        PRIMARY KEY (player_id, item_id),
                        FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                    )
                    """
            )),
            new Step(2, "Phase I: 장비 슬롯 테이블", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS player_equipment (
                        player_id BIGINT NOT NULL,
                        slot VARCHAR(16) NOT NULL,
                        item_id VARCHAR(64) NOT NULL,
                        PRIMARY KEY (player_id, slot),
                        FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                    )
                    """
            )),
            new Step(3, "Phase L: 계정 테이블 + players.account_id", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS accounts (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(32) NOT NULL UNIQUE,
                        password_hash VARCHAR(256) NOT NULL,
                        salt VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    "ALTER TABLE players ADD COLUMN IF NOT EXISTS account_id BIGINT"
            )),
            new Step(4, "재화(메소) 컬럼", List.of(
                    "ALTER TABLE players ADD COLUMN IF NOT EXISTS meso BIGINT NOT NULL DEFAULT 0"
            )),
            new Step(5, "HP/MP 영속화 컬럼(-1 sentinel = 풀피 복원)", List.of(
                    "ALTER TABLE players ADD COLUMN IF NOT EXISTS hp INT NOT NULL DEFAULT -1",
                    "ALTER TABLE players ADD COLUMN IF NOT EXISTS mp INT NOT NULL DEFAULT -1"
            )),
            // 백오피스(Phase 1) — 관리자 계정과 감사 로그.
            // 게임 accounts 와 별도 테이블: 권한 결합 사고 차단 + 단일 admin role 만 우선 도입.
            new Step(6, "백오피스: admin_accounts 테이블", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS admin_accounts (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(256) NOT NULL,
                        salt VARCHAR(64) NOT NULL,
                        role VARCHAR(16) NOT NULL DEFAULT 'admin',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """
            )),
            new Step(7, "백오피스: audit_log 테이블 (admin 행위 기록)", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        admin_id BIGINT,
                        admin_username VARCHAR(64),
                        action VARCHAR(64) NOT NULL,
                        payload CLOB,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """
            )),
            // 백오피스 Phase 2: 계정 정지(ban) 컬럼. AuthService 가 로그인 시 검사.
            new Step(8, "accounts.disabled BOOLEAN (계정 정지)", List.of(
                    "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE"
            )),
            // 백오피스 Phase 4: 상점 카탈로그 DB 화. 기존 ShopRegistry 코드 상수가 SSoT 였던 것을
            // shops/shop_items 테이블로 옮기고, 운영 화면에서 가격·재고를 즉시 편집 가능.
            new Step(9, "shops + shop_items 테이블", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS shops (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL DEFAULT ''
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS shop_items (
                        shop_id VARCHAR(64) NOT NULL,
                        item_id VARCHAR(64) NOT NULL,
                        price BIGINT NOT NULL,
                        stock_per_tx INT NOT NULL DEFAULT 1,
                        sort_order INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (shop_id, item_id),
                        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """
            )),
            // v9 직후 1회 시드. 기존 ShopRegistry 의 코드 상수와 동일한 값 — 마이그레이션이
            // 신규 환경에 한 번만 적용되므로 운영 중 실수로 가격이 리셋되는 일은 없다.
            new Step(10, "henesys_general 상점 시드", List.of(
                    "INSERT INTO shops (id, name) VALUES ('henesys_general', '잡화상 페트라')",
                    "INSERT INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order)"
                            + " VALUES ('henesys_general', 'red_potion',     50, 50, 0)",
                    "INSERT INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order)"
                            + " VALUES ('henesys_general', 'blue_potion',    80, 50, 1)",
                    "INSERT INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order)"
                            + " VALUES ('henesys_general', 'wooden_sword', 1500,  1, 2)",
                    "INSERT INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order)"
                            + " VALUES ('henesys_general', 'leather_cap',   800,  1, 3)"
            )),
            // 백오피스 Phase 4-2: 아이템 템플릿 DB 화. type=EQUIPMENT 는 slot/bonus_*,
            // type=CONSUMABLE 은 use_* 가 의미 있고, 그 외엔 0/NULL. ItemTemplate 의 ctor 가
            // 일관성 검증을 하므로 DB 에는 평탄화된 컬럼만 유지(EAV 회피).
            new Step(11, "item_templates 테이블", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS item_templates (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        color INT NOT NULL DEFAULT 0,
                        type VARCHAR(16) NOT NULL,
                        slot VARCHAR(16),
                        bonus_max_hp INT NOT NULL DEFAULT 0,
                        bonus_max_mp INT NOT NULL DEFAULT 0,
                        bonus_attack INT NOT NULL DEFAULT 0,
                        bonus_speed INT NOT NULL DEFAULT 0,
                        use_heal INT NOT NULL DEFAULT 0,
                        use_mana_heal INT NOT NULL DEFAULT 0,
                        sell_price BIGINT NOT NULL DEFAULT 0
                    )
                    """
            )),
            // v11 직후 1회 시드. 기존 ItemRegistry 코드 상수와 동일한 값.
            // hex 색상 → INT 변환은 INSERT SQL 에 그대로 0xAABBCC 형태로 표기.
            new Step(12, "item_templates 시드 (기존 ItemRegistry 상수)", List.of(
                    // CONSUMABLE: 빨간 포션 (heal=30)
                    "INSERT INTO item_templates (id, name, color, type, use_heal, sell_price) "
                            + "VALUES ('red_potion', '빨간 포션', 0xe74c3c, 'CONSUMABLE', 30, 25)",
                    // CONSUMABLE: 파란 포션 (manaHeal=30)
                    "INSERT INTO item_templates (id, name, color, type, use_mana_heal, sell_price) "
                            + "VALUES ('blue_potion', '파란 포션', 0x3498db, 'CONSUMABLE', 30, 40)",
                    // ETC: 달팽이 껍질
                    "INSERT INTO item_templates (id, name, color, type, sell_price) "
                            + "VALUES ('snail_shell', '달팽이 껍질', 0xb36836, 'ETC', 5)",
                    // EQUIPMENT 시리즈: slot + bonus_*
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_attack, sell_price) "
                            + "VALUES ('wooden_sword', '나무 검', 0x8b5a2b, 'EQUIPMENT', 'WEAPON', 10, 750)",
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_attack, sell_price) "
                            + "VALUES ('iron_sword', '철 검', 0xbfc7d5, 'EQUIPMENT', 'WEAPON', 25, 1000)",
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_max_hp, bonus_max_mp, sell_price) "
                            + "VALUES ('leather_cap', '가죽 모자', 0x6a4e2a, 'EQUIPMENT', 'HAT', 15, 5, 400)",
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_max_hp, sell_price) "
                            + "VALUES ('cloth_armor', '천 갑옷', 0xcfa16a, 'EQUIPMENT', 'ARMOR', 25, 800)",
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_attack, sell_price) "
                            + "VALUES ('work_gloves', '작업 장갑', 0x7d5b3a, 'EQUIPMENT', 'GLOVES', 4, 200)",
                    "INSERT INTO item_templates (id, name, color, type, slot, bonus_speed, sell_price) "
                            + "VALUES ('running_shoes', '달리기 신발', 0x4caf50, 'EQUIPMENT', 'SHOES', 30, 200)"
            ))
    );

    private Migrations() {}

    /** 누락된 버전을 순서대로 적용. 이미 적용된 버전은 스킵. */
    public static void apply(Database db) {
        ensureVersionTable(db);
        List<Integer> applied = appliedVersions(db);
        int beforeMax = applied.stream().mapToInt(Integer::intValue).max().orElse(0);
        for (Step step : STEPS) {
            if (applied.contains(step.version())) continue;
            applyStep(db, step);
        }
        int afterMax = currentVersion(db);
        log.info("DB 마이그레이션 완료: {} → {}", beforeMax, afterMax);
    }

    /** 가장 최근 적용된 버전. 없으면 0. 운영용 진단/테스트 검증에 쓰인다. */
    public static int currentVersion(Database db) {
        ensureVersionTable(db);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("schema_version 조회 실패", e);
        }
    }

    private static void ensureVersionTable(Database db) {
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INT PRIMARY KEY,
                    description VARCHAR(256) NOT NULL,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        } catch (SQLException e) {
            throw new RuntimeException("schema_version 생성 실패", e);
        }
    }

    private static List<Integer> appliedVersions(Database db) {
        List<Integer> versions = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT version FROM schema_version ORDER BY version");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) versions.add(rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException("적용된 버전 조회 실패", e);
        }
        return versions;
    }

    /**
     * 한 단계는 트랜잭션으로 묶어 실행한다 — 중간 SQL 이 실패하면 전체 롤백되어
     * schema_version 에도 기록되지 않는다(다음 실행 때 다시 시도 가능).
     */
    private static void applyStep(Database db, Step step) {
        log.info("DB 마이그레이션 적용: v{} {}", step.version(), step.description());
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : step.sql()) {
                    st.execute(sql);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
                    ps.setInt(1, step.version());
                    ps.setString(2, step.description());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("v" + step.version() + " 마이그레이션 실패", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("v" + step.version() + " 마이그레이션 연결 실패", e);
        }
    }
}
