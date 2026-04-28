import Phaser from 'phaser';
import { NPC_TEXTURE } from '../scenes/TextureFactory';

/**
 * 맵 위 고정 NPC. 위치 갱신은 없고, 머리 위에 이름 + 인터랙션 힌트를 띄운다.
 *
 * - 플레이어가 INTERACT_RANGE 안에 들어오면 「F: 대화」 힌트가 점등된다.
 * - 멀리 있을 때는 어둡게 표시.
 * - 스프라이트를 직접 클릭해도 동일한 인터랙션이 발생한다(F 키와 같은 경로).
 *   거리 검증은 GameScene 측에서 한 번, 서버에서 한 번 더 한다.
 */
export class NpcSprite {
  readonly sprite: Phaser.GameObjects.Sprite;
  private readonly nameLabel: Phaser.GameObjects.Text;
  private readonly hint: Phaser.GameObjects.Text;
  private nearby = false;

  constructor(
    scene: Phaser.Scene,
    readonly id: number,
    readonly name: string,
    readonly shopId: string,
    x: number,
    y: number,
    onClick: (npc: NpcSprite) => void
  ) {
    this.sprite = scene.add.sprite(x, y, NPC_TEXTURE);
    // setInteractive() 가 hit area 를 자동 생성하고 pointer 이벤트를 활성화한다.
    // useHandCursor: 마우스 호버 시 손가락 커서로 "클릭 가능" 신호.
    this.sprite.setInteractive({ useHandCursor: true });
    this.sprite.on(Phaser.Input.Events.GAMEOBJECT_POINTER_DOWN, () => onClick(this));

    this.nameLabel = scene.add
      .text(x, y - 28, name, {
        fontSize: '11px',
        color: '#ffeb8a',
        stroke: '#000000',
        strokeThickness: 2
      })
      .setOrigin(0.5, 1);
    this.hint = scene.add
      .text(x, y - 42, 'F / 클릭: 대화', {
        fontSize: '10px',
        color: '#ffffff',
        backgroundColor: '#000000aa',
        padding: { x: 4, y: 2 }
      })
      .setOrigin(0.5, 1)
      .setVisible(false);
  }

  /** 매 프레임 호출. nearby 여부에 따라 힌트 점등. */
  setNearby(near: boolean): void {
    if (near === this.nearby) return;
    this.nearby = near;
    this.hint.setVisible(near);
    this.sprite.setAlpha(near ? 1 : 0.85);
  }

  destroy(): void {
    this.sprite.destroy();
    this.nameLabel.destroy();
    this.hint.destroy();
  }
}
