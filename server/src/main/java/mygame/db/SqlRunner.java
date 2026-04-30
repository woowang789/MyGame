package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 보일러플레이트(connection 획득, try-with-resources, SQLException 변환)를
 * 한 곳에 가둔 얇은 헬퍼.
 *
 * <p>학습 메모: Spring 의 {@code JdbcTemplate} 이 *어떤 일을* 해 주는지
 * 직접 만들어 보면서 체감하는 단계다. 함수형 인터페이스 두 개
 * ({@link SqlBinder}, {@link RowMapper}) + 동일한 try-with-resources 패턴이
 * 전부다. 추가 의존성 없이 Repository 의 메서드 평균 12줄을 4줄로 줄인다.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>도메인 매핑(record 생성, 자식 컬렉션 적재 등)은 호출자 책임. SqlRunner 는
 *       오직 JDBC API 의 반복만 담당한다.</li>
 *   <li>각 메서드는 자체 connection 을 풀에서 받아 닫는다. 트랜잭션이 필요한
 *       경우 {@link #inTransaction(TxBlock)} 에서 같은 connection 을 공유하는
 *       {@link TxOps} 에 작업을 위임한다.</li>
 *   <li>{@link SQLException} 은 {@link RuntimeException} 으로 래핑한다 — 학습
 *       프로젝트에서 검사 예외를 호출 사슬마다 끌고 다니는 건 학습 효과가 낮다.</li>
 * </ul>
 */
public final class SqlRunner {

    private final Database db;

    public SqlRunner(Database db) {
        this.db = db;
    }

    /** 단일 행 조회. 결과가 없으면 {@link Optional#empty()}. */
    public <T> Optional<T> queryOne(String sql, SqlBinder binder, RowMapper<T> mapper) {
        try (Connection conn = db.getConnection()) {
            return TxOps.queryOne(conn, sql, binder, mapper);
        } catch (SQLException e) {
            throw wrap(sql, e);
        }
    }

    /** 다중 행 조회. */
    public <T> List<T> queryList(String sql, SqlBinder binder, RowMapper<T> mapper) {
        try (Connection conn = db.getConnection()) {
            return TxOps.queryList(conn, sql, binder, mapper);
        } catch (SQLException e) {
            throw wrap(sql, e);
        }
    }

    /** {@code COUNT(*)} 등 단일 long 값 조회. */
    public long queryLong(String sql, SqlBinder binder) {
        try (Connection conn = db.getConnection()) {
            return TxOps.queryLong(conn, sql, binder);
        } catch (SQLException e) {
            throw wrap(sql, e);
        }
    }

    /** INSERT/UPDATE/DELETE/MERGE 실행. 영향 받은 행 수를 반환. */
    public int update(String sql, SqlBinder binder) {
        try (Connection conn = db.getConnection()) {
            return TxOps.update(conn, sql, binder);
        } catch (SQLException e) {
            throw wrap(sql, e);
        }
    }

    /** 자동 생성 키를 반환하는 INSERT. */
    public long insertReturningKey(String sql, SqlBinder binder) {
        try (Connection conn = db.getConnection()) {
            return TxOps.insertReturningKey(conn, sql, binder);
        } catch (SQLException e) {
            throw wrap(sql, e);
        }
    }

    /**
     * 여러 SQL 을 한 트랜잭션으로 묶어 실행한다. 블록 내에서는 {@link TxOps} 를
     * 사용해 같은 connection 을 공유한다. 예외 시 자동 롤백.
     */
    public <T> T inTransaction(TxBlock<T> block) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = block.execute(new TxOps(conn));
                conn.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : wrap("transaction", (SQLException) e);
            }
        } catch (SQLException e) {
            throw wrap("transaction", e);
        }
    }

    private static RuntimeException wrap(String label, SQLException e) {
        return new RuntimeException("SQL 실패: " + label, e);
    }

    /**
     * 트랜잭션 블록. {@link #inTransaction(TxBlock)} 에 전달되며 같은
     * connection 을 공유하는 {@link TxOps} 를 통해 여러 SQL 을 호출한다.
     */
    @FunctionalInterface
    public interface TxBlock<T> {
        T execute(TxOps tx) throws SQLException;
    }

    /**
     * 트랜잭션 내부에서 사용할 connection-bound 작업 모음. SqlRunner 의 동일한
     * 메서드 시그니처를 갖되 connection 을 닫지 않는다.
     */
    public static final class TxOps {

        private final Connection conn;

        private TxOps(Connection conn) {
            this.conn = conn;
        }

        public <T> Optional<T> queryOne(String sql, SqlBinder binder, RowMapper<T> mapper)
                throws SQLException {
            return queryOne(conn, sql, binder, mapper);
        }

        public <T> List<T> queryList(String sql, SqlBinder binder, RowMapper<T> mapper)
                throws SQLException {
            return queryList(conn, sql, binder, mapper);
        }

        public int update(String sql, SqlBinder binder) throws SQLException {
            return update(conn, sql, binder);
        }

        public long insertReturningKey(String sql, SqlBinder binder) throws SQLException {
            return insertReturningKey(conn, sql, binder);
        }

        // --- 정적 구현부 (SqlRunner 와 TxOps 가 공유) ---

        static <T> Optional<T> queryOne(Connection conn, String sql, SqlBinder binder,
                                        RowMapper<T> mapper) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
                }
            }
        }

        static <T> List<T> queryList(Connection conn, String sql, SqlBinder binder,
                                     RowMapper<T> mapper) throws SQLException {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapper.map(rs));
                }
            }
            return out;
        }

        static long queryLong(Connection conn, String sql, SqlBinder binder) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }

        static int update(Connection conn, String sql, SqlBinder binder) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                return ps.executeUpdate();
            }
        }

        static long insertReturningKey(Connection conn, String sql, SqlBinder binder)
                throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                binder.bind(ps);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("INSERT 후 id 조회 실패");
                    return keys.getLong(1);
                }
            }
        }
    }
}
