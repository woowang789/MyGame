import Phaser from 'phaser';

/** 템플릿 ID → 색상. 서버 ItemRegistry 와 동일하게 유지해야 한다. */
const ITEM_COLORS: Record<string, number> = {
  red_potion: 0xe74c3c,
  blue_potion: 0x3498db,
  snail_shell: 0xb36836
};

/** 템플릿 ID → 표시 이름. */
export const ITEM_NAMES: Record<string, string> = {
  red_potion: '빨간 포션',
  blue_potion: '파란 포션',
  snail_shell: '달팽이 껍질'
};

/**
 * 맵 바닥에 떨어진 아이템을 다이아몬드 모양 + 살짝 바운스 애니메이션으로 표현.
 */
export class DroppedItemSprite {
  readonly body: Phaser.GameObjects.Polygon;

  constructor(
    scene: Phaser.Scene,
    readonly id: number,
    readonly templateId: string,
    x: number,
    y: number
  ) {
    const color = ITEM_COLORS[templateId] ?? 0xffffff;
    const points = [
      { x: 0, y: -7 },
      { x: 7, y: 0 },
      { x: 0, y: 7 },
      { x: -7, y: 0 }
    ];
    this.body = scene.add.polygon(x, y - 6, points, color).setStrokeStyle(1, 0xffffff);

    scene.tweens.add({
      targets: this.body,
      y: (y - 6) - 4,
      duration: 600,
      yoyo: true,
      repeat: -1,
      ease: 'sine.inOut'
    });
  }

  destroy(): void {
    this.body.destroy();
  }
}
