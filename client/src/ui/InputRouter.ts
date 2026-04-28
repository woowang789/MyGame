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

  private readonly onKeyDown: (e: KeyboardEvent) => void;

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
    // 모든 input 류 포커스 중 ESC = 포커스 해제. 채팅의 자체 ESC 핸들러는
    // 추가로 value 비우기 등 고유 동작을 하므로 유지된다 — 두 번 blur 가 호출돼도
    // 두 번째는 noop 이라 안전.
    this.onKeyDown = (e) => {
      if (e.key === 'Escape' && this.isAnyInputFocused()) {
        const ae = document.activeElement as HTMLElement | null;
        ae?.blur();
      }
    };
  }

  /** 한 번만 호출. 라이프사이클이 곧 페이지 전체이므로 별도 destroy 불필요. */
  install(): void {
    document.addEventListener('focusin', this.onFocusIn);
    document.addEventListener('focusout', this.onFocusOut);
    document.addEventListener('keydown', this.onKeyDown);
  }

  uninstall(): void {
    document.removeEventListener('focusin', this.onFocusIn);
    document.removeEventListener('focusout', this.onFocusOut);
    document.removeEventListener('keydown', this.onKeyDown);
  }

  /**
   * 현재 포커스가 텍스트 입력 요소에 있는지. GameScene.update 가 매 프레임 질의해
   * 스킬·이동 등 게임 입력을 건너뛸지 결정한다. 채팅·상점·미래의 모든 input 에
   * 일관되게 적용된다(이전에는 채팅만 chat.isFocused() 로 막고 있었다).
   */
  isAnyInputFocused(): boolean {
    return this.isTextEntry(document.activeElement);
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
