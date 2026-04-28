package mygame.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import mygame.game.entity.Monster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link GameMap#lastContactHitAt} 누수 방지 검증.
 *
 * <p>플레이어 퇴장 / 몬스터 사망 시 해당 id 가 포함된 키가 즉시 정리되어야 한다.
 * 정리 로직이 빠지면 장시간 운영 시 키가 누적되어 메모리 비효율로 이어진다.
 */
class GameMapContactHitCleanupTest {

    private GameMap newMap() {
        return new GameMap("test", 0, 0, new ObjectMapper(), () -> 1);
    }

    private Monster monster(int id) {
        // 인자는 많지만 cleanup 검증엔 id 만 의미가 있다.
        return new Monster(id, "stub", 0, 0, -100, 100, 50, 100, 0, 1000);
    }

    @Nested
    @DisplayName("키 인코딩 round-trip")
    class PairKey {

        @Test
        @DisplayName("pairKey → extractPlayerId/extractMonsterId 역변환 일치")
        void encodeDecode() {
            int p = 7, m = 42;
            long k = GameMap.pairKey(p, m);
            assertEquals(p, GameMap.extractPlayerId(k));
            assertEquals(m, GameMap.extractMonsterId(k));
        }

        @Test
        @DisplayName("큰 monster id 도 부호 손상 없이 round-trip")
        void encodeDecode_largeMonsterId() {
            int p = 1, m = 0x7FFF_FFFF; // 32비트 양수 최대
            long k = GameMap.pairKey(p, m);
            assertEquals(m, GameMap.extractMonsterId(k));
        }
    }

    @Nested
    @DisplayName("cleanup 동작")
    class Cleanup {

        @Test
        @DisplayName("removePlayer: 해당 플레이어의 모든 페어 제거, 다른 플레이어는 유지")
        void removePlayer_clearsOnlyThatPlayer() {
            GameMap map = newMap();
            // dbId(두 번째 인자) 는 1 이상 양수만 허용. 테스트엔 의미 없는 임의값.
            map.addPlayer(new mygame.game.entity.Player(
                    1, 1L, "p1", null, "test", 0, 0));
            map.addPlayer(new mygame.game.entity.Player(
                    2, 2L, "p2", null, "test", 0, 0));
            map.putContactHitForTest(1, 100, 1L);
            map.putContactHitForTest(1, 200, 2L);
            map.putContactHitForTest(2, 100, 3L);
            assertEquals(3, map.contactHitSize());

            map.removePlayer(1);

            // p2-100 만 남아야 함
            assertEquals(1, map.contactHitSize());
        }

        @Test
        @DisplayName("killMonster: 해당 몬스터의 모든 페어 제거, 다른 몬스터는 유지")
        void killMonster_clearsOnlyThatMonster() {
            GameMap map = newMap();
            map.putContactHitForTest(1, 100, 1L);
            map.putContactHitForTest(2, 100, 2L);
            map.putContactHitForTest(1, 200, 3L);
            assertEquals(3, map.contactHitSize());

            map.killMonster(monster(100), null);

            // p1-200 만 남아야 함
            assertEquals(1, map.contactHitSize());
        }

        @Test
        @DisplayName("아무도 안 맞은 몬스터 사망 시에도 안전")
        void killMonster_noEntries_isSafe() {
            GameMap map = newMap();
            map.killMonster(monster(999), null);
            assertEquals(0, map.contactHitSize());
        }
    }
}
