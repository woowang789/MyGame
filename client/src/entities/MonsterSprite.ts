import Phaser from 'phaser';

const HP_BAR_WIDTH = 36;
const HP_BAR_HEIGHT = 4;

/** 템플릿별 본체 색상. 서버 MonsterRegistry.bodyColor 와 동기화. */
const MONSTER_BODY_COLOR: Record<string, number> = {
  snail: 0x7cd36a,
  blue_snail: 0x6cb7ff,
  red_snail: 0xff6a5a,
  orange_mushroom: 0xffa64a,
  stump: 0x9a6b3d
};

/** 템플릿별 한글 표시명. 팝업/디버그용. */
export const MONSTER_NAMES: Record<string, string> = {
  snail: '달팽이',
  blue_snail: '파란 달팽이',
  red_snail: '빨간 달팽이',
  orange_mushroom: '주황버섯',
  stump: '나무 토막'
};

function textureKey(template: string): string {
  return `monster_${template}`;
}

/**
 * 서버 권위 몬스터의 클라이언트 표현. HP 바 + 피격 플래시 포함.
 *
 * <p>템플릿별로 별도의 텍스처를 동적 생성해 종을 시각적으로 구분한다.
 * 모양은 공통(껍질+몸통 실루엣), 색상만 바꾼다.
 */
export class MonsterSprite {
  readonly sprite: Phaser.GameObjects.Sprite;
  private readonly hpBg: Phaser.GameObjects.Rectangle;
  private readonly hpFill: Phaser.GameObjects.Rectangle;
  private targetX: number;
  /** 서버가 최근 알려준 속도(px/s). dead-reckoning 으로 targetX 를 자체 추진하는 데 쓴다. */
  private targetVx = 0;
  private readonly y: number;
  private hp: number;
  private readonly maxHp: number;

  constructor(
    scene: Phaser.Scene,
    readonly id: number,
    template: string,
    x: number,
    y: number,
    hp: number,
    maxHp: number
  ) {
    MonsterSprite.ensureTexture(scene, template);
    this.sprite = scene.add.sprite(x, y, textureKey(template)).setOrigin(0.5, 1);
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
    this.targetVx = vx;
    // 기본 텍스처는 왼쪽을 바라본다(눈이 x=6, 껍질이 x=22).
    // 따라서 오른쪽으로 이동할 때 flip, 왼쪽 이동 시 원본 유지.
    if (vx > 0) this.sprite.setFlipX(true);
    else if (vx < 0) this.sprite.setFlipX(false);
  }

  applyDamage(newHp: number, scene: Phaser.Scene, damage: number): void {
    this.hp = newHp;
    this.refreshHpBar();

    this.sprite.setTintFill(0xffffff);
    scene.time.delayedCall(80, () => this.sprite.clearTint());

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
    // Dead-reckoning: 서버 샘플이 오기 전 구간에도 targetX 가 스스로 전진하도록
    // 최신 vx 를 기반으로 추진. 새 샘플이 도착하면 setTarget 이 targetX 를 덮어써
    // 서버 권위와 재동기. 이로써 브로드캐스트 주기(100ms) 간격의 스테핑이 사라진다.
    this.targetX += this.targetVx * (dt / 1000);
    const t = Math.min(1, dt / 120);
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

  /** 템플릿별 텍스처를 lazy 생성. 이미 있으면 no-op. */
  private static ensureTexture(scene: Phaser.Scene, template: string): void {
    const key = textureKey(template);
    if (scene.textures.exists(key)) return;
    const body = MONSTER_BODY_COLOR[template] ?? 0xcccccc;
    const gfx = scene.add.graphics();
    gfx.fillStyle(body, 1);
    gfx.fillEllipse(16, 14, 30, 16);
    // 껍질은 본체 색의 보색 톤 대신 일관된 갈색 계열로 두어 "같은 골격, 다른 종" 인상을 준다.
    gfx.fillStyle(0xb36836, 1);
    gfx.fillCircle(22, 10, 9);
    gfx.fillStyle(0x7a431f, 1);
    gfx.fillCircle(22, 10, 5);
    gfx.fillStyle(0x000000, 1);
    gfx.fillRect(6, 8, 2, 2);
    gfx.generateTexture(key, 32, 22);
    gfx.destroy();
  }

  /**
   * 기본 텍스처(=달팽이)를 선행 생성해 Scene preload 시점과의 호환을 유지.
   * 새 템플릿은 스폰 시 동적으로 ensureTexture 가 처리한다.
   */
  static generateTexture(scene: Phaser.Scene): void {
    MonsterSprite.ensureTexture(scene, 'snail');
  }
}
