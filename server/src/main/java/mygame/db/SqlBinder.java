package mygame.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * {@link PreparedStatement} 에 파라미터를 바인딩하는 함수형 인터페이스.
 *
 * <p>학습 메모: Spring 의 {@code PreparedStatementSetter} 와 같은 역할.
 * 직접 만들어 보면 "프레임워크가 왜 이런 작은 인터페이스를 갖고 있는지" 가
 * 보인다. 핵심은 SQL 과 바인딩 로직을 분리해 SQL 주입을 차단하면서도
 * 호출부 코드를 짧게 유지하는 것.
 */
@FunctionalInterface
public interface SqlBinder {

    /** 파라미터가 없을 때 그대로 넘기는 no-op. */
    SqlBinder NONE = ps -> {};

    void bind(PreparedStatement ps) throws SQLException;
}
