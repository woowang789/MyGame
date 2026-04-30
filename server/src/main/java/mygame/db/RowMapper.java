package mygame.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link ResultSet} 의 현재 행을 도메인 객체로 변환하는 함수형 인터페이스.
 *
 * <p>학습 메모: Spring JDBC 의 {@code RowMapper} 와 같은 역할. 행 매핑 책임을
 * 한 곳에 모아서 Repository 메서드 본문에서 보일러를 제거한다.
 */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet rs) throws SQLException;
}
