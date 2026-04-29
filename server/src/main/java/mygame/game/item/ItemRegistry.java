package mygame.game.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.db.ItemTemplateRepository;
import mygame.game.item.ItemTemplate.ItemType;

/**
 * 아이템 원형 캐시 + 핫 리로드 진입점.
 *
 * <p>이전 단계에서는 정적 코드 상수가 SSoT 였다. Phase 4-2 에서 DB 의 {@code item_templates}
 * 가 SSoT 가 되고, 본 클래스는 다음 두 가지를 한다:
 * <ul>
 *   <li>서버 시작 시 {@link #bootstrap(ItemTemplateRepository)} 가 모든 템플릿을 한 번 적재.
 *   <li>운영 화면이 추가/수정/삭제 직후 {@link #reload(String)} 로 해당 itemId 만 갱신.
 * </ul>
 *
 * <p><b>API 호환성</b>: {@link #get}, {@link #isEquipment}, {@link #equipmentIds},
 * {@link #sellPrices} 모두 시그니처를 보존 — 게임 핫패스 코드(Equipment·Inventory·
 * ShopService 등)는 손대지 않아도 데이터 출처가 바뀐다.
 *
 * <p><b>호출 순서</b>: {@code ShopRegistry.bootstrap} 보다 <em>먼저</em> 호출돼야 한다.
 * ShopRegistry 가 카탈로그 검증 시 {@link #get} 을 호출하기 때문.
 */
public final class ItemRegistry {

    private static volatile ItemTemplateRepository repo = null;
    private static final ConcurrentHashMap<String, ItemTemplate> CACHE = new ConcurrentHashMap<>();

    private ItemRegistry() {}

    public static synchronized void bootstrap(ItemTemplateRepository repository) {
        repo = repository;
        Map<String, ItemTemplate> all = repository.loadAll();
        CACHE.clear();
        CACHE.putAll(all);
    }

    public static void reload(String itemId) {
        if (repo == null) return;
        repo.findById(itemId).ifPresentOrElse(
                t -> CACHE.put(itemId, t),
                () -> CACHE.remove(itemId));
    }

    public static ItemTemplate get(String id) {
        ItemTemplate t = CACHE.get(id);
        if (t == null) {
            throw new IllegalArgumentException("알 수 없는 아이템: " + id);
        }
        return t;
    }

    /** 주어진 아이템이 장비(EQUIPMENT)인지 검사. null-safe. */
    public static boolean isEquipment(String id) {
        ItemTemplate t = CACHE.get(id);
        return t != null && t.type() == ItemType.EQUIPMENT;
    }

    /** 장비로 등록된 모든 아이템 ID. 클라 HUD 에 SSoT 로 전달. */
    public static List<String> equipmentIds() {
        // 캐시 스냅샷에서 derive — 운영 변경 후에는 재호출하면 최신값을 본다.
        List<String> out = new ArrayList<>();
        for (ItemTemplate t : CACHE.values()) {
            if (t.type() == ItemType.EQUIPMENT) out.add(t.id());
        }
        Collections.sort(out); // 결정적 순서 (META 패킷 일관성)
        return List.copyOf(out);
    }

    /** 모든 매입가 맵 (sellPrice > 0 인 아이템만). META 패킷으로 클라에 전달. */
    public static Map<String, Long> sellPrices() {
        Map<String, Long> out = new LinkedHashMap<>();
        // 키 결정성을 위해 id 정렬 후 삽입.
        List<ItemTemplate> sorted = new ArrayList<>(CACHE.values());
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (ItemTemplate t : sorted) {
            if (t.isSellable()) out.put(t.id(), t.sellPrice());
        }
        return Collections.unmodifiableMap(out);
    }
}
