/**
 * 서버와의 WebSocket 통신을 캡슐화한다.
 *
 * 단순 송수신 + 패킷 타입별 핸들러 라우팅. 재연결/버퍼링은 추후 Phase 에서.
 */
export type Packet = { type: string } & Record<string, unknown>;
export type PacketHandler = (packet: Packet) => void;
type OpenHandler = () => void;

export class WebSocketClient {
  private socket: WebSocket | null = null;
  private readonly handlers = new Map<string, PacketHandler>();
  private readonly openHandlers: OpenHandler[] = [];

  constructor(private readonly url: string) {}

  connect(): void {
    const ws = new WebSocket(this.url);
    this.socket = ws;

    ws.addEventListener('open', () => {
      console.log('[WS] 연결됨:', this.url);
      for (const h of this.openHandlers) h();
    });

    ws.addEventListener('message', (event) => {
      try {
        const packet = JSON.parse(event.data as string) as Packet;
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
    });

    ws.addEventListener('error', (event) => {
      console.error('[WS] 오류:', event);
    });
  }

  on(type: string, handler: PacketHandler): void {
    this.handlers.set(type, handler);
  }

  onOpen(handler: OpenHandler): void {
    this.openHandlers.push(handler);
  }

  send(packet: Packet): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      console.warn(`[WS] 연결 미확립 상태에서 send 호출됨 (type=${packet.type})`);
      return;
    }
    this.socket.send(JSON.stringify(packet));
  }

  get isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }
}
