import type { WebSocketClient } from '../network/WebSocketClient';

/**
 * 채팅 입력창(DOM) · 채팅 로그(DOM) · ghost 노출을 담당.
 *
 * Phaser Scene 과 독립된 DOM 레이어라 Scene 에서 떼어내 단일 책임으로 다룬다.
 * 입력창 포커스 여부는 Scene 의 update() 루프가 질의하므로 public 메서드로 노출.
 *
 * <p>Phaser 키 캡처 on/off 는 {@link InputRouter} 가 글로벌로 처리한다 — 본 클래스는
 * 채팅 박스의 시각 효과만 책임진다.
 */
export class ChatController {
  private ghostTimer: number | null = null;

  constructor(
    private readonly network: WebSocketClient
  ) {}

  /** Phaser 의 keyboard capture 와 채팅 입력창의 포커스 상호작용을 초기화. */
  setup(): void {
    const input = document.getElementById('chat-input') as HTMLInputElement | null;
    if (!input) return;

    // 게임 중 Enter → 입력창 포커스. 입력창에서 Enter → 전송.
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && document.activeElement !== input) {
        e.preventDefault();
        input.focus();
      }
    });

    // Escape: 취소. Enter: 전송. 방향키: blur 후 게임 입력으로 넘김.
    const arrowKeys = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        input.blur();
        input.value = '';
      } else if (e.key === 'Enter') {
        const text = input.value.trim();
        input.value = '';
        input.blur();
        if (!text) return;
        this.send(text);
      } else if (arrowKeys.has(e.key)) {
        input.blur();
      }
    });

    // Phaser 키 캡처 on/off 는 글로벌 InputRouter 가 일괄 처리한다 — 여기서는
    // 채팅 박스의 시각 효과(확장 / ghost) 만 자기 책임으로 둔다.
    const box = document.getElementById('chat-box');
    input.addEventListener('focus', () => {
      box?.classList.add('expanded');
      box?.classList.remove('ghost');
      this.clearGhostTimer();
    });
    input.addEventListener('blur', () => {
      box?.classList.remove('expanded');
    });
  }

  /** 채팅 입력창이 포커스 상태인지. 게임 단축키 판정에서 제외하는 데 쓴다. */
  isFocused(): boolean {
    return document.activeElement?.id === 'chat-input';
  }

  /** /w 귓속말 라우팅 또는 일반 ALL 채팅으로 분기해 서버에 전송. */
  send(text: string): void {
    if (!this.network.isOpen) return;
    if (text.startsWith('/w ') || text.startsWith('/귓 ')) {
      const rest = text.slice(text.indexOf(' ') + 1).trim();
      const sp = rest.indexOf(' ');
      if (sp < 0) {
        this.append('sys', '사용법: /w 닉네임 메시지');
        return;
      }
      const target = rest.slice(0, sp);
      const message = rest.slice(sp + 1);
      this.network.send({ type: 'CHAT', scope: 'WHISPER', target, message });
    } else {
      this.network.send({ type: 'CHAT', scope: 'ALL', target: '', message: text });
    }
  }

  /** 수신한 CHAT 패킷의 scope 에 따라 로컬 로그에 기록. */
  onReceive(scope: string, sender: string, msg: string): void {
    if (scope.startsWith('WHISPER:')) {
      const partner = scope.slice('WHISPER:'.length);
      this.append('whisper', `[귓] ${sender} → ${partner}: ${msg}`);
    } else {
      this.append('all', `${sender}: ${msg}`);
    }
  }

  /** 시스템/일반/귓속말을 구분해 채팅 로그에 라인 하나를 추가. */
  append(kind: 'sys' | 'all' | 'whisper', line: string): void {
    const log = document.getElementById('chat-log');
    if (!log) return;
    const div = document.createElement('div');
    div.className = kind;
    div.textContent = line;
    log.appendChild(div);
    while (log.children.length > 80) log.removeChild(log.firstChild!);
    log.scrollTop = log.scrollHeight;
    this.showGhost();
  }

  /**
   * 새 메시지 도착 시 채팅 박스를 잠시 반투명 노출(.ghost).
   * 포커스 중이면 건너뛰고, 일정 시간 뒤 자동 제거한다.
   */
  private showGhost(): void {
    const box = document.getElementById('chat-box');
    if (!box || box.classList.contains('expanded')) return;
    box.classList.add('ghost');
    this.clearGhostTimer();
    this.ghostTimer = window.setTimeout(() => {
      box.classList.remove('ghost');
      this.ghostTimer = null;
    }, 4000);
  }

  private clearGhostTimer(): void {
    if (this.ghostTimer !== null) {
      window.clearTimeout(this.ghostTimer);
      this.ghostTimer = null;
    }
  }
}
