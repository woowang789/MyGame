import Phaser from 'phaser';

/**
 * 다른 플레이어(원격) 표현.
 *
 * - 로컬 예측/검증을 하지 않고 서버가 보낸 좌표를 보간(lerp)으로 따라간다.
 * - 이름표를 머리 위에 띄워 누구인지 구분.
 */
export class RemotePlayer {
  readonly sprite: Phaser.GameObjects.Sprite;
  private readonly nameLabel: Phaser.GameObjects.Text;
  private targetX: number;
  private targetY: number;

  constructor(
    scene: Phaser.Scene,
    readonly id: number,
    readonly name: string,
    x: number,
    y: number,
    textureKey: string
  ) {
    this.sprite = scene.add.sprite(x, y, textureKey).setTint(0x5ec3ff);
    this.nameLabel = scene.add
      .text(x, y - 28, name, { fontSize: '11px', color: '#ffffff' })
      .setOrigin(0.5, 1);
    this.targetX = x;
    this.targetY = y;
  }

  setTarget(x: number, y: number): void {
    this.targetX = x;
    this.targetY = y;
  }

  /** 매 프레임 호출. 서버 좌표로 부드럽게 접근. */
  update(dt: number): void {
    // 간이 선형 보간. 프레임 독립적이도록 시간 스케일 반영.
    const t = Math.min(1, dt / 120);
    this.sprite.x += (this.targetX - this.sprite.x) * t;
    this.sprite.y += (this.targetY - this.sprite.y) * t;
    this.nameLabel.setPosition(this.sprite.x, this.sprite.y - this.sprite.displayHeight / 2 - 4);
  }

  destroy(): void {
    this.sprite.destroy();
    this.nameLabel.destroy();
  }
}
