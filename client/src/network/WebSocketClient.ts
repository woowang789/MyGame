/**
 * 서버와의 WebSocket 통신을 캡슐화한다.
 *
 * Phase A 에서는 단순 송수신만 지원한다. 이후 Phase 에서 재연결,
 * 패킷 타입별 핸들러 라우팅 등으로 확장된다.
 */
export type Packet = { type: string } & Record<string, unknown>;
export type PacketHandler = (packet: Packet) => void;

export class WebSocketClient {
  private socket: WebSocket | null = null;
  private readonly handlers = new Map<string, PacketHandler>();

  constructor(private readonly url: string) {}

  connect(): void {
    const ws = new WebSocket(this.url);
    this.socket = ws;

    ws.addEventListener('open', () => {
      console.log('[WS] 연결됨:', this.url);
    });

    ws.addEventListener('message', (event) => {
      try {
        const packet = JSON.parse(event.data as string) as Packet;
        console.log('[WS] 수신:', packet);
        const handler = this.handlers.get(packet.type);
        handler?.(packet);
      } catch (err) {
        console.warn('[WS] 메시지 파싱 실패:', err);
      }
    });

    ws.addEventListener('close', (event) => {
      console.log(`[WS] 연결 종료 (code=${event.code})`);
    });

    ws.addEventListener('error', (event) => {
      console.error('[WS] 오류:', event);
    });
  }

  on(type: string, handler: PacketHandler): void {
    this.handlers.set(type, handler);
  }

  send(packet: Packet): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      // 연결 확립 전 호출은 조용히 버려지면 디버깅이 어려워진다.
      console.warn(`[WS] 연결 미확립 상태에서 send 호출됨 (type=${packet.type})`);
      return;
    }
    this.socket.send(JSON.stringify(packet));
  }

  get isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }
}
