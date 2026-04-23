import Phaser from 'phaser';
import { SERVER_URL } from '../config';
import { DroppedItemSprite, ITEM_NAMES } from '../entities/DroppedItemSprite';
import { MonsterSprite } from '../entities/MonsterSprite';
import { RemotePlayer } from '../entities/RemotePlayer';
import { WebSocketClient, type Packet } from '../network/WebSocketClient';

const TILESET_NAME = 'tiles';
const TILESET_TEXTURE = 'tiles-tex';
const PLAYER_TEXTURE = 'player';

const TILE_SIZE = 20;
const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;

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

interface PortalDef {
  zone: Phaser.GameObjects.Zone;
  targetMap: string;
  targetX: number;
  targetY: number;
  label: Phaser.GameObjects.Text;
  visual: Phaser.GameObjects.Rectangle;
}

/**
 * Phase D — 다중 맵 + 포털 전환.
 */
export class GameScene extends Phaser.Scene {
  private player!: Phaser.Physics.Arcade.Sprite;
  private cursors!: Phaser.Types.Input.Keyboard.CursorKeys;
  private attackKey!: Phaser.Input.Keyboard.Key;
  private myLabel!: Phaser.GameObjects.Text;
  private facing: 'left' | 'right' = 'right';
  private statusLabel!: Phaser.GameObjects.Text;
  private expBarFill!: Phaser.GameObjects.Rectangle;
  private network = new WebSocketClient(SERVER_URL);
  private lastMoveSentAt = 0;
  private myId = -1;
  private readonly playerName = this.generatePlayerName();
  private readonly remotes = new Map<number, RemotePlayer>();
  private readonly monsters = new Map<number, MonsterSprite>();
  private readonly droppedItems = new Map<number, DroppedItemSprite>();
  private lastPickupAttemptAt = 0;
  private invLabel!: Phaser.GameObjects.Text;

  private currentMapId = 'henesys';
  private tilemap: Phaser.Tilemaps.Tilemap | null = null;
  private portals: PortalDef[] = [];
  // 현재 겹쳐 있는 포털의 인덱스. 같은 포털에서 벗어나기 전까지는 재진입하지 않는다.
  // 맵 전환 직후 도착 지점이 복귀 포털 위라도 "이미 겹친 상태"로 취급되어 바로 되돌아가지 않게 한다.
  private activePortalIndex = -1;

  constructor() {
    super('GameScene');
  }

  preload(): void {
    for (const id of MAPS) {
      this.load.tilemapTiledJSON(id, `assets/${id}.json`);
    }
    this.generateTilesetTexture();
    this.generatePlayerTexture();
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

    this.createHud();
    this.setupChatInput();

    this.loadMap(this.currentMapId);
    this.setupNetwork();
  }

  private loadMap(mapId: string): void {
    // 기존 맵 정리
    this.tilemap?.destroy();
    for (const p of this.portals) {
      p.zone.destroy();
      p.label.destroy();
      p.visual.destroy();
    }
    this.portals = [];

    const map = this.make.tilemap({ key: mapId });
    const tileset = map.addTilesetImage(TILESET_NAME, TILESET_TEXTURE, TILE_SIZE, TILE_SIZE);
    if (!tileset) throw new Error(`타일셋 로드 실패: ${TILESET_NAME}`);

    const ground = map.createLayer('Ground', tileset, 0, 0);
    if (!ground) throw new Error(`Ground 레이어를 찾을 수 없습니다. map=${mapId}`);
    ground.setCollisionByProperty({ collides: true });

    this.physics.world.setBounds(0, 0, map.widthInPixels, map.heightInPixels);
    this.cameras.main.setBounds(0, 0, map.widthInPixels, map.heightInPixels);
    this.physics.add.collider(this.player, ground);

    // 포털 로드
    const portalLayer = map.getObjectLayer('Portals');
    if (portalLayer) {
      for (const obj of portalLayer.objects) {
        const x = obj.x ?? 0;
        const y = obj.y ?? 0;
        const w = obj.width ?? TILE_SIZE;
        const h = obj.height ?? TILE_SIZE;
        const props = this.propsToMap(obj.properties);
        const targetMap = String(props.targetMap ?? '');
        const targetX = Number(props.targetX ?? 0);
        const targetY = Number(props.targetY ?? 0);
        if (!targetMap) continue;

        const zone = this.add.zone(x + w / 2, y + h / 2, w, h);
        const visual = this.add
          .rectangle(x + w / 2, y + h / 2, w, h, 0x7fd3ff, 0.25)
          .setStrokeStyle(2, 0x7fd3ff);
        const label = this.add
          .text(x + w / 2, y - 4, `▲ ${targetMap}`, { fontSize: '10px', color: '#7fd3ff' })
          .setOrigin(0.5, 1);
        this.portals.push({ zone, targetMap, targetX, targetY, label, visual });
      }
    }

    this.tilemap = map;
  }

  private propsToMap(
    props: unknown
  ): Record<string, string | number | boolean> {
    const out: Record<string, string | number | boolean> = {};
    if (!Array.isArray(props)) return out;
    for (const p of props as Array<{ name: string; value: string | number | boolean }>) {
      out[p.name] = p.value;
    }
    return out;
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
    this.network.on('CHAT', (p) => this.onChat(p));
    this.network.on('ERROR', (p) => {
      console.warn('[Server ERROR]', p.message);
      this.appendChatLog('sys', `[오류] ${p.message}`);
    });
    this.network.onOpen(() => {
      this.network.send({ type: 'JOIN', name: this.playerName });
    });
    this.network.connect();
  }

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
    this.loadMap(mapId);
    this.player.setVelocity(0, 0);
    this.player.setPosition(x, y);

    for (const o of others) this.spawnRemote(o);
    const ms = (p.monsters ?? []) as MonsterStateMsg[];
    for (const m of ms) this.spawnMonster(m);
    for (const d of this.droppedItems.values()) d.destroy();
    this.droppedItems.clear();
    const items = (p.items ?? []) as DroppedItemMsg[];
    for (const it of items) this.spawnItem(it);
    // 도착 지점이 복귀 포털과 겹칠 수 있으므로, 방금 전환했다면 현재 겹친 포털을
    // "이미 활성"으로 간주해 즉시 재발동하지 않도록 한다.
    this.activePortalIndex = this.findOverlappingPortalIndex();
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
    this.monsters.set(ms.id, new MonsterSprite(this, ms.id, ms.x, ms.y, ms.hp, ms.maxHp));
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
    const lines = Object.entries(items)
      .map(([k, v]) => `${ITEM_NAMES[k] ?? k}: ${v}`)
      .join('\n');
    this.invLabel.setText(lines || '(비어 있음)');
  }

  override update(time: number, delta: number): void {
    const body = this.player.body as Phaser.Physics.Arcade.Body;

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

    // 공격 (Space)
    if (Phaser.Input.Keyboard.JustDown(this.attackKey) && this.network.isOpen) {
      this.network.send({ type: 'ATTACK', dir: this.facing });
      this.spawnAttackEffect();
    }

    // 점프
    if (Phaser.Input.Keyboard.JustDown(this.cursors.up) && body.blocked.down) {
      this.player.setVelocityY(JUMP_VELOCITY);
    }

    // 포털 자동 진입: 이전 프레임엔 겹치지 않았던 포털과 새로 겹치는 순간에만 트리거.
    const idx = this.findOverlappingPortalIndex();
    if (idx !== this.activePortalIndex) {
      this.activePortalIndex = idx;
      if (idx !== -1 && this.network.isOpen && this.myId !== -1) {
        const portal = this.portals[idx];
        this.network.send({
          type: 'CHANGE_MAP',
          targetMap: portal.targetMap,
          targetX: portal.targetX,
          targetY: portal.targetY
        });
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

  private findOverlappingPortalIndex(): number {
    const playerRect = this.player.getBounds();
    for (let i = 0; i < this.portals.length; i++) {
      if (Phaser.Geom.Rectangle.Overlaps(this.portals[i].zone.getBounds(), playerRect)) return i;
    }
    return -1;
  }

  private createHud(): void {
    // 화면 좌상단 고정 HUD (카메라 스크롤 영향 X)
    this.statusLabel = this.add
      .text(12, 12, 'Lv 1  EXP 0 / 50', {
        fontSize: '13px',
        color: '#ffeb8a',
        fontStyle: 'bold'
      })
      .setScrollFactor(0)
      .setDepth(1000);
    this.add
      .rectangle(12, 34, 180, 8, 0x000000, 0.6)
      .setOrigin(0, 0.5)
      .setScrollFactor(0)
      .setDepth(1000);
    this.expBarFill = this.add
      .rectangle(12, 34, 0, 8, 0xffc64a)
      .setOrigin(0, 0.5)
      .setScrollFactor(0)
      .setDepth(1001);

    // 인벤토리 HUD (우상단)
    this.add
      .rectangle(608, 12, 180, 110, 0x000000, 0.55)
      .setOrigin(0, 0)
      .setScrollFactor(0)
      .setDepth(1000);
    this.add
      .text(618, 18, '인벤토리', { fontSize: '12px', color: '#aee1ff', fontStyle: 'bold' })
      .setScrollFactor(0)
      .setDepth(1001);
    this.invLabel = this.add
      .text(618, 38, '(비어 있음)', { fontSize: '12px', color: '#ffffff', lineSpacing: 2 })
      .setScrollFactor(0)
      .setDepth(1001);
  }

  private onPlayerExp(p: Packet): void {
    const level = p.level as number;
    const exp = p.exp as number;
    const toNext = p.toNextLevel as number;
    const gained = p.gained as number;
    this.statusLabel.setText(`Lv ${level}  EXP ${exp} / ${toNext}`);
    this.expBarFill.displayWidth = 180 * Math.max(0, Math.min(1, exp / toNext));
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
      }
    });

    // 입력창 포커스 중에는 방향키·Space 이벤트가 Phaser 로 가지 않게 차단.
    input.addEventListener('focus', () => this.input.keyboard?.disableGlobalCapture());
    input.addEventListener('blur', () => this.input.keyboard?.enableGlobalCapture());
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

  private generatePlayerName(): string {
    return 'User-' + Math.floor(Math.random() * 9000 + 1000);
  }

  private generateTilesetTexture(): void {
    const cols = 4;
    const canvas = this.textures.createCanvas(TILESET_TEXTURE, TILE_SIZE * cols, TILE_SIZE);
    if (!canvas) return;
    const ctx = canvas.getContext();

    ctx.fillStyle = '#6b4a2b';
    ctx.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#4caf50';
    ctx.fillRect(0, 0, TILE_SIZE, 5);

    ctx.fillStyle = '#8e6b3d';
    ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#a37d47';
    ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, 4);
    ctx.strokeStyle = '#5a4327';
    ctx.strokeRect(TILE_SIZE + 0.5, 0.5, TILE_SIZE - 1, TILE_SIZE - 1);

    ctx.fillStyle = '#3e2b17';
    ctx.fillRect(TILE_SIZE * 2, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#2a1d0f';
    for (let i = 0; i < 4; i++) {
      ctx.fillRect(TILE_SIZE * 2 + i * 5, i * 5, 3, 3);
    }

    ctx.fillStyle = '#555566';
    ctx.fillRect(TILE_SIZE * 3, 0, TILE_SIZE, TILE_SIZE);

    canvas.refresh();
  }

  private generatePlayerTexture(): void {
    const gfx = this.add.graphics();
    gfx.fillStyle(0xf1c40f, 1);
    gfx.fillRect(0, 0, 20, 32);
    gfx.fillStyle(0x333333, 1);
    gfx.fillRect(13, 8, 3, 3);
    gfx.generateTexture(PLAYER_TEXTURE, 20, 32);
    gfx.destroy();
  }
}
