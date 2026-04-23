import Phaser from 'phaser';
import { SERVER_URL } from '../config';
import { WebSocketClient } from '../network/WebSocketClient';

const PLAYER_TEXTURE = 'player';
const GROUND_TEXTURE = 'ground';
const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;

/**
 * Phase A 의 메인 씬.
 *
 * - 중력 적용된 플레이어를 방향키로 이동/점프
 * - 일정 간격으로 서버에 MOVE 패킷 전송 (throttle)
 * - 서버가 보내는 ECHO/WELCOME 은 콘솔에 출력
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
    // Phase A: 외부 에셋 대신 프로그램적으로 텍스처를 생성한다.
    // 이후 Phase 에서 스프라이트 시트로 교체 예정.
    this.generateTextures();
  }

  create(): void {
    // 바닥
    const ground = this.physics.add.staticGroup();
    ground.create(400, 580, GROUND_TEXTURE).refreshBody();

    // 플레이어
    this.player = this.physics.add.sprite(100, 300, PLAYER_TEXTURE);
    this.player.setCollideWorldBounds(true);
    this.physics.add.collider(this.player, ground);

    // 입력
    this.cursors = this.input.keyboard!.createCursorKeys();

    // 네트워크
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

    // 서버에 위치 주기적 전송 (throttle)
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
   * Phase A 용 임시 텍스처: 플레이어는 노란 사각형, 바닥은 회색 막대.
   * 실제 스프라이트가 준비되면 제거한다.
   */
  private generateTextures(): void {
    const playerGfx = this.add.graphics();
    playerGfx.fillStyle(0xf1c40f, 1);
    playerGfx.fillRect(0, 0, 32, 48);
    playerGfx.generateTexture(PLAYER_TEXTURE, 32, 48);
    playerGfx.destroy();

    const groundGfx = this.add.graphics();
    groundGfx.fillStyle(0x555566, 1);
    groundGfx.fillRect(0, 0, 800, 40);
    groundGfx.generateTexture(GROUND_TEXTURE, 800, 40);
    groundGfx.destroy();
  }
}
