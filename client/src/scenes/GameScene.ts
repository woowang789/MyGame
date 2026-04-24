import Phaser from 'phaser';
import type { AuthedSession } from '../auth/LoginScreen';
import { DroppedItemSprite } from '../entities/DroppedItemSprite';
import { MonsterSprite } from '../entities/MonsterSprite';
import { RemotePlayer } from '../entities/RemotePlayer';
import type { WebSocketClient, Packet } from '../network/WebSocketClient';
import { EQUIPMENT_IDS, HudView, SKILL_META } from '../ui/HudView';
import { MapController } from './MapController';
import {
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
  private escKey!: Phaser.Input.Keyboard.Key;
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

  private currentMapId = 'henesys';
  private mapController!: MapController;

  constructor(network: WebSocketClient, session: AuthedSession) {
    super('GameScene');
    this.network = network;
    this.playerName = session.username;
  }

  preload(): void {
    for (const id of MAPS) {
      this.load.tilemapTiledJSON(id, `assets/${id}.json`);
    }
    generateTilesetTexture(this);
    generatePlayerTexture(this);
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
    this.escKey = this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.ESC);
    this.skillKeys = {
      power_strike: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.ONE),
      triple_blow: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.TWO),
      recovery: this.input.keyboard!.addKey(Phaser.Input.Keyboard.KeyCodes.THREE)
    };

    this.hud.setPlayerName(this.playerName);
    this.setupChatInput();
    document.getElementById('inv-close')?.addEventListener('click', () => this.hud.closeInventory());

    this.mapController = new MapController(this, this.player);
    this.mapController.loadMap(this.currentMapId);
    this.setupNetwork();
  }

  private setupNetwork(): void {
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
    this.network.on('ERROR', (p) => {
      const msg = String(p.message ?? '');
      console.warn('[Server ERROR]', msg);
      this.appendChatLog('sys', `[오류] ${msg}`);
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
    console.log(`[Game] 맵 전환: ${mapId} (${x},${y}), 기존 플레이어 ${others.length}명`);

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
    this.inventoryItems = items;
    this.hud.updateInventory(items);
  }

  private onMeso(p: Packet): void {
    const meso = Number(p.meso ?? 0);
    const gained = Number(p.gained ?? 0);
    this.hud.updateMeso(meso);
    if (gained > 0) {
      // 획득 시 채팅 로그에 시스템 메시지로 남겨 피드백을 준다.
      this.appendChatLog('sys', `+${gained.toLocaleString('ko-KR')} 메소 획득 (보유 ${meso.toLocaleString('ko-KR')})`);
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
      currentHp: p.currentHp as number,
      currentMp: p.currentMp as number
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
    this.spawnDamageNumber(x, y - 28, dmg);
    if (playerId === this.myId) {
      this.flashPlayerDamage();
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

  private flashPlayerDamage(): void {
    // 서버 IFRAME_MS(1500ms) 와 동기: 150ms × yoyo × repeat 4 = 1500ms 깜빡임.
    this.player.setTint(0xff4444);
    this.tweens.add({
      targets: this.player,
      alpha: { from: 0.3, to: 1 },
      duration: 150,
      yoyo: true,
      repeat: 4,
      onComplete: () => {
        if (!this.player.active) return;
        this.player.clearTint();
        this.player.setAlpha(1);
      }
    });
  }

  private spawnDamageNumber(x: number, y: number, dmg: number): void {
    const txt = this.add
      .text(x, y, `-${dmg}`, { fontSize: '14px', color: '#ff6b6b', fontStyle: 'bold' })
      .setOrigin(0.5, 1)
      .setStroke('#3a0a0a', 3);
    this.tweens.add({
      targets: txt,
      y: y - 24,
      alpha: 0,
      duration: 700,
      onComplete: () => txt.destroy()
    });
  }

  private onSkillUsed(p: Packet): void {
    const playerId = p.playerId as number;
    const skillId = p.skillId as string;
    // 시전자 좌표
    const x = playerId === this.myId
      ? this.player.x
      : this.remotes.get(playerId)?.sprite.x ?? 0;
    const y = playerId === this.myId
      ? this.player.y
      : this.remotes.get(playerId)?.sprite.y ?? 0;
    this.spawnSkillEffect(skillId, x, y, (p.dir as string) ?? 'right');
  }

  /** 스킬별 간단한 이펙트. 색상·크기로 구분. */
  private spawnSkillEffect(skillId: string, x: number, y: number, dir: string): void {
    const color = skillId === 'power_strike'
      ? 0xffb347
      : skillId === 'triple_blow'
      ? 0xff6bd6
      : 0x7bd4ff;
    const offset = dir === 'left' ? -36 : 36;
    if (skillId === 'recovery') {
      // 자기 회복: 플레이어 위에 상승 원형
      const fx = this.add.circle(x, y, 20, color, 0.6).setStrokeStyle(2, 0xffffff);
      this.tweens.add({
        targets: fx,
        y: y - 50,
        alpha: 0,
        scale: 1.6,
        duration: 600,
        onComplete: () => fx.destroy()
      });
      return;
    }
    const slash = this.add.rectangle(x + offset, y, 80, 32, color, 0.7).setStrokeStyle(2, 0xffffff);
    this.tweens.add({
      targets: slash,
      alpha: 0,
      scaleX: 1.6,
      scaleY: 1.2,
      duration: 220,
      onComplete: () => slash.destroy()
    });
  }

  /** 인벤토리에서 장비로 보이는 첫 아이템 ID 반환(없으면 null). */
  private firstEquipmentInInventory(): string | null {
    for (const id of Object.keys(this.inventoryItems)) {
      if (EQUIPMENT_IDS.has(id) && (this.inventoryItems[id] ?? 0) > 0) return id;
    }
    return null;
  }

  override update(time: number, delta: number): void {
    const body = this.player.body as Phaser.Physics.Arcade.Body;

    // 채팅 입력 중 또는 사망 중에는 게임 입력을 전부 건너뛴다.
    // 좌우 속도도 잠가 두어 Q/E 등이 텍스트에 섞이거나, 사망 후 키 입력으로
    // 시체가 움직이는 현상을 막는다.
    if (this.isChatFocused() || this.isDead) {
      this.player.setVelocityX(0);
      for (const r of this.remotes.values()) r.update(delta);
      for (const m of this.monsters.values()) m.update(delta);
      return;
    }

    // 넉백 구간에는 입력으로 속도를 덮어쓰지 않고 관성·중력에 맡긴다.
    // (onPlayerDamaged 가 performance.now() 기준으로 knockbackUntil 을 세팅.)
    const knockback = performance.now() < this.knockbackUntil;
    if (knockback) {
      // 방향 플립은 유지. 수평 속도는 onPlayerDamaged 에서 이미 설정된 값이 감쇠한다.
    } else if (this.cursors.left.isDown) {
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

    // 공격 (Space)
    if (Phaser.Input.Keyboard.JustDown(this.attackKey) && this.network.isOpen) {
      this.network.send({ type: 'ATTACK', dir: this.facing });
      this.spawnAttackEffect();
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

    // I: 인벤토리 창 토글. Esc: 열려 있으면 닫기.
    if (Phaser.Input.Keyboard.JustDown(this.inventoryKey)) {
      this.hud.toggleInventory();
    }
    if (Phaser.Input.Keyboard.JustDown(this.escKey) && this.hud.isInventoryOpen()) {
      this.hud.closeInventory();
    }

    // 1/2/3: 스킬 사용. 쿨다운 예측으로 중복 패킷 방지.
    for (const [skillId, key] of Object.entries(this.skillKeys)) {
      if (!Phaser.Input.Keyboard.JustDown(key) || !this.network.isOpen) continue;
      const until = this.skillCooldownUntil.get(skillId) ?? 0;
      if (time < until) continue;
      const meta = SKILL_META[skillId];
      this.skillCooldownUntil.set(skillId, time + meta.cooldownMs);
      this.network.send({ type: 'USE_SKILL', skillId, dir: this.facing });
    }

    // 스킬 쿨다운 HUD 갱신(60Hz 갱신 부담 적음, 문자열 변환만).
    this.hud.updateSkillCooldowns(time, this.skillCooldownUntil);

    // 포털 진입 / 점프: UP 키는 두 용도로 쓰인다.
    // 포털 위에서 UP 을 누르면 맵 이동(점프보다 우선), 아니면 평소처럼 점프.
    // 이전 프레임 겹침 여부는 더 이상 기억할 필요가 없어 activePortalIndex 는 쓰지 않는다.
    if (Phaser.Input.Keyboard.JustDown(this.cursors.up)) {
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

    for (const r of this.remotes.values()) r.update(delta);
    for (const m of this.monsters.values()) m.update(delta);

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
      const txt = this.add
        .text(this.player.x, this.player.y - 36, `+${gained} EXP`, {
          fontSize: '12px',
          color: '#aee1ff'
        })
        .setOrigin(0.5, 1);
      this.tweens.add({
        targets: txt,
        y: txt.y - 24,
        alpha: 0,
        duration: 800,
        onComplete: () => txt.destroy()
      });
    }
  }

  private onPlayerLevelUp(p: Packet): void {
    const level = p.level as number;
    const id = p.playerId as number;
    const x = id === this.myId ? this.player.x : this.remotes.get(id)?.sprite.x ?? this.player.x;
    const y = id === this.myId ? this.player.y : this.remotes.get(id)?.sprite.y ?? this.player.y;
    const txt = this.add
      .text(x, y - 50, `LEVEL UP! Lv ${level}`, {
        fontSize: '18px',
        color: '#ffd700',
        fontStyle: 'bold'
      })
      .setOrigin(0.5, 1)
      .setStroke('#6b3200', 3);
    this.tweens.add({
      targets: txt,
      y: txt.y - 40,
      alpha: 0,
      duration: 1400,
      onComplete: () => txt.destroy()
    });
  }

  private setupChatInput(): void {
    const input = document.getElementById('chat-input') as HTMLInputElement | null;
    if (!input) return;

    // 게임 중 Enter → 입력창 포커스. 입력창에서 Enter → 전송.
    // 입력 중에는 Phaser 가 키를 가로채지 않도록 disableGlobalCapture 사용.
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && document.activeElement !== input) {
        e.preventDefault();
        input.focus();
      }
    });

    // 채팅 입력 중 blur 트리거:
    //   Escape           → 취소 (입력값 폐기)
    //   Enter            → 전송 후 blur. 빈 값이면 그냥 blur.
    //   방향키(↑↓←→)    → blur 후 해당 키를 다음 프레임부터 게임 입력으로 쓸 수 있게.
    //                      preventDefault 하지 않아야 Phaser 쪽 keydown 이 정상 수신.
    const arrowKeys = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        input.blur();
        input.value = '';
      } else if (e.key === 'Enter') {
        const text = input.value.trim();
        input.value = '';
        input.blur();
        if (!text) return;
        this.sendChat(text);
      } else if (arrowKeys.has(e.key)) {
        input.blur();
      }
    });

    // 입력창 포커스 중에는 Phaser 키 캡처를 끄고, 벗어나면 켠다.
    // 추가로 blur 시 resetKeys() 를 호출해 입력창에서 눌렸던 Q/E 가 JustDown 으로
    // 흘러들어가지 않도록 내부 키 상태를 초기화한다.
    const box = document.getElementById('chat-box');
    input.addEventListener('focus', () => {
      this.input.keyboard?.disableGlobalCapture();
      box?.classList.add('expanded');
      box?.classList.remove('ghost');
      this.clearGhostTimer();
    });
    input.addEventListener('blur', () => {
      this.input.keyboard?.enableGlobalCapture();
      this.input.keyboard?.resetKeys();
      box?.classList.remove('expanded');
    });
  }

  /**
   * 새 메시지 도착 시 채팅 박스를 잠시 반투명 노출(.ghost). 포커스 중이면 건너뛴다.
   * 일정 시간 뒤 자동 제거. 포커스가 시작되면 타이머를 해제해 깜빡임을 막는다.
   */
  private ghostTimer: number | null = null;
  private showChatGhost(): void {
    const box = document.getElementById('chat-box');
    if (!box || box.classList.contains('expanded')) return;
    box.classList.add('ghost');
    this.clearGhostTimer();
    this.ghostTimer = window.setTimeout(() => {
      box.classList.remove('ghost');
      this.ghostTimer = null;
    }, 4000);
  }
  private clearGhostTimer(): void {
    if (this.ghostTimer !== null) {
      window.clearTimeout(this.ghostTimer);
      this.ghostTimer = null;
    }
  }

  /** 채팅 입력창이 포커스 상태인지. 게임 단축키(Q/E/숫자 등) 판정에서 제외하는 데 쓴다. */
  private isChatFocused(): boolean {
    return document.activeElement?.id === 'chat-input';
  }

  private sendChat(text: string): void {
    if (!this.network.isOpen) return;
    if (text.startsWith('/w ') || text.startsWith('/귓 ')) {
      const rest = text.slice(text.indexOf(' ') + 1).trim();
      const sp = rest.indexOf(' ');
      if (sp < 0) {
        this.appendChatLog('sys', '사용법: /w 닉네임 메시지');
        return;
      }
      const target = rest.slice(0, sp);
      const message = rest.slice(sp + 1);
      this.network.send({ type: 'CHAT', scope: 'WHISPER', target, message });
    } else {
      this.network.send({ type: 'CHAT', scope: 'ALL', target: '', message: text });
    }
  }

  private onChat(p: Packet): void {
    const scope = p.scope as string;
    const sender = p.sender as string;
    const msg = p.message as string;
    if (scope.startsWith('WHISPER:')) {
      const partner = scope.slice('WHISPER:'.length);
      this.appendChatLog('whisper', `[귓] ${sender} → ${partner}: ${msg}`);
    } else {
      this.appendChatLog('all', `${sender}: ${msg}`);
    }
  }

  private appendChatLog(kind: 'sys' | 'all' | 'whisper', line: string): void {
    const log = document.getElementById('chat-log');
    if (!log) return;
    const div = document.createElement('div');
    div.className = kind;
    div.textContent = line;
    log.appendChild(div);
    // 최근 80줄만 유지
    while (log.children.length > 80) log.removeChild(log.firstChild!);
    log.scrollTop = log.scrollHeight;
    // 축소 상태였으면 ghost 로 잠시 드러낸다.
    this.showChatGhost();
  }

  private spawnAttackEffect(): void {
    const offsetX = this.facing === 'right' ? 28 : -28;
    const slash = this.add
      .rectangle(this.player.x + offsetX, this.player.y, 40, 20, 0xffffff, 0.7)
      .setStrokeStyle(2, 0xffeb8a);
    this.tweens.add({
      targets: slash,
      alpha: 0,
      scaleX: 1.3,
      duration: 160,
      onComplete: () => slash.destroy()
    });
  }

}
