package mygame.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Monster} 의 동시성 보호 검증.
 *
 * <p>여러 워커가 동시에 {@code applyDamage} 를 호출해도 lost update 없이 모두
 * 반영되어야 한다. {@code synchronized} 가 빠지면 이 테스트가 깨지면서 회귀를 잡는다.
 */
class MonsterConcurrencyTest {

    @Test
    @DisplayName("동시 applyDamage: 모든 데미지가 누적 반영된다(lost update 없음)")
    void applyDamage_concurrent_noLostUpdate() throws Exception {
        // HP 10000 인 몬스터에 1000개 스레드가 1씩 때리면 정확히 1000 깎여야 한다.
        // synchronized 가 없으면 "동시 read 후 늦은 write" 로 일부가 사라진다.
        Monster m = new Monster(
                1, "test", 0, 0, -100, 100,
                /* speed */ 50,
                /* maxHp */ 10_000,
                /* atk   */ 0,
                /* atkMs */ 1000);

        int threads = 32;
        int hitsPerThread = 50;
        int totalHits = threads * hitsPerThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger appliedSum = new AtomicInteger(0);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < hitsPerThread; i++) {
                            appliedSum.addAndGet(m.applyDamage(1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "타임아웃");
        } finally {
            pool.shutdownNow();
        }

        // 누적 적용 합과 hp 감소량이 일치해야 함.
        assertEquals(totalHits, appliedSum.get(), "누적 적용량 손실 발생");
        assertEquals(10_000 - totalHits, m.hp(), "hp 가 정확히 감소해야 함");
    }

    @Test
    @DisplayName("hp 가 0 이하로 떨어지지 않는다(과다 데미지 클램프)")
    void applyDamage_clampsAtZero() {
        Monster m = new Monster(1, "test", 0, 0, -100, 100, 50, 100, 0, 1000);
        int applied1 = m.applyDamage(60);
        int applied2 = m.applyDamage(60);
        assertEquals(60, applied1);
        assertEquals(40, applied2, "남은 hp(40) 만큼만 적용");
        assertEquals(0, m.hp());
        assertTrue(m.isDead());
        // 이미 죽었으면 추가 데미지는 0
        assertEquals(0, m.applyDamage(10));
    }
}
