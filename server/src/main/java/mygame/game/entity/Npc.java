package mygame.game.entity;

/**
 * 맵 위에 고정된 NPC. 현재는 상점 인터랙션만 지원한다.
 *
 * <p>위치는 불변(record). 클라가 자기 캐릭터가 NPC 근처에 있을 때
 * 상점을 열 수 있고, 서버는 거리 검증으로 핵을 차단한다.
 */
public record Npc(
        int id,
        String name,
        double x,
        double y,
        /** 연결된 {@link mygame.game.shop.ShopCatalog} 의 식별자. */
        String shopId
) {}
