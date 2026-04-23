import Phaser from 'phaser';

const MONSTER_TEXTURE = 'monster';

/**
 * 서버 권위 몬스터의 클라이언트 표현.
 *
 * - 좌표는 서버에서 받아 선형 보간.
 * - 가로 속도(vx) 방향에 따라 스프라이트 flip.
 */
export class MonsterSprite {
  readonly sprite: Phaser.GameObjects.Sprite;
  private targetX: number;
  private readonly y: number;

  constructor(scene: Phaser.Scene, readonly id: number, x: number, y: number) {
    this.sprite = scene.add.sprite(x, y, MONSTER_TEXTURE).setOrigin(0.5, 1);
    this.targetX = x;
    this.y = y;
  }

  setTarget(x: number, vx: number): void {
    this.targetX = x;
    if (vx < 0) this.sprite.setFlipX(true);
    else if (vx > 0) this.sprite.setFlipX(false);
  }

  update(dt: number): void {
    const t = Math.min(1, dt / 180);
    this.sprite.x += (this.targetX - this.sprite.x) * t;
    this.sprite.y = this.y;
  }

  destroy(): void {
    this.sprite.destroy();
  }

  static generateTexture(scene: Phaser.Scene): void {
    if (scene.textures.exists(MONSTER_TEXTURE)) return;
    const gfx = scene.add.graphics();
    // 달팽이 몸통 (연두)
    gfx.fillStyle(0x7cd36a, 1);
    gfx.fillEllipse(16, 14, 30, 16);
    // 껍질 (갈색 원)
    gfx.fillStyle(0xb36836, 1);
    gfx.fillCircle(22, 10, 9);
    // 껍질 나선
    gfx.fillStyle(0x7a431f, 1);
    gfx.fillCircle(22, 10, 5);
    // 눈
    gfx.fillStyle(0x000000, 1);
    gfx.fillRect(6, 8, 2, 2);
    scene.textures.remove(MONSTER_TEXTURE);
    gfx.generateTexture(MONSTER_TEXTURE, 32, 22);
    gfx.destroy();
  }
}
