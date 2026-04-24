import Phaser from 'phaser';
import { ITEM_META } from '../data/ItemMeta';

/**
 * 레거시 호환: 기존 import 경로를 깨지 않도록 ITEM_META 에서 파생된
 * 조회 맵을 계속 export 한다. 새 코드는 ItemMeta.ts 를 직접 참조하는 편이 낫다.
 */
export const ITEM_COLORS: Record<string, number> = Object.fromEntries(
  Object.values(ITEM_META).map((m) => [m.id, m.color])
);
export const ITEM_NAMES: Record<string, string> = Object.fromEntries(
  Object.values(ITEM_META).map((m) => [m.id, m.name])
);

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
