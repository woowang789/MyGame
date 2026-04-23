import Phaser from 'phaser';

const MONSTER_TEXTURE = 'monster';
const HP_BAR_WIDTH = 36;
const HP_BAR_HEIGHT = 4;

/**
 * 서버 권위 몬스터의 클라이언트 표현. HP 바 + 피격 플래시 포함.
 */
export class MonsterSprite {
  readonly sprite: Phaser.GameObjects.Sprite;
  private readonly hpBg: Phaser.GameObjects.Rectangle;
  private readonly hpFill: Phaser.GameObjects.Rectangle;
  private targetX: number;
  private readonly y: number;
  private hp: number;
  private readonly maxHp: number;

  constructor(
    scene: Phaser.Scene,
    readonly id: number,
    x: number,
    y: number,
    hp: number,
    maxHp: number
  ) {
    this.sprite = scene.add.sprite(x, y, MONSTER_TEXTURE).setOrigin(0.5, 1);
    this.targetX = x;
    this.y = y;
    this.hp = hp;
    this.maxHp = maxHp;

    this.hpBg = scene.add.rectangle(x, y - 26, HP_BAR_WIDTH, HP_BAR_HEIGHT, 0x000000, 0.6);
    this.hpFill = scene.add
      .rectangle(x, y - 26, HP_BAR_WIDTH, HP_BAR_HEIGHT, 0xff5c5c)
      .setOrigin(0, 0.5);
    this.hpFill.x = x - HP_BAR_WIDTH / 2;
    this.refreshHpBar();
  }

  setTarget(x: number, vx: number): void {
    this.targetX = x;
    if (vx < 0) this.sprite.setFlipX(true);
    else if (vx > 0) this.sprite.setFlipX(false);
  }

  applyDamage(newHp: number, scene: Phaser.Scene, damage: number): void {
    this.hp = newHp;
    this.refreshHpBar();

    // 피격 플래시
    this.sprite.setTintFill(0xffffff);
    scene.time.delayedCall(80, () => this.sprite.clearTint());

    // 데미지 숫자 팝업
    const txt = scene.add
      .text(this.sprite.x, this.sprite.y - 30, String(damage), {
        fontSize: '14px',
        color: '#ffeb3b',
        fontStyle: 'bold'
      })
      .setOrigin(0.5, 1);
    scene.tweens.add({
      targets: txt,
      y: txt.y - 26,
      alpha: 0,
      duration: 600,
      onComplete: () => txt.destroy()
    });
  }

  update(dt: number): void {
    const t = Math.min(1, dt / 180);
    this.sprite.x += (this.targetX - this.sprite.x) * t;
    this.sprite.y = this.y;
    this.hpBg.setPosition(this.sprite.x, this.sprite.y - 26);
    this.hpFill.x = this.sprite.x - HP_BAR_WIDTH / 2;
    this.hpFill.y = this.sprite.y - 26;
  }

  private refreshHpBar(): void {
    const ratio = Math.max(0, Math.min(1, this.hp / this.maxHp));
    this.hpFill.width = HP_BAR_WIDTH * ratio;
    this.hpFill.displayWidth = HP_BAR_WIDTH * ratio;
  }

  destroy(): void {
    this.sprite.destroy();
    this.hpBg.destroy();
    this.hpFill.destroy();
  }

  static generateTexture(scene: Phaser.Scene): void {
    if (scene.textures.exists(MONSTER_TEXTURE)) return;
    const gfx = scene.add.graphics();
    gfx.fillStyle(0x7cd36a, 1);
    gfx.fillEllipse(16, 14, 30, 16);
    gfx.fillStyle(0xb36836, 1);
    gfx.fillCircle(22, 10, 9);
    gfx.fillStyle(0x7a431f, 1);
    gfx.fillCircle(22, 10, 5);
    gfx.fillStyle(0x000000, 1);
    gfx.fillRect(6, 8, 2, 2);
    gfx.generateTexture(MONSTER_TEXTURE, 32, 22);
    gfx.destroy();
  }
}
