import Phaser from 'phaser';
import type { AuthedSession } from '../auth/LoginScreen';
import { DroppedItemSprite } from '../entities/DroppedItemSprite';
import { MonsterSprite } from '../entities/MonsterSprite';
import { NpcSprite } from '../entities/NpcSprite';
import { RemotePlayer } from '../entities/RemotePlayer';
import type { WebSocketClient, Packet } from '../network/WebSocketClient';
import { ChatController } from '../ui/ChatController';
import { EffectFactory } from '../ui/EffectFactory';
import { applyMeta, EQUIPMENT_IDS, HudView, SKILL_META } from '../ui/HudView';
import { getItemMeta } from '../data/ItemMeta';
import { PickupLog } from '../ui/PickupLog';
import { MapController } from './MapController';
import {
  generateNpcTexture,
  generatePlayerTexture,
  generateTilesetTexture,
  PLAYER_TEXTURE
} from './TextureFactory';

const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;
/** 피격 넉백: 수평 밀림 속도 · 살짝 뜨는 높이 · 입력 잠금 시간(ms). */
const KNOCKBACK_SPEED_X = 220;
const KNOCKBACK_VELOCITY_Y = -180;
const KNOCKBACK_DURATION_MS = 220;
/** NPC 인터랙션 가능 거리(px). 서버 ShopService.INTERACT_RANGE 와 일치. */
const NPC_INTERACT_RANGE = 80;

const MAPS = ['henesys', 'ellinia'] as const;

interface PlayerStateMsg {
  id: number;
  name: string;
  x: number;
  y: number;
}

interface MonsterStateMsg {
  id: number;
  template: string;
  x: number;
  y: number;
  hp: number;
  maxHp: number;
}

interface DroppedItemMsg {
  id: number;
  templateId: string;
  x: number;
  y: number;
}

interface NpcStateMsg {
  id: number;
  name: string;
  x: number;
  y: number;
  shopId: string;
}

/**
 * Phase D — 다중 맵 + 포털 전환.
 */
export class GameScene extends Phaser.Scene {
  private player!: Phaser.Physics.Arcade.Sprite;
  private cursors!: Phaser.Types.Input.Keyboard.CursorKeys;
  private attackKey!: Phaser.Input.Keyboard.Key;
  private equipKey!: Phaser.Input.Keyboard.Key;
  private unequipKey!: Phaser.Input.Keyboard.Key;
  private inventoryKey!: Phaser.Input.Keyboard.Key;
  private statKey!: Phaser.Input.Keyboard.Key;
  private escKey!: Phaser.Input.Keyboard.Key;
  private interactKey!: Phaser.Input.Keyboard.Key;
  private skillKeys!: Record<string, Phaser.Input.Keyboard.Key>;
  private myLabel!: Phaser.GameObjects.Text;
  private facing: 'left' | 'right' = 'right';
  private readonly network: WebSocketClient;
  private lastMoveSentAt = 0;
  private myId = -1;
  private readonly playerName: string;
  private readonly remotes = new Map<number, RemotePlayer>();
  private readonly monsters = new Map<number, MonsterSprite>();
  private readonly droppedItems = new Map<number, DroppedItemSprite>();
  private readonly npcs = new Map<number, NpcSprite>();
  /** 현재 상점 창에 열려있는 shopId. null 이면 닫힘 — 결과 패킷 라우팅에 사용. */
  private openShopId: string | null = null;
  private lastPickupAttemptAt = 0;
  /** 인벤토리 스냅샷. Q 키로 첫 장비를 찾을 때 참조. */
  private inventoryItems: Record<string, number> = {};
  /** 현재 착용 중인 장비 슬롯. E 키로 해제. */
  private equippedSlots: Record<string, string> = {};
  /** 스킬별 남은 쿨다운 종료 시각(ms, performance.now 기준). HUD 카운트다운 표시용. */
  private readonly skillCooldownUntil = new Map<string, number>();
  /** 넉백 종료 시각(performance.now 기준). 이 시각까지 좌우 입력을 무시해 밀려나는 느낌을 준다. */
  private knockbackUntil = 0;
  /** true 면 사망 상태. 부활 패킷 수신 시 false 로 복귀. 사망 중에는 입력 전반을 차단한다. */
  private isDead = false;
  private readonly hud = new HudView();
  private readonly effects: EffectFactory;
  private readonly chat: ChatController;
  private readonly pickupLog = new PickupLog();
  /** 인벤 증가분 계산용 마지막 스냅샷. onInventory 가 새 값 수신 시 diff 해서 알림을 쏜다. */
  private lastInventorySnapshot: Record<string, number> = {};
  /** JOIN 직후 복원된 인벤토리 스냅샷을 "획득" 으로 오인하지 않기 위한 가드. */
  private inventoryInitialized = false;

  private currentMapId = 'henesys';
  private mapController!: MapController;

  constructor(network: WebSocketClient, session: AuthedSession) {
    super('GameScene');
    this.network = network;
    this.playerName = session.username;
    this.effects = new EffectFactory(this);
    this.chat = new ChatController(this, network);
  }

  preload(): void {
    for (const id of MAPS) {
      this.load.tilemapTiledJSON(id, `assets/${id}.json`);
    }
    generateTilesetTexture(this);
    generatePlayerTexture(this);
    generateNpcTexture(this);
    // 몬스터 텍스처는 MonsterSprite 가 관리.
    MonsterSprite.generateTexture(this);
  }

  create(): void {
    // 플레이어는 씬 전체에 단 하나. 맵 전환 시에도 동일 인스턴스를 이어 사용.
    this.player = this.physics.add.sprite(80, 100, PLAYER_TEXTURE);
    this.player.setCollideWorldBounds(true);

    this.myLabel = this.add
      .text(this.player.x, this.player.y - 28, this.playerName, {
        fontSize: '11px',
        color: '#ffeb8a'
      })
      .setOrigin(0.5, 1);
    this.events.on(Phaser.Scenes.Events.POST_UPDATE, () => {
      this.myLabel.setPosition(this.player.x, this.player.y - this.player.displayHeight / 2 - 4);
    });

    this.cameras.main.startFollow(this.player, true, 0.1, 0.1);
    this.cameras.main.setDeadzone(200, 120);

    this.cursors = this.input.keyboard!.createCursorKeys();
    this.attackKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.SPACE);
    this.equipKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.Q);
    this.unequipKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.E);
    this.inventoryKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.I);
    this.statKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.S);
    this.escKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.ESC);
    this.interactKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.F);
    this.skillKeys = {
      power_strike: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.ONE),
      triple_blow: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.TWO),
      recovery: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.THREE)
    };

    this.hud.setPlayerName(this.playerName);
    // localStorage 네임스페이스: 캐릭터 이름 단위로 슬롯 순서를 분리.
    this.hud.setAccountKey(this.playerName);
    this.chat.setup();
    document.getElementById('inv-close')?.addEventListener('click', () => this.hud.closeInventory());
    document.getElementById('stat-close')?.addEventListener('click', () => this.hud.closeStat());
    document.getElementById('shop-close')?.addEventListener('click', () => this.closeShop());
    this.hud.bindInventoryInteractions();
    // 인벤/장비 슬롯의 더블클릭 → 서버 패킷으로 위임.
    this.hud.onInventoryAction((action) => {
      if (!this.network.isOpen) return;
      switch (action.kind) {
        case 'equip':
          this.network.send({ type: 'EQUIP', templateId: action.templateId });
          break;
        case 'unequip':
          this.network.send({ type: 'UNEQUIP', slot: action.slot });
          break;
        case 'use':
          this.network.send({ type: 'USE_ITEM', templateId: action.templateId });
          break;
        case 'drop':
          this.network.send({
            type: 'DROP_ITEM',
            templateId: action.templateId,
            amount: action.amount
          });
          break;
      }
    });

    this.mapController = new MapController(this, this.player);
    this.mapController.loadMap(this.currentMapId);
    this.setupNetwork();
  }

  private setupNetwork(): void {
    this.network.on('META', (p) => {
      applyMeta({
        equipmentIds: (p.equipmentIds ?? []) as string[],
        skills: (p.skills ?? []) as { id: string; name: string; mpCost: number; cooldownMs: number }[]
      });
    });
    this.network.on('WELCOME', (p) => this.onWelcome(p));
    this.network.on('PLAYER_JOIN', (p) => this.onPlayerJoin(p));
    this.network.on('PLAYER_MOVE', (p) => this.onPlayerMove(p));
    this.network.on('PLAYER_LEAVE', (p) => this.onPlayerLeave(p));
    this.network.on('MAP_CHANGED', (p) => this.onMapChanged(p));
    this.network.on('MONSTER_MOVE', (p) => this.onMonsterMove(p));
    this.network.on('MONSTER_DAMAGED', (p) => this.onMonsterDamaged(p));
    this.network.on('MONSTER_DIED', (p) => this.onMonsterDied(p));
    this.network.on('MONSTER_SPAWN', (p) => this.onMonsterSpawn(p));
    this.network.on('PLAYER_EXP', (p) => this.onPlayerExp(p));
    this.network.on('PLAYER_LEVELUP', (p) => this.onPlayerLevelUp(p));
    this.network.on('ITEM_DROPPED', (p) => this.onItemDropped(p));
    this.network.on('ITEM_REMOVED', (p) => this.onItemRemoved(p));
    this.network.on('INVENTORY', (p) => this.onInventory(p));
    this.network.on('MESO', (p) => this.onMeso(p));
    this.network.on('CHAT', (p) => this.onChat(p));
    this.network.on('EQUIPMENT', (p) => this.onEquipment(p));
    this.network.on('STATS', (p) => this.onStats(p));
    this.network.on('SKILL_USED', (p) => this.onSkillUsed(p));
    this.network.on('PLAYER_DAMAGED', (p) => this.onPlayerDamaged(p));
    this.network.on('PLAYER_DIED', (p) => this.onPlayerDied(p));
    this.network.on('PLAYER_RESPAWN', (p) => this.onPlayerRespawn(p));
    this.network.on('PLAYER_CORRECT', (p) => this.onPlayerCorrect(p));
    this.network.on('SHOP_OPENED', (p) => this.onShopOpened(p));
    this.network.on('SHOP_RESULT', (p) => this.onShopResult(p));
    this.network.on('ERROR', (p) => {
      const msg = String(p.message ?? '');
      console.warn('[Server ERROR]', msg);
      this.chat.append('sys', `[오류] ${msg}`);
      // 가장 최근 서버 에러를 기억했다가 소켓이 끊길 때 사용자에게 안내한다.
      this.lastServerError = msg;
    });
    // 서버가 연결을 종료하면(중복 로그인 등) 로그인 화면으로 되돌린다.
    // 가장 단순하고 확실한 방법은 페이지 재로드 — 게임 상태·WebSocket·DOM 모두 깨끗이 초기화된다.
    this.network.onClose(() => {
      const reason = this.lastServerError || '서버와의 연결이 종료되었습니다.';
      // alert 는 reload 전까지 페이지를 멈춰 사용자가 사유를 읽을 수 있게 한다.
      window.alert(reason);
      window.location.reload();
    });
    // 인증은 이미 완료되어 있다. 즉시 JOIN 전송.
    this.network.send({ type: 'JOIN', name: this.playerName });
  }

  private lastServerError: string | null = null;

  private onWelcome(p: Packet): void {
    this.myId = p.playerId as number;
    const self = p.self as PlayerStateMsg;
    this.player.setPosition(self.x, self.y);
    const others = (p.others ?? []) as PlayerStateMsg[];
    for (const o of others) this.spawnRemote(o);
    const ms = (p.monsters ?? []) as MonsterStateMsg[];
    for (const m of ms) this.spawnMonster(m);
    const items = (p.items ?? []) as DroppedItemMsg[];
    for (const it of items) this.spawnItem(it);
    const npcs = (p.npcs ?? []) as NpcStateMsg[];
    for (const n of npcs) this.spawnNpc(n);
  }

  private onPlayerJoin(p: Packet): void {
    const ps = p.player as PlayerStateMsg;
    if (ps.id === this.myId) return;
    this.spawnRemote(ps);
  }

  private onPlayerMove(p: Packet): void {
    const id = p.id as number;
    if (id === this.myId) return;
    this.remotes.get(id)?.setTarget(p.x as number, p.y as number);
  }

  private onPlayerLeave(p: Packet): void {
    const id = p.id as number;
    const r = this.remotes.get(id);
    if (!r) return;
    r.destroy();
    this.remotes.delete(id);
  }

  private onMapChanged(p: Packet): void {
    const mapId = p.mapId as string;
    const x = p.x as number;
    const y = p.y as number;
    const others = (p.others ?? []) as PlayerStateMsg[];

    // 원격 플레이어·몬스터 모두 제거 후 새 맵 스냅샷으로 교체
    for (const r of this.remotes.values()) r.destroy();
    this.remotes.clear();
    for (const m of this.monsters.values()) m.destroy();
    this.monsters.clear();

    this.currentMapId = mapId;
    this.mapController.loadMap(mapId);
    this.player.setVelocity(0, 0);
    this.player.setPosition(x, y);
    // 새 맵 bounds 가 적용된 뒤 카메라를 플레이어 위치로 즉시 스냅.
    // startFollow 의 lerp/deadzone 때문에 새 bounds 가장자리에 카메라가 고정돼
    // 자기 캐릭터가 화면 밖에 머무는 문제를 방지한다.
    this.cameras.main.centerOn(x, y);
    this.cameras.main.startFollow(this.player, true, 0.1, 0.1);

    for (const o of others) this.spawnRemote(o);
    const ms = (p.monsters ?? []) as MonsterStateMsg[];
    for (const m of ms) this.spawnMonster(m);
    for (const d of this.droppedItems.values()) d.destroy();
    this.droppedItems.clear();
    const items = (p.items ?? []) as DroppedItemMsg[];
    for (const it of items) this.spawnItem(it);
    for (const n of this.npcs.values()) n.destroy();
    this.npcs.clear();
    const npcs = (p.npcs ?? []) as NpcStateMsg[];
    for (const n of npcs) this.spawnNpc(n);
  }

  private spawnNpc(n: NpcStateMsg): void {
    if (this.npcs.has(n.id)) return;
    this.npcs.set(n.id, new NpcSprite(this, n.id, n.name, n.shopId, n.x, n.y));
  }

  /** 플레이어 위치 기준 가장 가까운 NPC. INTERACT_RANGE 안에 없으면 null. */
  private nearestNpc(): NpcSprite | null {
    let best: NpcSprite | null = null;
    let bestDist = NPC_INTERACT_RANGE;
    for (const n of this.npcs.values()) {
      const dx = n.sprite.x - this.player.x;
      const dy = n.sprite.y - this.player.y;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d <= bestDist) {
        bestDist = d;
        best = n;
      }
    }
    return best;
  }

  private spawnRemote(ps: PlayerStateMsg): void {
    if (this.remotes.has(ps.id)) return;
    this.remotes.set(
      ps.id,
      new RemotePlayer(this, ps.id, ps.name, ps.x, ps.y, PLAYER_TEXTURE)
    );
  }

  private spawnMonster(ms: MonsterStateMsg): void {
    if (this.monsters.has(ms.id)) return;
    this.monsters.set(ms.id, new MonsterSprite(this, ms.id, ms.template, ms.x, ms.y, ms.hp, ms.maxHp));
  }

  private onMonsterMove(p: Packet): void {
    this.monsters.get(p.id as number)?.setTarget(p.x as number, p.vx as number);
  }

  private onMonsterDamaged(p: Packet): void {
    const m = this.monsters.get(p.id as number);
    if (!m) return;
    m.applyDamage(p.hp as number, this, p.dmg as number);
  }

  private onMonsterDied(p: Packet): void {
    const id = p.id as number;
    const m = this.monsters.get(id);
    if (!m) return;
    m.destroy();
    this.monsters.delete(id);
  }

  private onMonsterSpawn(p: Packet): void {
    const monster = p.monster as MonsterStateMsg;
    this.spawnMonster(monster);
  }

  private spawnItem(ms: DroppedItemMsg): void {
    if (this.droppedItems.has(ms.id)) return;
    this.droppedItems.set(ms.id, new DroppedItemSprite(this, ms.id, ms.templateId, ms.x, ms.y));
  }

  private onItemDropped(p: Packet): void {
    this.spawnItem(p.item as DroppedItemMsg);
  }

  private onItemRemoved(p: Packet): void {
    const id = p.id as number;
    this.droppedItems.get(id)?.destroy();
    this.droppedItems.delete(id);
  }

  private onInventory(p: Packet): void {
    const items = p.items as Record<string, number>;
    // 이전 스냅샷과 비교해 증가분만 픽업 로그로 알린다.
    // 장비 착용·드롭 같은 "감소" 는 알리지 않고, 언장착/픽업 같은 "증가" 만 대상.
    // 서버에 전용 ITEM_GAINED 패킷을 추가하지 않고 클라 측 diff 로 해결해
    // 프로토콜 변경 없이 UX 를 개선한다.
    if (this.inventoryInitialized) {
      this.reportInventoryGains(this.lastInventorySnapshot, items);
    }
    this.lastInventorySnapshot = { ...items };
    this.inventoryInitialized = true;
    this.inventoryItems = items;
    this.hud.updateInventory(items);
  }

  private reportInventoryGains(
    prev: Record<string, number>,
    next: Record<string, number>
  ): void {
    for (const [id, count] of Object.entries(next)) {
      const delta = count - (prev[id] ?? 0);
      if (delta <= 0) continue;
      const name = getItemMeta(id).name;
      const suffix = delta > 1 ? ` ×${delta}` : '';
      this.pickupLog.push('item', `+ ${name}${suffix}`);
    }
  }

  private onMeso(p: Packet): void {
    const meso = Number(p.meso ?? 0);
    const gained = Number(p.gained ?? 0);
    this.hud.updateMeso(meso);
    if (gained > 0) {
      this.pickupLog.push('meso', `+${gained.toLocaleString('ko-KR')} 메소`);
    }
  }

  private onEquipment(p: Packet): void {
    const slots = (p.slots ?? {}) as Record<string, string>;
    this.equippedSlots = slots;
    this.hud.updateEquipment(slots);
  }

  private onStats(p: Packet): void {
    this.hud.updateStats({
      attack: p.attack as number,
      maxHp: p.maxHp as number,
      maxMp: p.maxMp as number,
      speed: p.speed as number,
      currentHp: p.currentHp as number,
      currentMp: p.currentMp as number,
      baseAttack: p.baseAttack as number,
      baseMaxHp: p.baseMaxHp as number,
      baseMaxMp: p.baseMaxMp as number,
      baseSpeed: p.baseSpeed as number
    });
  }

  private onPlayerDamaged(p: Packet): void {
    const playerId = p.playerId as number;
    const dmg = p.dmg as number;
    const currentHp = p.currentHp as number;
    const maxHp = p.maxHp as number;
    // 본인은 STATS 패킷이 별도로 오므로 바 갱신은 거기에 맡긴다. 시각 효과만.
    const x = playerId === this.myId
      ? this.player.x
      : this.remotes.get(playerId)?.sprite.x ?? 0;
    const y = playerId === this.myId
      ? this.player.y
      : this.remotes.get(playerId)?.sprite.y ?? 0;
    this.effects.spawnDamageNumber(x, y - 28, dmg);
    if (playerId === this.myId) {
      this.effects.flashSpriteDamage(this.player);
      this.hud.updateHpImmediate(currentHp, maxHp);
      this.applyPlayerKnockback(p.attackerId as number);
    }
  }

  /**
   * 공격자(음수면 몬스터) 위치를 기준으로 플레이어를 반대 방향으로 밀어낸다.
   * 서버는 넉백을 모르므로 다음 MOVE 패킷이 갱신된 좌표를 재동기화할 때까지
   * 시각 효과가 살짝 남아있을 수 있지만, iframe 구간 내에서 마무리된다.
   */
  private applyPlayerKnockback(attackerId: number): void {
    // attackerId 가 음수면 몬스터. 몬스터 id = -attackerId.
    const monsterId = attackerId < 0 ? -attackerId : attackerId;
    const monster = this.monsters.get(monsterId);
    const attackerX = monster ? monster.sprite.x : this.player.x;
    const dir = this.player.x >= attackerX ? 1 : -1;
    this.player.setVelocityX(dir * KNOCKBACK_SPEED_X);
    const body = this.player.body as Phaser.Physics.Arcade.Body;
    if (body.blocked.down) this.player.setVelocityY(KNOCKBACK_VELOCITY_Y);
    this.knockbackUntil = performance.now() + KNOCKBACK_DURATION_MS;
  }

  private onPlayerDied(p: Packet): void {
    const playerId = p.playerId as number;
    if (playerId === this.myId) {
      this.isDead = true;
      this.hud.showDeathOverlay();
      this.player.setTint(0x555555);
      this.player.setAlpha(0.5);
      // 정지 상태로 고정. 서버도 사망 중 MOVE 를 무시하므로 클라 예측도 멈춘다.
      this.player.setVelocity(0, 0);
    } else {
      const r = this.remotes.get(playerId);
      if (r) {
        r.sprite.setTint(0x555555);
        r.sprite.setAlpha(0.5);
      }
    }
  }

  private onPlayerRespawn(p: Packet): void {
    const playerId = p.playerId as number;
    const x = p.x as number;
    const y = p.y as number;
    if (playerId === this.myId) {
      this.isDead = false;
      this.hud.hideDeathOverlay();
      this.player.clearTint();
      this.player.setAlpha(1);
      this.player.setVelocity(0, 0);
      this.player.setPosition(x, y);
    } else {
      const r = this.remotes.get(playerId);
      if (r) {
        r.sprite.clearTint();
        r.sprite.setAlpha(1);
        r.setTarget(x, y);
      }
    }
  }

  /**
   * 서버 권위 좌표 보정. 클라가 보낸 MOVE 좌표가 서버 검증을 통과하지 못하면
   * 서버는 이전 좌표를 그대로 반환한다. 클라는 즉시 자신의 스프라이트를 재배치하고
   * 누적된 속도를 0 으로 폐기해 다음 입력부터 다시 시작한다.
   */
  private onPlayerCorrect(p: Packet): void {
    const x = p.x as number;
    const y = p.y as number;
    const reason = p.reason as string | undefined;
    console.warn('[PLAYER_CORRECT] 서버 좌표로 보정', { x, y, reason });
    this.player.setVelocity(0, 0);
    this.player.setPosition(x, y);
  }

  private onShopOpened(p: Packet): void {
    const shopId = p.shopId as string;
    const npcName = p.npcName as string;
    const items = (p.items ?? []) as { itemId: string; price: number; stockPerTransaction: number }[];
    const meso = p.meso as number;
    this.openShopId = shopId;
    this.hud.openShop(shopId, npcName, items, meso, (itemId, qty) => {
      // 구매 버튼 클릭 → 서버 요청. 응답은 onShopResult 에서.
      this.network.send({ type: 'SHOP_BUY', shopId, itemId, qty });
    });
  }

  private onShopResult(p: Packet): void {
    const ok = p.ok as boolean;
    const reason = p.reason as string | null;
    const mesoAfter = p.mesoAfter as number;
    this.hud.handleShopResult(ok, reason, mesoAfter);
    if (!ok && reason) this.pickupLog.push('item', `상점: ${reason}`);
  }

  private onSkillUsed(p: Packet): void {
    const playerId = p.playerId as number;
    const skillId = p.skillId as string;
    const dir = (p.dir as string) ?? 'right';
    const x = playerId === this.myId
      ? this.player.x
      : this.remotes.get(playerId)?.sprite.x ?? 0;
    const y = playerId === this.myId
      ? this.player.y
      : this.remotes.get(playerId)?.sprite.y ?? 0;
    if (skillId === 'basic_attack') {
      // 본인은 입력 즉시 슬래시를 이미 그렸으므로 중복 렌더 방지.
      // 다른 플레이어의 기본 공격은 같은 슬래시 이펙트로 시각화한다.
      if (playerId !== this.myId) {
        this.effects.spawnAttackSlash(x, y, dir === 'left' ? 'left' : 'right');
      }
      return;
    }
    this.effects.spawnSkillEffect(skillId, x, y, dir);
  }

  /** 인벤토리에서 장비로 보이는 첫 아이템 ID 반환(없으면 null). */
  private firstEquipmentInInventory(): string | null {
    for (const id of Object.keys(this.inventoryItems)) {
      if (EQUIPMENT_IDS.has(id) && (this.inventoryItems[id] ?? 0) > 0) return id;
    }
    return null;
  }

  override update(time: number, delta: number): void {
    // 채팅 입력 중 또는 사망 중에는 게임 입력을 전부 건너뛴다.
    // 좌우 속도도 잠가 두어 Q/E 등이 텍스트에 섞이거나, 사망 후 키 입력으로
    // 시체가 움직이는 현상을 막는다.
    if (this.chat.isFocused() || this.isDead) {
      this.player.setVelocityX(0);
      for (const r of this.remotes.values()) r.update(delta);
      for (const m of this.monsters.values()) m.update(delta);
      return;
    }

    this.handleMovement();
    this.handleActions();
    this.handleSkills(time);
    this.handlePortalOrJump();

    for (const r of this.remotes.values()) r.update(delta);
    for (const m of this.monsters.values()) m.update(delta);

    this.sendNetworkUpdates(time);
  }

  /** 좌/우 입력 → 속도와 facing 갱신. 넉백 구간에는 입력을 무시. */
  private handleMovement(): void {
    // 넉백 구간에는 입력으로 속도를 덮어쓰지 않고 관성·중력에 맡긴다.
    // (onPlayerDamaged 가 performance.now() 기준으로 knockbackUntil 을 세팅.)
    if (performance.now() < this.knockbackUntil) return;

    if (this.cursors.left.isDown) {
      this.player.setVelocityX(-MOVE_SPEED);
      this.facing = 'left';
      this.player.setFlipX(true);
    } else if (this.cursors.right.isDown) {
      this.player.setVelocityX(MOVE_SPEED);
      this.facing = 'right';
      this.player.setFlipX(false);
    } else {
      this.player.setVelocityX(0);
    }
  }

  /** Space/Q/E/I/Esc 등 1프레임 단발 액션 입력. */
  private handleActions(): void {
    if (Phaser.Input.Keyboard.JustDown(this.attackKey) && this.network.isOpen) {
      // 기본 공격도 스킬 시민. 서버는 USE_SKILL 한 경로로 모든 공격 행동을 검증·송출한다.
      // 클라 측 쿨다운 예측은 handleSkills 와 동일 맵을 공유해 일관되게 동작한다.
      const skillId = 'basic_attack';
      const time = this.time.now;
      const until = this.skillCooldownUntil.get(skillId) ?? 0;
      if (time >= until) {
        const meta = SKILL_META[skillId];
        if (meta) this.skillCooldownUntil.set(skillId, time + meta.cooldownMs);
        this.network.send({ type: 'USE_SKILL', skillId, dir: this.facing });
        this.effects.spawnAttackSlash(this.player.x, this.player.y, this.facing);
      }
    }

    // Q: 인벤토리 첫 장비 착용. E: 착용한 모든 슬롯 해제.
    if (Phaser.Input.Keyboard.JustDown(this.equipKey) && this.network.isOpen) {
      const id = this.firstEquipmentInInventory();
      if (id) this.network.send({ type: 'EQUIP', templateId: id });
    }
    if (Phaser.Input.Keyboard.JustDown(this.unequipKey) && this.network.isOpen) {
      for (const slot of Object.keys(this.equippedSlots)) {
        this.network.send({ type: 'UNEQUIP', slot });
      }
    }

    // I: 인벤토리 창 토글. S: 스탯창 토글. Esc: 열려 있는 패널을 닫기.
    if (Phaser.Input.Keyboard.JustDown(this.inventoryKey)) {
      this.hud.toggleInventory();
    }
    if (Phaser.Input.Keyboard.JustDown(this.statKey)) {
      this.hud.toggleStat();
    }
    if (Phaser.Input.Keyboard.JustDown(this.escKey)) {
      if (this.hud.isInventoryOpen()) this.hud.closeInventory();
      if (this.hud.isStatOpen()) this.hud.closeStat();
      if (this.openShopId) this.closeShop();
    }

    // F: 가까운 NPC 와 상호작용 — 첫 단계는 상점 NPC 만.
    if (Phaser.Input.Keyboard.JustDown(this.interactKey) && this.network.isOpen) {
      const npc = this.nearestNpc();
      if (npc) {
        this.openShopId = npc.shopId;
        this.network.send({ type: 'SHOP_OPEN', shopId: npc.shopId });
      }
    }

    // 매 프레임 가까운 NPC 의 「F: 대화」 힌트 표시.
    const near = this.nearestNpc();
    for (const n of this.npcs.values()) n.setNearby(n === near);
  }

  private closeShop(): void {
    this.openShopId = null;
    this.hud.closeShop();
  }

  /** 1/2/3 스킬 + HUD 쿨다운 갱신. 쿨다운 예측으로 중복 패킷 방지. */
  private handleSkills(time: number): void {
    for (const [skillId, key] of Object.entries(this.skillKeys)) {
      if (!Phaser.Input.Keyboard.JustDown(key) || !this.network.isOpen) continue;
      const until = this.skillCooldownUntil.get(skillId) ?? 0;
      if (time < until) continue;
      // META 패킷 도착 전 스킬 사용 시 쿨다운 예측은 건너뛴다. 서버가 권위적이므로
      // 패킷이 도착하기 전 눌러도 서버 쿨다운이 최종 결과를 보장한다.
      const meta = SKILL_META[skillId];
      if (meta) this.skillCooldownUntil.set(skillId, time + meta.cooldownMs);
      this.network.send({ type: 'USE_SKILL', skillId, dir: this.facing });
    }
    // 스킬 쿨다운 HUD 갱신(60Hz 갱신 부담 적음, 문자열 변환만).
    this.hud.updateSkillCooldowns(time, this.skillCooldownUntil);
  }

  /**
   * UP 키 이중 용도: 포털 위면 맵 이동(점프보다 우선), 아니면 바닥에 붙어 있을 때만 점프.
   * 이전 프레임 겹침 여부는 더 이상 기억할 필요가 없어 activePortalIndex 는 쓰지 않는다.
   */
  private handlePortalOrJump(): void {
    if (!Phaser.Input.Keyboard.JustDown(this.cursors.up)) return;
    const body = this.player.body as Phaser.Physics.Arcade.Body;
    const idx = this.mapController.findOverlappingPortalIndex();
    if (idx !== -1 && this.network.isOpen && this.myId !== -1) {
      const portal = this.mapController.portalAt(idx)!;
      this.network.send({
        type: 'CHANGE_MAP',
        targetMap: portal.targetMap,
        targetX: portal.targetX,
        targetY: portal.targetY
      });
    } else if (body.blocked.down) {
      this.player.setVelocityY(JUMP_VELOCITY);
    }
  }

  /** 주기적 MOVE 패킷 + 근접 드롭 자동 픽업 요청. */
  private sendNetworkUpdates(time: number): void {
    // 자동 픽업: 가까운 드롭 아이템이 있을 때 200ms 간격으로 서버에 요청
    if (time - this.lastPickupAttemptAt >= 200 && this.droppedItems.size > 0) {
      const px = this.player.x;
      const py = this.player.y;
      for (const it of this.droppedItems.values()) {
        const dx = it.body.x - px;
        const dy = it.body.y - py;
        if (Math.abs(dx) <= 36 && Math.abs(dy) <= 36) {
          this.lastPickupAttemptAt = time;
          this.network.send({ type: 'PICKUP' });
          break;
        }
      }
    }

    if (time - this.lastMoveSentAt >= MOVE_PACKET_INTERVAL_MS) {
      this.lastMoveSentAt = time;
      if (this.network.isOpen && this.myId !== -1) {
        const body = this.player.body as Phaser.Physics.Arcade.Body;
        this.network.send({
          type: 'MOVE',
          x: Math.round(this.player.x),
          y: Math.round(this.player.y),
          vx: body.velocity.x,
          vy: body.velocity.y
        });
      }
    }
  }

  private onPlayerExp(p: Packet): void {
    const level = p.level as number;
    const exp = p.exp as number;
    const toNext = p.toNextLevel as number;
    const gained = p.gained as number;
    this.hud.updateExp(level, exp, toNext);
    if (gained > 0) {
      // 플레이어 위의 플로팅 텍스트는 "타격 피드백" 역할로 유지하고,
      // 좌하단 로그에도 동일 내용을 기록해 지나가는 알림을 놓치지 않게 한다.
      this.effects.spawnExpGain(this.player.x, this.player.y, gained);
      this.pickupLog.push('exp', `+${gained.toLocaleString('ko-KR')} EXP`);
    }
  }

  private onPlayerLevelUp(p: Packet): void {
    const level = p.level as number;
    const id = p.playerId as number;
    const x = id === this.myId ? this.player.x : this.remotes.get(id)?.sprite.x ?? this.player.x;
    const y = id === this.myId ? this.player.y : this.remotes.get(id)?.sprite.y ?? this.player.y;
    this.effects.spawnLevelUp(x, y, level);
  }

  private onChat(p: Packet): void {
    this.chat.onReceive(p.scope as string, p.sender as string, p.message as string);
  }
}
