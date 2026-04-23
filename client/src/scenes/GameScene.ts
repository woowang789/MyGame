import Phaser from 'phaser';
import { SERVER_URL } from '../config';
import { WebSocketClient } from '../network/WebSocketClient';

const MAP_KEY = 'henesys';
const TILESET_NAME = 'tiles'; // Tiled 맵 JSON 의 tileset 이름과 일치해야 함
const TILESET_TEXTURE = 'tiles-tex';
const PLAYER_TEXTURE = 'player';

const TILE_SIZE = 20;
const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;

/**
 * Phase B — Tiled 타일맵 기반 스테이지.
 *
 * 변경점:
 * - 프로그램적 사각형 대신 Tiled JSON 타일맵을 로드
 * - 타일에 `collides` 프로퍼티로 충돌 여부 지정 → 플랫폼 구현
 * - 타일셋 이미지는 런타임에 Canvas 에서 생성 (외부 에셋 제로 의존성)
 *
 * 패킷 송수신 로직은 Phase A 와 동일.
 */
export class GameScene extends Phaser.Scene {
  private player!: Phaser.Physics.Arcade.Sprite;
  private cursors!: Phaser.Types.Input.Keyboard.CursorKeys;
  private network = new WebSocketClient(SERVER_URL);
  private lastMoveSentAt = 0;

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
    if (!tileset) {
      throw new Error(`타일셋 로드 실패: ${TILESET_NAME}`);
    }

    const groundLayer = map.createLayer('Ground', tileset, 0, 0);
    if (!groundLayer) {
      throw new Error('Ground 레이어를 찾을 수 없습니다.');
    }
    groundLayer.setCollisionByProperty({ collides: true });

    // 월드 크기 = 맵 크기
    const worldW = map.widthInPixels;
    const worldH = map.heightInPixels;
    this.physics.world.setBounds(0, 0, worldW, worldH);
    this.cameras.main.setBounds(0, 0, worldW, worldH);

    // 플레이어
    this.player = this.physics.add.sprite(80, 100, PLAYER_TEXTURE);
    this.player.setCollideWorldBounds(true);
    this.physics.add.collider(this.player, groundLayer);

    // 카메라 추적
    this.cameras.main.startFollow(this.player, true, 0.1, 0.1);
    this.cameras.main.setDeadzone(200, 120);

    this.cursors = this.input.keyboard!.createCursorKeys();

    this.network.connect();
    this.network.on('WELCOME', (p) => {
      console.log(`[Game] 세션 시작: id=${p.sessionId}`);
    });
  }

  override update(time: number): void {
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

    if (time - this.lastMoveSentAt >= MOVE_PACKET_INTERVAL_MS) {
      this.lastMoveSentAt = time;
      if (this.network.isOpen) {
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

  /**
   * 4-타일 타일셋 텍스처 생성.
   *   id 1: 흙 블록 (상단 풀)  — 바닥 표면
   *   id 2: 흙 블록            — 바닥 내부
   *   id 3: 플랫폼 (충돌 on)
   *   id 4: 장식용 (충돌 off — 현재 미사용)
   * 순서는 Tiled JSON 의 `firstgid=1` 기준.
   */
  private generateTilesetTexture(): void {
    const cols = 4;
    const w = TILE_SIZE * cols;
    const h = TILE_SIZE;
    const canvas = this.textures.createCanvas(TILESET_TEXTURE, w, h);
    if (!canvas) return;

    const ctx = canvas.getContext();

    // id 1 — 바닥 상단 (풀+흙)
    ctx.fillStyle = '#6b4a2b';
    ctx.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#4caf50';
    ctx.fillRect(0, 0, TILE_SIZE, 5);

    // id 2 — 플랫폼 (공중 블록)
    ctx.fillStyle = '#8e6b3d';
    ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#a37d47';
    ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, 4);
    ctx.strokeStyle = '#5a4327';
    ctx.strokeRect(TILE_SIZE + 0.5, 0.5, TILE_SIZE - 1, TILE_SIZE - 1);

    // id 3 — 바닥 내부 (어두운 흙)
    ctx.fillStyle = '#3e2b17';
    ctx.fillRect(TILE_SIZE * 2, 0, TILE_SIZE, TILE_SIZE);
    ctx.fillStyle = '#2a1d0f';
    for (let i = 0; i < 4; i++) {
      ctx.fillRect(TILE_SIZE * 2 + (i * 5), i * 5, 3, 3);
    }

    // id 4 — 장식 (현재 미사용, 자리 표시)
    ctx.fillStyle = '#555566';
    ctx.fillRect(TILE_SIZE * 3, 0, TILE_SIZE, TILE_SIZE);

    canvas.refresh();
  }

  private generatePlayerTexture(): void {
    const gfx = this.add.graphics();
    gfx.fillStyle(0xf1c40f, 1);
    gfx.fillRect(0, 0, 20, 32);
    // 간단한 눈
    gfx.fillStyle(0x333333, 1);
    gfx.fillRect(13, 8, 3, 3);
    gfx.generateTexture(PLAYER_TEXTURE, 20, 32);
    gfx.destroy();
  }
}
