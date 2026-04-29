package mygame.game.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.db.MonsterTemplateRepository;
import mygame.game.item.ItemRegistry;

/**
 * 몬스터 템플릿 캐시 + 핫 리로드 진입점.
 *
 * <p>이전 단계에서는 정적 코드 상수가 SSoT 였다. Phase 4-3 에서 DB 의 {@code monster_templates}
 * + {@code monster_drops} 가 SSoT 가 되고, 본 클래스는 다음 두 가지를 한다:
 * <ul>
 *   <li>서버 시작 시 {@link #bootstrap(MonsterTemplateRepository)} 가 모든 종을 한 번 적재.
 *   <li>운영 화면이 추가/수정/삭제 직후 {@link #reload(String)} 로 해당 monsterId 만 갱신.
 * </ul>
 *
 * <p><b>API 호환성</b>: {@link #get} 시그니처를 보존 — SpawnPoint 등 게임 핫패스 코드는
 * 손대지 않아도 데이터 출처가 바뀐다.
 *
 * <p><b>호출 순서</b>: {@link ItemRegistry#bootstrap} 보다 <em>나중에</em> 호출돼야 한다.
 * drop table 의 itemId 가 ItemRegistry 에 등록돼 있는지 검증하기 위함.
 */
public final class MonsterRegistry {

    private static volatile MonsterTemplateRepository repo = null;
    private static final ConcurrentHashMap<String, MonsterTemplate> CACHE = new ConcurrentHashMap<>();

    private MonsterRegistry() {}

    public static synchronized void bootstrap(MonsterTemplateRepository repository) {
        repo = repository;
        Map<String, MonsterTemplate> all = repository.loadAll();
        CACHE.clear();
        CACHE.putAll(all);
        validateAll();
    }

    public static void reload(String monsterId) {
        if (repo == null) return;
        repo.findById(monsterId).ifPresentOrElse(
                t -> CACHE.put(monsterId, t),
                () -> CACHE.remove(monsterId));
    }

    public static MonsterTemplate get(String id) {
        MonsterTemplate t = CACHE.get(id);
        if (t == null) throw new IllegalArgumentException("알 수 없는 몬스터 템플릿: " + id);
        return t;
    }

    /** drop table 의 itemId 가 ItemRegistry 에 등록돼 있는지 시작 시점에 한 번 검증. */
    private static void validateAll() {
        for (MonsterTemplate t : CACHE.values()) {
            for (var e : t.dropTable().entries()) {
                ItemRegistry.get(e.itemId()); // 미등록이면 IAE
                if (e.chance() < 0.0 || e.chance() > 1.0) {
                    throw new IllegalStateException(
                            "monster=" + t.id() + " drop=" + e.itemId()
                                    + " chance 는 0..1 범위여야 함: " + e.chance());
                }
            }
        }
    }
}
