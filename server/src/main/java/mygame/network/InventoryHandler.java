package mygame.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Player;
import mygame.game.item.DroppedItem;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemRegistry;
import mygame.game.item.ItemTemplate;
import mygame.network.packets.Packets.DropItemRequest;
import mygame.network.packets.Packets.EquipRequest;
import mygame.network.packets.Packets.InventoryPacket;
import mygame.network.packets.Packets.MesoUpdatedPacket;
import mygame.network.packets.Packets.UnequipRequest;
import mygame.network.packets.Packets.UseItemRequest;

/**
 * 인벤토리·장비·드롭 관련 핸들러. PICKUP / EQUIP / UNEQUIP / USE_ITEM / DROP_ITEM.
 *
 * <p>플레이어의 아이템 상태를 바꾼 뒤 인벤토리 스냅샷을 당사자에게 전송하는 패턴이 공통.
 * 장비/소비는 스탯 변화도 동반하므로 {@link SessionNotifier} 를 거친다.
 */
public final class InventoryHandler {

    /** 드롭 아이템의 기본 TTL(ms). 서버가 주기적으로 만료 아이템을 청소한다. */
    public static final long DROP_TTL_MS = 60_000;
    /** 픽업 가능 거리(맨해튼 근사). */
    private static final double PICKUP_RANGE = 40;

    private final World world;
    private final ObjectMapper json;
    private final SessionNotifier notifier;

    public InventoryHandler(World world, ObjectMapper json, SessionNotifier notifier) {
        this.world = world;
        this.json = json;
        this.notifier = notifier;
    }

    public void handlePickup(PacketContext ctx) {
        Player player = ctx.player();
        if (player == null) return;
        GameMap map = world.map(player.mapId());
        if (map == null) return;

        for (DroppedItem d : map.droppedItems()) {
            double dx = d.x() - player.x();
            double dy = d.y() - player.y();
            if (Math.abs(dx) > PICKUP_RANGE || Math.abs(dy) > PICKUP_RANGE) continue;

            if (d.isMeso()) {
                if (map.takeDroppedItem(d.id()) == null) continue;
                int gained = d.amount();
                player.addMeso(gained);
                player.connection().send(PacketEnvelope.wrap(json, "MESO",
                        new MesoUpdatedPacket(player.meso(), gained)));
                continue;
            }

            // 같은 id 스택 증가는 항상 허용되므로, 맵에서 먼저 take 하고 인벤토리 add 를 시도한다.
            if (map.takeDroppedItem(d.id()) == null) continue;
            boolean added = player.inventory().add(d.templateId(), 1);
            if (!added) {
                // 실패 — 아이템을 다시 맵에 되돌리고 사용자에게 경고.
                map.addDroppedItem(d);
                player.connection().send(PacketEnvelope.error(json, "인벤토리가 가득 찼습니다."));
                continue;
            }
            player.connection().send(PacketEnvelope.wrap(json, "INVENTORY",
                    new InventoryPacket(player.inventory().snapshot())));
        }
    }

    public void handleEquip(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        EquipRequest req = ctx.json().treeToValue(ctx.body(), EquipRequest.class);
        String itemId = req.templateId();
        if (itemId == null || !ItemRegistry.isEquipment(itemId)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "장비 아이템이 아닙니다."));
            return;
        }
        if (!player.inventory().remove(itemId, 1)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "인벤토리에 해당 장비가 없습니다."));
            return;
        }
        String replaced = player.equipment().equip(itemId);
        // 기존 장비는 인벤토리로 되돌린다(교체). 변경이 원자적이도록 이 순서가 중요.
        if (replaced != null) player.inventory().add(replaced, 1);
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        notifier.sendEquipmentAndStats(player);
    }

    public void handleUnequip(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        UnequipRequest req = ctx.json().treeToValue(ctx.body(), UnequipRequest.class);
        EquipSlot slot;
        try {
            slot = EquipSlot.valueOf(req.slot());
        } catch (IllegalArgumentException e) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "알 수 없는 슬롯: " + req.slot()));
            return;
        }
        String removed = player.equipment().unequip(slot);
        if (removed == null) return;
        player.inventory().add(removed, 1);
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        notifier.sendEquipmentAndStats(player);
    }

    public void handleUseItem(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        if (player.isDead()) return;
        UseItemRequest req = ctx.json().treeToValue(ctx.body(), UseItemRequest.class);
        String id = req.templateId();
        if (id == null) return;
        ItemTemplate template = ItemRegistry.get(id);
        if (template.type() != ItemTemplate.ItemType.CONSUMABLE) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "소비 아이템이 아닙니다."));
            return;
        }
        if (!player.inventory().remove(id, 1)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "보유하고 있지 않은 아이템입니다."));
            return;
        }
        var effect = template.use();
        if (effect.heal() > 0) player.restoreHp(effect.heal());
        if (effect.manaHeal() > 0) player.restoreMp(effect.manaHeal());
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        notifier.sendStats(player);
    }

    public void handleDropItem(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        if (player.isDead()) return;
        DropItemRequest req = ctx.json().treeToValue(ctx.body(), DropItemRequest.class);
        String id = req.templateId();
        int amount = Math.max(1, req.amount());
        if (id == null) return;
        if (!player.inventory().remove(id, amount)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "버릴 수량이 충분하지 않습니다."));
            return;
        }
        GameMap map = world.map(player.mapId());
        if (map == null) return;
        // 드롭은 플레이어 발밑에 놓는다. TTL 은 DROP_TTL_MS 로 일원화.
        map.addDroppedItem(new DroppedItem(
                world.itemIdSeq().getAndIncrement(),
                id, player.x(), player.y(), DROP_TTL_MS, amount));
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
    }
}
