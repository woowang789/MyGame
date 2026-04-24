import Phaser from 'phaser';

/**
 * 일회성 시각 이펙트 팩토리. 트윈으로 수명을 자동 관리해
 * 생성 시점에만 호출하면 알아서 사라진다.
 *
 * Scene 으로부터 분리한 이유: 데미지 숫자·공격 슬래시·레벨업 텍스트 등은
 * Scene 의 상태에 의존하지 않는 순수한 뷰 조각이라 독립 모듈이 적절하다.
 */
export class EffectFactory {
  constructor(private readonly scene: Phaser.Scene) {}

  /** 피격 숫자. 위로 떠오르며 사라진다. */
  spawnDamageNumber(x: number, y: number, dmg: number): void {
    const txt = this.scene.add
      .text(x, y, `-${dmg}`, { fontSize: '14px', color: '#ff6b6b', fontStyle: 'bold' })
      .setOrigin(0.5, 1)
      .setStroke('#3a0a0a', 3);
    this.scene.tweens.add({
      targets: txt,
      y: y - 24,
      alpha: 0,
      duration: 700,
      onComplete: () => txt.destroy()
    });
  }

  /** 기본 공격 슬래시 이펙트. */
  spawnAttackSlash(x: number, y: number, facing: 'left' | 'right'): void {
    const offsetX = facing === 'right' ? 28 : -28;
    const slash = this.scene.add
      .rectangle(x + offsetX, y, 40, 20, 0xffffff, 0.7)
      .setStrokeStyle(2, 0xffeb8a);
    this.scene.tweens.add({
      targets: slash,
      alpha: 0,
      scaleX: 1.3,
      duration: 160,
      onComplete: () => slash.destroy()
    });
  }

  /** 스킬별 간단 이펙트 — 색상·크기로 구분. recovery 만 상승 원형. */
  spawnSkillEffect(skillId: string, x: number, y: number, dir: string): void {
    const color =
      skillId === 'power_strike'
        ? 0xffb347
        : skillId === 'triple_blow'
        ? 0xff6bd6
        : 0x7bd4ff;
    if (skillId === 'recovery') {
      const fx = this.scene.add.circle(x, y, 20, color, 0.6).setStrokeStyle(2, 0xffffff);
      this.scene.tweens.add({
        targets: fx,
        y: y - 50,
        alpha: 0,
        scale: 1.6,
        duration: 600,
        onComplete: () => fx.destroy()
      });
      return;
    }
    const offset = dir === 'left' ? -36 : 36;
    const slash = this.scene.add
      .rectangle(x + offset, y, 80, 32, color, 0.7)
      .setStrokeStyle(2, 0xffffff);
    this.scene.tweens.add({
      targets: slash,
      alpha: 0,
      scaleX: 1.6,
      scaleY: 1.2,
      duration: 220,
      onComplete: () => slash.destroy()
    });
  }

  /** EXP 획득 텍스트. */
  spawnExpGain(x: number, y: number, gained: number): void {
    const txt = this.scene.add
      .text(x, y - 36, `+${gained} EXP`, { fontSize: '12px', color: '#aee1ff' })
      .setOrigin(0.5, 1);
    this.scene.tweens.add({
      targets: txt,
      y: txt.y - 24,
      alpha: 0,
      duration: 800,
      onComplete: () => txt.destroy()
    });
  }

  /** 레벨업 안내 텍스트. */
  spawnLevelUp(x: number, y: number, level: number): void {
    const txt = this.scene.add
      .text(x, y - 50, `LEVEL UP! Lv ${level}`, {
        fontSize: '18px',
        color: '#ffd700',
        fontStyle: 'bold'
      })
      .setOrigin(0.5, 1)
      .setStroke('#6b3200', 3);
    this.scene.tweens.add({
      targets: txt,
      y: txt.y - 40,
      alpha: 0,
      duration: 1400,
      onComplete: () => txt.destroy()
    });
  }

  /**
   * 본인 피격 시 플레이어 스프라이트를 깜빡이는 플래시.
   * 서버 IFRAME_MS(1500ms) 와 동기: 150ms × yoyo × repeat 4 = 1500ms.
   */
  flashSpriteDamage(sprite: Phaser.Physics.Arcade.Sprite): void {
    sprite.setTint(0xff4444);
    this.scene.tweens.add({
      targets: sprite,
      alpha: { from: 0.3, to: 1 },
      duration: 150,
      yoyo: true,
      repeat: 4,
      onComplete: () => {
        if (!sprite.active) return;
        sprite.clearTint();
        sprite.setAlpha(1);
      }
    });
  }
}
