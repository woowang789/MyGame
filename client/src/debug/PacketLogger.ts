/**
 * 디버그용 WebSocket 패킷 로거.
 *
 * <p>{@code ?debug=ws} 쿼리 파라미터가 있을 때만 활성화된다. 활성화 시
 * {@link WebSocketClient} 가 송수신하는 모든 패킷을 두 가지 채널로 노출한다:
 *
 * <ol>
 *   <li>우하단 오버레이 패널 — 마지막 {@link #BUFFER_SIZE} 개를 새로고침 없이 표시</li>
 *   <li>{@code console.groupCollapsed} — DevTools 에서 풀어 펼쳐 볼 수 있도록 보조</li>
 * </ol>
 *
 * <p>학습 메모: 별도 라이브러리(react-devtools 류) 없이 같은 효과를 직접 짜본다.
 * MOVE 계열은 30Hz 로 흘러 다른 패킷을 묻기 때문에 기본 필터에서 제외하고,
 * 패널 상단의 토글로 다시 노출할 수 있다.
 */

import type { Packet } from '../network/WebSocketClient';

type Direction = 'send' | 'recv';

interface LogEntry {
  readonly seq: number;
  readonly time: number; // performance.now()
  readonly dir: Direction;
  readonly type: string;
  readonly payload: Packet;
}

/** 기본 노이즈 필터 — 30Hz 로 들어오는 위치 동기화 류는 끄고 시작. */
const DEFAULT_HIDDEN_TYPES: ReadonlySet<string> = new Set([
  'MOVE',
  'PLAYER_MOVE',
  'MONSTER_MOVE'
]);

const BUFFER_SIZE = 200;
const QUERY_KEY = 'debug';
const QUERY_VALUE = 'ws';

/** URL 쿼리에서 {@code ?debug=ws} 가 켜져 있는지 검사. */
export function isPacketDebugEnabled(): boolean {
  try {
    const params = new URLSearchParams(window.location.search);
    return params.get(QUERY_KEY) === QUERY_VALUE;
  } catch {
    return false;
  }
}

export class PacketLogger {
  private readonly buffer: LogEntry[] = [];
  private seqCounter = 0;
  private hiddenTypes: Set<string> = new Set(DEFAULT_HIDDEN_TYPES);
  private showFiltered = false;

  // DOM
  private root: HTMLDivElement | null = null;
  private listEl: HTMLDivElement | null = null;
  private statusEl: HTMLSpanElement | null = null;
  private filterToggleEl: HTMLButtonElement | null = null;

  install(): void {
    this.mountDom();
  }

  recordSend(packet: Packet): void {
    this.append({
      seq: ++this.seqCounter,
      time: performance.now(),
      dir: 'send',
      type: packet.type,
      payload: packet
    });
  }

  recordRecv(packet: Packet): void {
    this.append({
      seq: ++this.seqCounter,
      time: performance.now(),
      dir: 'recv',
      type: packet.type,
      payload: packet
    });
  }

  private append(entry: LogEntry): void {
    this.buffer.push(entry);
    if (this.buffer.length > BUFFER_SIZE) this.buffer.shift();
    // DevTools 보조 출력 — 패널이 닫혀 있어도 콘솔로 흐름 추적 가능.
    // 필터에 걸린 항목은 콘솔에도 찍지 않아 노이즈를 동시에 줄인다.
    if (!this.hiddenTypes.has(entry.type)) {
      const arrow = entry.dir === 'send' ? '→' : '←';
      // eslint-disable-next-line no-console
      console.groupCollapsed(`[WS ${arrow}] ${entry.type}`);
      // eslint-disable-next-line no-console
      console.log(entry.payload);
      // eslint-disable-next-line no-console
      console.groupEnd();
    }
    this.renderEntry(entry);
    this.updateStatus();
  }

  // ---------- DOM ----------

  private mountDom(): void {
    if (this.root) return;
    const root = document.createElement('div');
    root.id = 'ws-debug-panel';
    root.innerHTML = `
      <header>
        <span class="title">WS Debug</span>
        <span class="status">0건</span>
        <button class="toggle-filter" type="button"></button>
        <button class="clear" type="button">Clear</button>
        <button class="collapse" type="button">_</button>
      </header>
      <div class="list"></div>
    `;
    this.applyStyles(root);
    document.body.appendChild(root);

    this.root = root;
    this.listEl = root.querySelector<HTMLDivElement>('.list');
    this.statusEl = root.querySelector<HTMLSpanElement>('.status');
    this.filterToggleEl = root.querySelector<HTMLButtonElement>('.toggle-filter');

    root.querySelector<HTMLButtonElement>('.clear')?.addEventListener('click', () => {
      this.buffer.length = 0;
      if (this.listEl) this.listEl.innerHTML = '';
      this.updateStatus();
    });
    root.querySelector<HTMLButtonElement>('.collapse')?.addEventListener('click', () => {
      root.classList.toggle('collapsed');
    });
    this.filterToggleEl?.addEventListener('click', () => {
      this.showFiltered = !this.showFiltered;
      this.refilter();
    });
    this.refreshFilterToggle();
  }

  private applyStyles(root: HTMLElement): void {
    // 인라인 스타일 — 게임 CSS 와 충돌 없이 자기완결.
    const style = document.createElement('style');
    style.textContent = `
      #ws-debug-panel {
        position: fixed; right: 8px; bottom: 8px;
        width: 360px; max-height: 60vh;
        display: flex; flex-direction: column;
        background: rgba(15, 18, 28, 0.92);
        color: #d8e1ff;
        font: 11px/1.35 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        border: 1px solid #3a4258; border-radius: 6px;
        box-shadow: 0 8px 24px rgba(0,0,0,0.5);
        z-index: 99999;
      }
      #ws-debug-panel.collapsed .list { display: none; }
      #ws-debug-panel.collapsed { max-height: none; }
      #ws-debug-panel header {
        display: flex; align-items: center; gap: 6px;
        padding: 4px 6px; border-bottom: 1px solid #2a3046;
        background: rgba(30, 36, 56, 0.85); border-radius: 6px 6px 0 0;
      }
      #ws-debug-panel header .title { font-weight: 600; color: #ffd57a; }
      #ws-debug-panel header .status { margin-left: auto; color: #97a3c4; }
      #ws-debug-panel header button {
        background: #1f2538; color: #d8e1ff; border: 1px solid #3a4258;
        border-radius: 4px; padding: 1px 6px; font: inherit; cursor: pointer;
      }
      #ws-debug-panel header button:hover { background: #2a3149; }
      #ws-debug-panel .list {
        overflow-y: auto; flex: 1; padding: 2px 0;
      }
      #ws-debug-panel .entry {
        padding: 1px 6px; border-bottom: 1px dotted #2a3046;
        cursor: pointer; white-space: nowrap;
      }
      #ws-debug-panel .entry.recv { color: #9fd5ff; }
      #ws-debug-panel .entry.send { color: #ffc7a8; }
      #ws-debug-panel .entry .seq { color: #5b6478; margin-right: 6px; }
      #ws-debug-panel .entry .arrow { display: inline-block; width: 12px; }
      #ws-debug-panel .entry .type { font-weight: 600; }
      #ws-debug-panel .entry pre {
        display: none; white-space: pre-wrap; word-break: break-all;
        margin: 4px 0 6px 18px; padding: 4px 6px;
        background: rgba(0,0,0,0.35); border-radius: 4px;
        color: #c8d3f1; font-size: 10.5px;
      }
      #ws-debug-panel .entry.expanded pre { display: block; }
      #ws-debug-panel .entry.hidden { display: none; }
    `;
    root.appendChild(style);
  }

  private renderEntry(entry: LogEntry): void {
    if (!this.listEl) return;
    const el = document.createElement('div');
    el.className = `entry ${entry.dir}`;
    if (this.hiddenTypes.has(entry.type) && !this.showFiltered) {
      el.classList.add('hidden');
    }
    const arrow = entry.dir === 'send' ? '→' : '←';
    const seq = String(entry.seq).padStart(4, '0');
    el.innerHTML = `
      <div class="row">
        <span class="seq">#${seq}</span>
        <span class="arrow">${arrow}</span>
        <span class="type"></span>
      </div>
      <pre></pre>
    `;
    el.querySelector<HTMLSpanElement>('.type')!.textContent = entry.type;
    el.querySelector<HTMLPreElement>('pre')!.textContent = JSON.stringify(
      entry.payload,
      null,
      2
    );
    el.addEventListener('click', () => el.classList.toggle('expanded'));

    this.listEl.appendChild(el);
    // 버퍼와 동일한 윈도우 유지: 가장 오래된 행 제거.
    while (this.listEl.children.length > BUFFER_SIZE) {
      this.listEl.removeChild(this.listEl.children[0]);
    }
    // 새 행이 보이도록 자동 스크롤 — 단, 사용자가 위로 스크롤 중이면 방해하지 않는다.
    const nearBottom =
      this.listEl.scrollTop + this.listEl.clientHeight >= this.listEl.scrollHeight - 40;
    if (nearBottom) this.listEl.scrollTop = this.listEl.scrollHeight;
  }

  private refilter(): void {
    if (!this.listEl) return;
    for (const child of Array.from(this.listEl.children)) {
      const el = child as HTMLElement;
      const typeEl = el.querySelector<HTMLSpanElement>('.type');
      if (!typeEl) continue;
      const isHidden = this.hiddenTypes.has(typeEl.textContent ?? '');
      el.classList.toggle('hidden', isHidden && !this.showFiltered);
    }
    this.refreshFilterToggle();
  }

  private refreshFilterToggle(): void {
    if (!this.filterToggleEl) return;
    this.filterToggleEl.textContent = this.showFiltered
      ? 'MOVE 숨기기'
      : 'MOVE 보이기';
    this.filterToggleEl.title = `필터된 타입: ${[...this.hiddenTypes].join(', ')}`;
  }

  private updateStatus(): void {
    if (!this.statusEl) return;
    this.statusEl.textContent = `${this.buffer.length}건`;
  }
}
