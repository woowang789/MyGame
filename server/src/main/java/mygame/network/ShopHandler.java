package mygame.network;

import java.util.List;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Player;
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopRegistry;
import mygame.game.shop.ShopService;
import mygame.network.packets.Packets.InventoryPacket;
import mygame.network.packets.Packets.MesoUpdatedPacket;
import mygame.network.packets.Packets.ShopBuyRequest;
import mygame.network.packets.Packets.ShopCatalogEntry;
import mygame.network.packets.Packets.ShopOpenRequest;
import mygame.network.packets.Packets.ShopOpenedPacket;
import mygame.network.packets.Packets.ShopResultPacket;

/**
 * NPC 상점 관련 패킷 핸들러.
 *
 * <p>도메인 검증·트랜잭션은 {@link ShopService} 에 위임하고, 본 클래스는 패킷 ↔ DTO
 * 변환과 결과 송신만 책임진다. ChatHandler/InventoryHandler 와 같은 분리 패턴.
 */
public final class ShopHandler {

    private final World world;

    public ShopHandler(World world) {
        this.world = world;
    }

    public void handleOpen(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        ShopOpenRequest req = ctx.json().treeToValue(ctx.body(), ShopOpenRequest.class);

        // 카탈로그 + NPC 위치(거리) 모두 검증해야 핵을 막는다.
        GameMap map = world.map(player.mapId());
        if (map == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "맵 정보 없음"));
            return;
        }
        var npc = map.findNpcByShopId(req.shopId());
        if (npc == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "이 맵에 해당 상점이 없습니다"));
            return;
        }
        double dx = npc.x() - player.x();
        double dy = npc.y() - player.y();
        if (Math.sqrt(dx * dx + dy * dy) > ShopService.INTERACT_RANGE) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "NPC 와 너무 멀리 있습니다"));
            return;
        }

        ShopCatalog catalog = ShopRegistry.find(req.shopId()).orElse(null);
        if (catalog == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "상점 카탈로그 없음"));
            return;
        }
        List<ShopCatalogEntry> items = catalog.items().stream()
                .map(e -> new ShopCatalogEntry(e.itemId(), e.price(), e.stockPerTransaction()))
                .toList();
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "SHOP_OPENED",
                new ShopOpenedPacket(req.shopId(), npc.name(), items, player.meso())));
    }

    public void handleBuy(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        ShopBuyRequest req = ctx.json().treeToValue(ctx.body(), ShopBuyRequest.class);

        GameMap map = world.map(player.mapId());
        if (map == null) {
            ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "SHOP_RESULT",
                    new ShopResultPacket(false, "맵 정보 없음", player.meso())));
            return;
        }

        var result = ShopService.buy(map, player, req.shopId(), req.itemId(), req.qty());
        switch (result) {
            case ShopService.BuyResult.Ok ok -> {
                // 메소·인벤 상태가 바뀌었으므로 본인에게 즉시 풀-싱크.
                ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "MESO",
                        new MesoUpdatedPacket(player.meso(), -ok.totalPaid())));
                ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                        new InventoryPacket(player.inventory().snapshot())));
                ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "SHOP_RESULT",
                        new ShopResultPacket(true, null, ok.mesoAfter())));
            }
            case ShopService.BuyResult.Failure f ->
                    ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "SHOP_RESULT",
                            new ShopResultPacket(false, f.reason(), player.meso())));
        }
    }
}
