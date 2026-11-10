package io.plainround.seatwave.network.link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend long-distance link on one machine: a TCP forwarder on the loopback address that holds
 * every chunk of bytes for a fixed one-way delay before passing it on, in each direction.
 *
 * <p>With a one-way delay of 40 ms, every exchange of bytes that needs an answer costs 80 ms: a
 * round-trip time of 80 ms, as for a fan in another country. The operating system completes the TCP
 * handshake with the link itself before {@code accept} returns, so the link cannot delay the
 * handshake; {@link HandshakeDelay#ONE_ROUND_TRIP} charges that round trip to the first bytes the
 * client sends instead.
 *
 * <p>Bytes keep their order in each direction, as on a TCP connection. It never drops anything; a
 * lossy link is Lab 3's subject. It accepts at most a fixed number of connections at once and
 * closes any connection over that limit at once. Thread-safe: the counters are atomic, and each
 * connection's bytes are confined to its own reader and writer threads in each direction.
 */
public final class DelayLink implements AutoCloseable {

  // One read takes at most 16 KiB; any size works, since every chunk waits the same delay.
  private static final int CHUNK_BYTES = 16 * 1024;
  // Chunks waiting in one direction; a full queue makes the reader wait, which is backpressure.
  private static final int CHUNKS_IN_FLIGHT = 1_024;
  private static final Duration UPSTREAM_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  // How long a reader waits for room in a full queue before it gives the connection up.
  private static final Duration QUEUE_OFFER_TIMEOUT = Duration.ofSeconds(30);
  // 0 asks the operating system for its default accept backlog.
  private static final int SYSTEM_DEFAULT_BACKLOG = 0;
  // Below this, the writer spins instead of sleeping: a fifth of a millisecond of busy waiting.
  private static final long SPIN_BELOW_NANOS = 200_000;
  private static final Chunk END_OF_STREAM = new Chunk(0, new byte[0]);

  private final ServerSocket listener;
  private final InetSocketAddress upstream;
  private final long oneWayDelayNanos;
  private final long handshakeExtraNanos;
  private final Semaphore openSlots;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
  private final AtomicInteger connectionsAccepted = new AtomicInteger();
  private final AtomicInteger connectionsRefused = new AtomicInteger();
  private final AtomicInteger connectionsFailed = new AtomicInteger();
  private volatile boolean isClosed;

  private DelayLink(ServerSocket listener, InetSocketAddress upstream, LinkSettings settings) {
    this.listener = listener;
    this.upstream = upstream;
    this.oneWayDelayNanos = settings.oneWayDelay().toNanos();
    this.handshakeExtraNanos =
        switch (settings.handshakeDelay()) {
          case NONE -> 0;
          case ONE_ROUND_TRIP -> settings.roundTrip().toNanos();
        };
    this.openSlots = new Semaphore(settings.maxConnections());
    threads.submit(this::acceptLoop);
  }

  /**
   * Starts a link on the loopback address that forwards to one upstream server.
   *
   * @param port the port to listen on, or 0 for any free port
   * @param upstreamPort the port of the server on the loopback address
   * @param settings the delay, the handshake rule and the connection limit
   * @return the running link
   * @throws IOException if the port cannot be bound
   */
  public static DelayLink start(int port, int upstreamPort, LinkSettings settings)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    InetAddress loopback = InetAddress.getLoopbackAddress();
    ServerSocket listener = new ServerSocket(port, SYSTEM_DEFAULT_BACKLOG, loopback);
    return new DelayLink(listener, new InetSocketAddress(loopback, upstreamPort), settings);
  }

  /**
   * Returns the port the link listens on.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }

  /**
   * Returns how many connections the link has accepted and forwarded so far.
   *
   * @return the count; a client that reuses one connection for many requests adds one
   */
  public int connectionsAccepted() {
    return connectionsAccepted.get();
  }

  /**
   * Returns how many connections the link closed at once because the limit was reached.
   *
   * @return the count
   */
  public int connectionsRefused() {
    return connectionsRefused.get();
  }

  /**
   * Returns how many connections ended because a socket failed, for example an upstream that was
   * not listening.
   *
   * @return the count
   */
  public int connectionsFailed() {
    return connectionsFailed.get();
  }

  /** Stops accepting, closes every open connection and waits for the link's threads to end. */
  @Override
  public void close() throws IOException {
    isClosed = true;
    listener.close();
    for (Socket socket : openSockets) {
      socket.close();
    }
    threads.close();
  }

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        if (!openSlots.tryAcquire()) {
          // Over the limit: refuse at once rather than accept and stall (failure mode 5).
          connectionsRefused.incrementAndGet();
          client.close();
          continue;
        }
        connectionsAccepted.incrementAndGet();
        threads.submit(() -> serve(client));
      } catch (IOException acceptFailed) {
        // accept fails when close() closed the listener, which ends the loop; any other failure
        // also ends it, and clients then see "connection refused" instead of a hang.
        return;
      }
    }
  }

  private void serve(Socket client) {
    Socket server = new Socket();
    openSockets.add(client);
    openSockets.add(server);
    try (client;
        server) {
      if (isClosed) {
        // close() ran between accept and here; it may have missed these two sockets.
        return;
      }
      try {
        server.connect(upstream, Math.toIntExact(UPSTREAM_CONNECT_TIMEOUT.toMillis()));
      } catch (IOException upstreamUnreachable) {
        // Counted before the client's socket closes, then handled by closing it: the client sees
        // its connection end, which is what a dead server looks like from the far side of a link.
        connectionsFailed.incrementAndGet();
        return;
      }
      // Small TLS and HTTP messages must leave at once; Nagle's algorithm would hold them back.
      client.setTcpNoDelay(true);
      server.setTcpNoDelay(true);
      try (ExecutorService directions = Executors.newVirtualThreadPerTaskExecutor()) {
        directions.submit(() -> forward(client, server, handshakeExtraNanos));
        directions.submit(() -> forward(server, client, 0));
      }
    } catch (IOException socketFailed) {
      // Setting an option or closing a socket failed; the connection is over either way.
      connectionsFailed.incrementAndGet();
    } finally {
      openSockets.remove(client);
      openSockets.remove(server);
      openSlots.release();
    }
  }

  private void forward(Socket from, Socket to, long firstChunkExtraNanos) {
    BlockingQueue<Chunk> inFlight = new ArrayBlockingQueue<>(CHUNKS_IN_FLIGHT);
    Thread writer = Thread.ofVirtual().start(() -> deliver(inFlight, from, to));
    byte[] buffer = new byte[CHUNK_BYTES];
    long extraNanos = firstChunkExtraNanos;
    try {
      InputStream in = from.getInputStream();
      int read;
      while ((read = in.read(buffer)) >= 0) {
        long dueNanos = System.nanoTime() + oneWayDelayNanos + extraNanos;
        // Only the first chunk pays the handshake's round trip; the chunks behind it wait for it
        // anyway, because the writer delivers in order.
        extraNanos = 0;
        Chunk chunk = new Chunk(dueNanos, Arrays.copyOf(buffer, read));
        if (!inFlight.offer(chunk, QUEUE_OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          break;
        }
      }
      inFlight.offer(END_OF_STREAM, QUEUE_OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      writer.join();
    } catch (IOException closed) {
      // The peer reset or closed the connection; the writer stops on its own when it fails.
      writer.interrupt();
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      writer.interrupt();
    }
  }

  private void deliver(BlockingQueue<Chunk> inFlight, Socket from, Socket to) {
    try {
      OutputStream out = to.getOutputStream();
      while (true) {
        Chunk chunk = inFlight.take();
        if (chunk == END_OF_STREAM) {
          // The sender finished: pass the end of the stream on, after the same delay.
          to.shutdownOutput();
          return;
        }
        waitUntil(chunk.dueNanos());
        out.write(chunk.bytes());
        out.flush();
      }
    } catch (IOException peerGone) {
      // The receiving side is gone, so the connection is over: closing the sending side makes
      // its reader see the end too, instead of filling a queue nobody empties.
      try {
        from.close();
      } catch (IOException alsoGone) {
        peerGone.addSuppressed(alsoGone);
        connectionsFailed.incrementAndGet();
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static void waitUntil(long dueNanos) throws InterruptedException {
    long remainingNanos;
    // A sleep can wake late by a share of its length (macOS coalesces timers), so the writer
    // sleeps half of what is left at a time, and spins only for the last fraction of a millisecond.
    while ((remainingNanos = dueNanos - System.nanoTime()) > SPIN_BELOW_NANOS) {
      Thread.sleep(Duration.ofNanos(remainingNanos / 2));
    }
    while (System.nanoTime() < dueNanos) {
      Thread.onSpinWait();
    }
  }

  private record Chunk(long dueNanos, byte[] bytes) {}
}
