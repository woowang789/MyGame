package mygame.game.shop;

import mygame.game.GameMap;
import mygame.game.entity.Npc;
import mygame.game.entity.Player;
import mygame.game.item.ItemRegistry;
import mygame.game.item.ItemTemplate;
import mygame.game.shop.ShopCatalog.Entry;

/**
 * 상점 트랜잭션을 도메인 규칙으로 검증·실행하는 서비스.
 *
 * <p>네트워크 계층(ShopHandler) 은 패킷 → DTO 변환만 담당하고, 실제 검증·차감·
 * 보상 처리는 모두 이쪽에 있다. 단위 테스트하기 좋은 형태로 분리.
 *
 * <p><b>트랜잭션의 의미</b>: spendMeso 와 inventory.add 는 두 객체에 걸친
 * 작업이지만, 외부 DB 가 아니라 메모리 상태이므로 SQL 트랜잭션이 아닌
 * 명시적 보상(compensation) 으로 원자성을 흉내 낸다 — spendMeso 가 통과한 뒤
 * inventory.add 가 실패하면 즉시 메소를 환불한다.
 *
 * <p><b>거리 정책</b>: 카탈로그 조회(SHOP_OPEN)와 거래(SHOP_BUY) 모두 거리 검증을
 * 두지 않는다. "카탈로그가 열렸다 = 거래 가능" 이 사용자 모델과 일치하므로
 * 두 단계의 정책을 통일한 결과. 핵 방어는 메소·재고·overflow 검증으로 충분.
 */
public final class ShopService {

    private ShopService() {}

    public sealed interface BuyResult {
        record Ok(long totalPaid, long mesoAfter) implements BuyResult {}
        record Failure(String reason) implements BuyResult {}
    }

    public sealed interface SellResult {
        record Ok(long totalGained, long mesoAfter) implements SellResult {}
        record Failure(String reason) implements SellResult {}
    }

    /**
     * 상점에서 한 종류의 아이템을 {@code qty} 만큼 구매한다.
     *
     * @param map    현재 플레이어가 있는 맵 (NPC 존재 확인용)
     * @param player 구매 주체
     * @param shopId 상점 식별자
     * @param itemId 아이템 템플릿 ID
     * @param qty    구매 수량(양수)
     */
    public static BuyResult buy(GameMap map, Player player,
                                String shopId, String itemId, int qty) {
        // 1) 같은 맵에 NPC 가 존재하는지만 확인. 거리는 검증하지 않는다.
        Npc npc = map.findNpcByShopId(shopId);
        if (npc == null) return new BuyResult.Failure("상점을 찾을 수 없습니다");

        // 2) 카탈로그 검증
        ShopCatalog catalog = ShopRegistry.find(shopId).orElse(null);
        if (catalog == null) return new BuyResult.Failure("상점 카탈로그가 없습니다");
        Entry entry = catalog.find(itemId).orElse(null);
        if (entry == null) return new BuyResult.Failure("판매하지 않는 아이템입니다");

        // 3) 수량 검증
        if (qty <= 0) return new BuyResult.Failure("수량은 양수여야 합니다");
        if (qty > entry.stockPerTransaction()) {
            return new BuyResult.Failure("최대 " + entry.stockPerTransaction() + "개까지 구매 가능");
        }

        // 4) overflow 체크 — 가격×수량이 long 한계를 넘지 않게
        long total;
        try {
            total = Math.multiplyExact(entry.price(), (long) qty);
        } catch (ArithmeticException e) {
            return new BuyResult.Failure("가격이 너무 큽니다");
        }

        // 5) 트랜잭션: 메소 차감 → 인벤 추가. 실패 시 메소 환불(보상 패턴).
        //    플레이어 본인의 두 mutator 만 건드리므로 player 락으로 충분하다.
        synchronized (player) {
            if (!player.spendMeso(total)) {
                return new BuyResult.Failure("메소가 부족합니다");
            }
            if (!player.inventory().add(itemId, qty)) {
                player.addMeso(total);
                return new BuyResult.Failure("인벤토리가 가득 찼습니다");
            }
        }
        return new BuyResult.Ok(total, player.meso());
    }

    /**
     * 인벤토리에서 아이템 {@code qty} 개를 매입가로 판매한다.
     *
     * <p>매입가는 {@link ItemTemplate#sellPrice()} 가 SSoT — 어떤 NPC 든 같은 가격이다.
     * 같은 맵에 NPC 가 존재하기만 하면 거리 무관하게 거래(구매·매입 정책 통일).
     *
     * <p>트랜잭션: 인벤 차감 → 메소 추가. addMeso 는 사실상 실패하지 않으므로 보상이
     * 필요 없다 (정수 overflow 는 사전 체크로 차단).
     */
    public static SellResult sell(GameMap map, Player player,
                                  String shopId, String itemId, int qty) {
        // 1) 같은 맵에 NPC 존재 확인 (구매와 정책 통일)
        Npc npc = map.findNpcByShopId(shopId);
        if (npc == null) return new SellResult.Failure("상점을 찾을 수 없습니다");
        if (ShopRegistry.find(shopId).isEmpty()) {
            return new SellResult.Failure("상점 카탈로그가 없습니다");
        }

        // 2) 아이템 템플릿 조회 + 매입 가능 여부
        ItemTemplate t;
        try {
            t = ItemRegistry.get(itemId);
        } catch (IllegalArgumentException e) {
            return new SellResult.Failure("알 수 없는 아이템입니다");
        }
        if (!t.isSellable()) {
            return new SellResult.Failure("매입하지 않는 아이템입니다");
        }

        // 3) 수량 검증
        if (qty <= 0) return new SellResult.Failure("수량은 양수여야 합니다");

        // 4) overflow 체크
        long total;
        try {
            total = Math.multiplyExact(t.sellPrice(), (long) qty);
        } catch (ArithmeticException e) {
            return new SellResult.Failure("가격이 너무 큽니다");
        }

        // 5) 트랜잭션: 인벤 차감 → 메소 추가. inventory.remove 는 보유 부족 시 false.
        synchronized (player) {
            if (!player.inventory().remove(itemId, qty)) {
                return new SellResult.Failure("보유 수량이 부족합니다");
            }
            player.addMeso(total);
        }
        return new SellResult.Ok(total, player.meso());
    }
}
