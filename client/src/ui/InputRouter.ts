import Phaser from 'phaser';

/**
 * HTML 입력 요소(input/textarea/contenteditable)와 Phaser 키보드 캡처 사이의
 * 충돌을 한 곳에서 정리한다.
 *
 * <p><b>왜 한 곳에 모으는가</b>
 * <ul>
 *   <li>채팅, 상점 수량, 미래의 강화 NPC 등 input 이 추가될 때마다
 *       focus/blur 핸들러를 반복 등록하면 누락되기 쉽다.
 *   <li>{@code focusin}/{@code focusout} 은 버블링되므로 document 한 곳에서
 *       모든 input 의 포커스 변화를 감지할 수 있다(이벤트 위임 패턴).
 *   <li>각 컴포넌트는 자기 고유 처리(예: 채팅 박스 확장, ghost 효과)만 하면 된다.
 * </ul>
 *
 * <p>핵심 동작: input 류가 포커스되면 Phaser keyboard 의 글로벌 캡처를 비활성화해
 * "타이핑하는 숫자가 스킬을 발동시키는" 종류의 사고를 차단한다. blur 시 캡처를
 * 다시 켜고 키 상태를 리셋해 잔류 입력을 막는다.
 */
export class InputRouter {
  private readonly scene: Phaser.Scene;
  private readonly onFocusIn: (e: FocusEvent) => void;
  private readonly onFocusOut: (e: FocusEvent) => void;

  constructor(scene: Phaser.Scene) {
    this.scene = scene;
    this.onFocusIn = (e) => {
      if (this.isTextEntry(e.target)) {
        this.scene.input.keyboard?.disableGlobalCapture();
      }
    };
    this.onFocusOut = (e) => {
      if (this.isTextEntry(e.target)) {
        this.scene.input.keyboard?.enableGlobalCapture();
        this.scene.input.keyboard?.resetKeys();
      }
    };
  }

  /** 한 번만 호출. 라이프사이클이 곧 페이지 전체이므로 별도 destroy 불필요. */
  install(): void {
    document.addEventListener('focusin', this.onFocusIn);
    document.addEventListener('focusout', this.onFocusOut);
  }

  uninstall(): void {
    document.removeEventListener('focusin', this.onFocusIn);
    document.removeEventListener('focusout', this.onFocusOut);
  }

  /**
   * 텍스트 입력으로 간주할 요소 판별. 일반 button/checkbox 같은 input 은 게임 키와
   * 충돌이 없으므로 제외할 수도 있지만, 단순화를 위해 모든 INPUT/TEXTAREA 와
   * contenteditable 을 포함한다.
   */
  private isTextEntry(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') return true;
    if (target.isContentEditable) return true;
    return false;
  }
}
