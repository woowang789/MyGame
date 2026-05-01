/**
 * 서버와의 WebSocket 통신을 캡슐화한다.
 *
 * 송수신 + 패킷 타입별 핸들러 라우팅 + 지연 연결.
 * 재연결은 호출자(LoginScreen)가 사용자 액션 시점에 ensureOpen() 으로 트리거.
 */
export type Packet = { type: string } & Record<string, unknown>;
export type PacketHandler = (packet: Packet) => void;
type CloseHandler = (event: CloseEvent) => void;

/**
 * 송수신 패킷을 관찰하기 위한 옵저버. 디버그 빌드의 PacketLogger 가 구현하지만,
 * 본 인터페이스를 통해 WebSocketClient 가 디버그 모듈에 직접 의존하지 않는다.
 */
export interface PacketObserver {
  recordSend(packet: Packet): void;
  recordRecv(packet: Packet): void;
}

export class WebSocketClient {
  private socket: WebSocket | null = null;
  private readonly handlers = new Map<string, PacketHandler>();
  private readonly closeHandlers: CloseHandler[] = [];
  /**
   * 진행 중인 연결 시도 Promise. 같은 시점에 여러 호출이 들어와도 동일 시도를 공유.
   * 시도 종료(open or error/close) 후 null 로 비워 다음 시도가 새 소켓을 만들 수 있게 함.
   */
  private pendingOpen: Promise<void> | null = null;
  private observer: PacketObserver | null = null;

  constructor(private readonly url: string) {}

  /** 옵저버 등록(중복 호출 시 마지막 옵저버로 교체). 디버그 모드에서만 사용. */
  setObserver(observer: PacketObserver | null): void {
    this.observer = observer;
  }

  /**
   * 현재 연결되지 않은 경우에만 새로 연결을 시도하고, OPEN 이 될 때까지 기다린다.
   * - 이미 OPEN 이면 즉시 resolve
   * - 진행 중인 시도가 있으면 같은 Promise 를 공유 (중복 소켓 방지)
   * - 첫 패킷이 오기 전 close/error 가 먼저 오면 reject
   */
  ensureOpen(): Promise<void> {
    if (this.isOpen) return Promise.resolve();
    if (this.pendingOpen) return this.pendingOpen;

    const ws = new WebSocket(this.url);
    this.socket = ws;

    this.pendingOpen = new Promise<void>((resolve, reject) => {
      const onOpen = () => {
        console.log('[WS] 연결됨:', this.url);
        ws.removeEventListener('error', onErrorOnce);
        this.pendingOpen = null;
        resolve();
      };
      // 연결 확립 전 발생한 error → 시도 자체를 실패시킨다.
      // 연결 후의 error 는 별도로 console.error 만 찍는다.
      const onErrorOnce = () => {
        ws.removeEventListener('open', onOpen);
        this.pendingOpen = null;
        reject(new Error('WebSocket 연결 실패'));
      };
      ws.addEventListener('open', onOpen, { once: true });
      ws.addEventListener('error', onErrorOnce, { once: true });
    });

    ws.addEventListener('message', (event) => {
      try {
        const packet = JSON.parse(event.data as string) as Packet;
        // 핸들러가 패킷을 소비하기 전에 기록 — 핸들러 안에서 예외가 나도 로그는 남는다.
        this.observer?.recordRecv(packet);
        const handler = this.handlers.get(packet.type);
        if (handler) {
          handler(packet);
        } else {
          console.log('[WS] 미처리 패킷:', packet);
        }
      } catch (err) {
        console.warn('[WS] 메시지 파싱 실패:', err);
      }
    });

    ws.addEventListener('close', (event) => {
      console.log(`[WS] 연결 종료 (code=${event.code})`);
      // 아직 연결도 못한 상태에서 close 가 먼저 오면 (예: 서버 down) 시도를 reject.
      // ensureOpen 의 onErrorOnce 가 먼저 처리되는 경우가 대부분이지만 보강 차원.
      this.pendingOpen = null;
      for (const h of this.closeHandlers) h(event);
    });

    ws.addEventListener('error', (event) => {
      console.error('[WS] 오류:', event);
    });

    return this.pendingOpen;
  }

  on(type: string, handler: PacketHandler): void {
    this.handlers.set(type, handler);
  }

  onClose(handler: CloseHandler): void {
    this.closeHandlers.push(handler);
  }

  send(packet: Packet): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      console.warn(`[WS] 연결 미확립 상태에서 send 호출됨 (type=${packet.type})`);
      return;
    }
    this.socket.send(JSON.stringify(packet));
    // 실제 전송 성공 직후에만 기록 — readyState 가드를 통과한 패킷만 옵저버에 노출.
    this.observer?.recordSend(packet);
  }

  get isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }
}
