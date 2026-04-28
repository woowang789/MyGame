package mygame.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세션 ID(int) 와 영속 dbId(long) 의 분리·invariant 검증.
 *
 * <p>Player 인스턴스가 존재한다면 {@code dbId > 0} 임을 생성자에서 강제한다.
 * 이를 통해 호출 측의 {@code dbId() > 0} 가드를 모두 제거할 수 있었다.
 */
class PlayerIdentityTest {

    @Test
    @DisplayName("세션 ID 와 dbId 가 각각 정확히 노출된다")
    void exposesBothIdentifiers() {
        Player p = new Player(7, 12345L, "alice", null, "henesys", 0, 0);
        assertEquals(7, p.id(), "세션 ID");
        assertEquals(12345L, p.dbId(), "영속 PK");
    }

    @Test
    @DisplayName("dbId 0 은 거부된다 — Player 가 살아있다면 PK 가 유효해야 한다")
    void rejectsZeroDbId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Player(1, 0L, "x", null, "m", 0, 0));
    }

    @Test
    @DisplayName("dbId 음수는 거부된다 — sentinel(-1) 등이 새지 않게")
    void rejectsNegativeDbId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Player(1, -1L, "x", null, "m", 0, 0));
    }
}
