import Phaser from 'phaser';
import { SERVER_URL } from '../config';
import { RemotePlayer } from '../entities/RemotePlayer';
import { WebSocketClient, type Packet } from '../network/WebSocketClient';

const MAP_KEY = 'henesys';
const TILESET_NAME = 'tiles';
const TILESET_TEXTURE = 'tiles-tex';
const PLAYER_TEXTURE = 'player';

const TILE_SIZE = 20;
const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;

interface PlayerStateMsg {
  id: number;
  name: string;
  x: number;
  y: number;
}

/**
 * Phase C — 멀티플레이어 위치 동기화.
 *
 * 흐름:
 *  1) 접속 → 즉시 JOIN 패킷 송신
 *  2) WELCOME 수신 → 본인 id 설정 + 기존 타 플레이어 스폰
 *  3) PLAYER_JOIN / PLAYER_MOVE / PLAYER_LEAVE 로 실시간 동기화
 */
export class GameScene extends Phaser.Scene {
  private player!: Phaser.Physics.Arcade.Sprite;
  private cursors!: Phaser.Types.Input.Keyboard.CursorKeys;
  private network = new WebSocketClient(SERVER_URL);
  private lastMoveSentAt = 0;
  private myId = -1;
  private readonly remotes = new Map<number, RemotePlayer>();
  private readonly playerName = this.generatePlayerName();

  constructor() {
    super('GameScene');
  }

  preload(): void {
    this.load.tilemapTiledJSON(MAP_KEY, 'assets/henesys.json');
    this.generateTilesetTexture();
    this.generatePlayerTexture();
  }

  create(): void {
    const map = this.make.tilemap({ key: MAP_KEY });
    const tileset = map.addTilesetImage(TILESET_NAME, TILESET_TEXTURE, TILE_SIZE, TILE_SIZE);
    if (!tileset) throw new Error(`타일셋 로드 실패: ${TILESET_NAME}`);

    const groundLayer = map.createLayer('Ground', tileset, 0, 0);
    if (!groundLayer) throw new Error('Ground 레이어를 찾을 수 없습니다.');
    groundLayer.setCollisionByProperty({ collides: true });

    this.physics.world.setBounds(0, 0, map.widthInPixels, map.heightInPixels);
    this.cameras.main.setBounds(0, 0, map.widthInPixels, map.heightInPixels);

    this.player = this.physics.add.sprite(80, 100, PLAYER_TEXTURE);
    this.player.setCollideWorldBounds(true);
    this.physics.add.collider(this.player, groundLayer);
    this.cameras.main.startFollow(this.player, true, 0.1, 0.1);
    this.cameras.main.setDeadzone(200, 120);

    // 내 이름표
    const myLabel = this.add
      .text(this.player.x, this.player.y - 28, this.playerName, {
        fontSize: '11px',
        color: '#ffeb8a'
      })
      .setOrigin(0.5, 1);
    // 매 프레임 플레이어 머리 위로 따라가게
    this.events.on(Phaser.Scenes.Events.POST_UPDATE, () => {
      myLabel.setPosition(this.player.x, this.player.y - this.player.displayHeight / 2 - 4);
    });

    this.cursors = this.input.keyboard!.createCursorKeys();

    this.setupNetwork();
  }

  private setupNetwork(): void {
    this.network.on('WELCOME', (p) => this.onWelcome(p));
    this.network.on('PLAYER_JOIN', (p) => this.onPlayerJoin(p));
    this.network.on('PLAYER_MOVE', (p) => this.onPlayerMove(p));
    this.network.on('PLAYER_LEAVE', (p) => this.onPlayerLeave(p));
    this.network.on('ERROR', (p) => console.warn('[Server ERROR]', p.message));

    // 연결이 열리면 JOIN 송신
    this.network.onOpen(() => {
      this.network.send({ type: 'JOIN', name: this.playerName });
    });
    this.network.connect();
  }

  private onWelcome(p: Packet): void {
    this.myId = p.playerId as number;
    const others = (p.others ?? []) as PlayerStateMsg[];
    console.log(`[Game] WELCOME id=${this.myId}, 기존 플레이어 ${others.length}명`);
    for (const o of others) {
      this.spawnRemote(o);
    }
  }

  private onPlayerJoin(p: Packet): void {
    const ps = p.player as PlayerStateMsg;
    if (ps.id === this.myId) return;
    console.log(`[Game] 입장: ${ps.name} (id=${ps.id})`);
    this.spawnRemote(ps);
  }

  private onPlayerMove(p: Packet): void {
    const id = p.id as number;
    if (id === this.myId) return;
    const remote = this.remotes.get(id);
    if (!remote) return;
    remote.setTarget(p.x as number, p.y as number);
  }

  private onPlayerLeave(p: Packet): void {
    const id = p.id as number;
    const remote = this.remotes.get(id);
    if (!remote) return;
    console.log(`[Game] 퇴장: id=${id}`);
    remote.destroy();
    this.remotes.delete(id);
  }

  private spawnRemote(ps: PlayerStateMsg): void {
    if (this.remotes.has(ps.id)) return;
    const r = new RemotePlayer(this, ps.id, ps.name, ps.x, ps.y, PLAYER_TEXTURE);
    this.remotes.set(ps.id, r);
  }

  override update(time: number, delta: number): void {
    const body = this.player.body as Phaser.Physics.Arcade.Body;

    if (this.cursors.left.isDown) {
      this.player.setVelocityX(-MOVE_SPEED);
    } else if (this.cursors.right.isDown) {
      this.player.setVelocityX(MOVE_SPEED);
    } else {
      this.player.setVelocityX(0);
    }

    if (this.cursors.up.isDown && body.blocked.down) {
      this.player.setVelocityY(JUMP_VELOCITY);
    }

    // 원격 플레이어 보간
    for (const r of this.remotes.values()) {
      r.update(delta);
    }

    // 내 위치 서버로
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

  private generatePlayerName(): string {
    return 'User-' + Math.floor(Math.random() * 9000 + 1000);
  }

  private generateTilesetTexture(): void {
    const cols = 4;
    const w = TILE_SIZE * cols;
    const h = TILE_SIZE;
    const canvas = this.textures.createCanvas(TILESET_TEXTURE, w, h);
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
