import Phaser from 'phaser';
import { SERVER_URL } from '../config';
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
    this.network.on('ERROR', (p) => console.warn('[Server ERROR]', p.message));
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
