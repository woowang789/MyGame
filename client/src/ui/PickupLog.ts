/** 픽업 로그 엔트리의 종류. 색상·CSS 클래스 구분용. */
export type PickupKind = 'exp' | 'meso' | 'item';

/**
 * 좌하단 픽업/획득 알림 로그.
 *
 * 채팅창과 분리된 독립 DOM 영역을 관리한다. 각 엔트리는 일정 시간 후 페이드아웃되고
 * 완료 시 DOM 에서 제거된다. 동시에 많은 알림이 쌓이면 {@link MAX_ENTRIES} 로 오래된
 * 것부터 즉시 제거해 화면이 넘치지 않게 한다.
 */
export class PickupLog {
  /** 한 엔트리의 총 수명(ms). 이 시간 후 페이드아웃 트랜지션이 시작된다. */
  private static readonly HOLD_MS = 4000;
  /** 페이드아웃 트랜지션 시간. CSS 의 transition duration 과 일치해야 한다. */
  private static readonly FADE_MS = 600;
  /** 화면에 동시에 유지할 최대 엔트리 수. 초과 시 가장 오래된 엔트리 강제 제거. */
  private static readonly MAX_ENTRIES = 6;

  push(kind: PickupKind, text: string): void {
    const container = document.getElementById('pickup-log');
    if (!container) return;

    const div = document.createElement('div');
    div.className = `entry ${kind}`;
    div.textContent = text;
    container.appendChild(div);

    // 오래된 엔트리 선제 제거. column-reverse 라 DOM 첫 자식이 가장 오래된 항목.
    while (container.children.length > PickupLog.MAX_ENTRIES) {
      container.firstChild?.remove();
    }

    // 수명 경과 후 페이드아웃 → DOM 제거. setTimeout 기반이라 탭 비활성 시에도
    // 누적되지 않게 MAX_ENTRIES 로 상한을 건다.
    window.setTimeout(() => div.classList.add('fading'), PickupLog.HOLD_MS);
    window.setTimeout(() => div.remove(), PickupLog.HOLD_MS + PickupLog.FADE_MS);
  }
}
