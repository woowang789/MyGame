package mygame.game.shop;

import java.util.List;
import java.util.Optional;

/**
 * NPC 상점 카탈로그. 한 NPC 가 파는 아이템 목록을 불변 데이터로 보관한다.
 *
 * <p>{@code stockPerTransaction} 은 한 번 거래 요청에 살 수 있는 최대 수량이다.
 * 거래마다 별도 호출이라 사실상 무제한이지만, 클라 UI 가 단번에 너무 큰 수량을
 * 구매하지 않도록 합의한 안전 범위다.
 */
public record ShopCatalog(String shopId, List<Entry> items) {

    public ShopCatalog {
        items = List.copyOf(items); // 외부에서 들어온 가변 리스트를 차단(방어적 복사)
    }

    public record Entry(String itemId, long price, int stockPerTransaction) {}

    public Optional<Entry> find(String itemId) {
        for (Entry e : items) {
            if (e.itemId().equals(itemId)) return Optional.of(e);
        }
        return Optional.empty();
    }
}
