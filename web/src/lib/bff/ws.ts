import { bffWsOrigin } from "@/lib/env";

/**
 * One WebSocket, shared by every component that wants frames from it.
 *
 * There are two of these in the app -- `/bff/ws/market` and `/bff/ws/user` -- and never
 * more. Six panels opening six sockets would mean six connections per tab and six
 * simultaneous reconnect storms when the network blips; worse, web-bff indexes the user
 * stream by session, so duplicates multiply work on the server for no benefit.
 *
 * Frames carry **absolute state, not diffs**. The backend deliberately sends full
 * refreshes so there is no handshake to get wrong and no sequence gap to detect. Do not
 * add diff-application logic here for something that does not send diffs.
 */

export type Frame = { type: string; data: unknown };

type Listener = (frame: Frame) => void;

/** Backoff on reconnect. Starts fast because the common case is a laptop waking up and
 *  the socket being fine; caps low because a trading screen showing stale depth is worse
 *  than one reconnecting noisily. */
const RECONNECT_MIN_MS = 500;
const RECONNECT_MAX_MS = 10_000;

export class BffSocket {
  private socket: WebSocket | null = null;
  private readonly listeners = new Set<Listener>();
  /** Subscription payloads we have sent, kept so they can be replayed after a reconnect.
   *  Keyed by a caller-supplied string so two panels asking for the same thing count once
   *  and the second one leaving does not unsubscribe the first. */
  private readonly subscriptions = new Map<string, { refs: number; payload: unknown }>();
  private retryMs = RECONNECT_MIN_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private closedByUs = false;
  private readonly reconnectListeners = new Set<() => void>();
  private hasOpened = false;

  constructor(private readonly path: string) {}

  /** Idempotent: calling this when already open does nothing, so a provider mounting
   *  twice in development's strict mode cannot produce two sockets. */
  connect(): void {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) {
      return;
    }
    this.closedByUs = false;
    const socket = new WebSocket(`${bffWsOrigin()}${this.path}`);
    this.socket = socket;

    socket.onopen = () => {
      this.retryMs = RECONNECT_MIN_MS;
      // Replay on every open, not just reconnects: a component may have subscribed while
      // the socket was still CONNECTING, and that request went nowhere.
      for (const { payload } of this.subscriptions.values()) {
        socket.send(JSON.stringify(payload));
      }
      if (this.hasOpened) for (const listener of this.reconnectListeners) listener();
      this.hasOpened = true;
    };

    socket.onmessage = (event) => {
      let frame: Frame;
      try {
        frame = JSON.parse(event.data as string) as Frame;
      } catch {
        return; // Not ours to interpret; dropping is better than throwing in a handler.
      }
      for (const listener of this.listeners) {
        listener(frame);
      }
    };

    // onclose fires after onerror, so reconnect logic lives in one place.
    socket.onclose = () => {
      this.socket = null;
      if (this.closedByUs) {
        return;
      }
      this.reconnectTimer = setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, RECONNECT_MAX_MS);
    };
  }

  /** Stops reconnecting and closes. Only a provider unmounting should call this. */
  close(): void {
    this.closedByUs = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.socket = null;
  }

  /** After a reconnect, never the first open: frames sent in the gap are lost, so whatever
   *  they would have updated must be refetched. Returns its own unsubscriber. */
  onReconnect(listener: () => void): () => void {
    this.reconnectListeners.add(listener);
    return () => this.reconnectListeners.delete(listener);
  }

  /** Every frame, for a component to filter. Returns its own unsubscriber. */
  onFrame(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Subscribes to a channel, reference-counted by `key`.
   *
   * Returns an unsubscriber that only actually unsubscribes when the last interested
   * component lets go -- two depth panels at the same grouping share one subscription, and
   * one of them unmounting must not blind the other.
   */
  subscribe(key: string, subscribePayload: unknown, unsubscribePayload: unknown): () => void {
    const existing = this.subscriptions.get(key);
    if (existing) {
      existing.refs += 1;
    } else {
      this.subscriptions.set(key, { refs: 1, payload: subscribePayload });
      this.send(subscribePayload);
    }

    let released = false;
    return () => {
      if (released) {
        return; // An effect cleanup running twice must not decrement twice.
      }
      released = true;
      const entry = this.subscriptions.get(key);
      if (!entry) {
        return;
      }
      entry.refs -= 1;
      if (entry.refs === 0) {
        this.subscriptions.delete(key);
        this.send(unsubscribePayload);
      }
    };
  }

  /** Dropped if the socket is not open. Safe: whatever we wanted to say is in
   *  `subscriptions` and gets replayed on the next open. */
  private send(payload: unknown): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(payload));
    }
  }
}
