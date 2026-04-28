package mygame.game.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Player#tryActivateSkill} 의 쿨다운 + 동시성 보장 검증.
 *
 * <p>{@code compute} 의 원자성 덕분에 같은 스킬을 동시에 여러 스레드가 시도해도
 * 정확히 한 번만 활성화돼야 한다. 사이드 채널 트릭 제거 후에도 행동이 동일함을
 * 회귀 테스트로 확정한다.
 */
class PlayerSkillCooldownTest {

    private Player newPlayer() {
        return new Player(1, 1L, "p", null, "test", 0, 0);
    }

    @Test
    @DisplayName("처음 사용은 활성화된다")
    void firstUse_activates() {
        Player p = newPlayer();
        assertTrue(p.tryActivateSkill("fireball", 1000, 1_000));
    }

    @Test
    @DisplayName("쿨다운 안에 다시 호출하면 거부")
    void duringCooldown_rejected() {
        Player p = newPlayer();
        assertTrue(p.tryActivateSkill("fireball", 1000, 1_000));
        assertFalse(p.tryActivateSkill("fireball", 1000, 1_500),
                "500ms 만 지났으니 거부");
    }

    @Test
    @DisplayName("쿨다운 종료 직후엔 다시 활성화")
    void afterCooldown_activates() {
        Player p = newPlayer();
        assertTrue(p.tryActivateSkill("fireball", 1000, 1_000));
        assertTrue(p.tryActivateSkill("fireball", 1000, 2_000),
                "1000ms 경과 — 통과");
    }

    @Test
    @DisplayName("동시 호출: 정확히 한 번만 활성화된다(race 차단)")
    void concurrent_exactlyOneActivation() throws Exception {
        Player p = newPlayer();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (p.tryActivateSkill("fireball", 5000, 1_000)) {
                            successes.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // compute 의 원자성으로 정확히 한 번만 통과해야 함
        org.junit.jupiter.api.Assertions.assertEquals(1, successes.get());
    }
}
