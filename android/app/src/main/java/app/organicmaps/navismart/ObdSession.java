package app.organicmaps.navismart;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;
import com.navismart.obd.ElmChannel;
import com.navismart.obd.ObdCsv;
import com.navismart.obd.ObdProtocol;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit foreground diagnostic session. Closing the screen closes the socket and log. */
public final class ObdSession implements AutoCloseable
{
  public enum Status { CONNECTING, DISCOVERING, READING, NO_SENSORS, STOPPED, FAILED, LIMIT_REACHED }
  public interface Listener
  {
    void status(Status status);
    void sample(ObdProtocol.Sample sample);
  }
  private volatile boolean mClosed;
  private volatile BluetoothSocket mSocket;
  private final java.util.concurrent.ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final java.util.concurrent.ScheduledExecutorService mTimer = Executors.newSingleThreadScheduledExecutor();
  private final Listener mListener;
  private final File mDirectory;
  private volatile File mLog;
  private final Object mLogLock = new Object();

  public ObdSession(Context context, Listener listener)
  { mDirectory = new File(context.getFilesDir(), "navismart-obd"); mListener = listener; }

  @SuppressLint("MissingPermission") // Caller obtains BLUETOOTH_CONNECT before selecting a bonded device.
  public void start(BluetoothDevice device)
  {
    mWorker.execute(() -> {
      try
      {
        mListener.status(Status.CONNECTING);
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
        mSocket = socket;
        if (mClosed) { socket.close(); return; }
        var deadline = mTimer.schedule(() -> { try { socket.close(); } catch (IOException ignored) {} }, 15, TimeUnit.SECONDS);
        try { socket.connect(); } finally { deadline.cancel(false); }
        if (mClosed) return;
        var channel = new ElmChannel(socket.getInputStream(), socket.getOutputStream());
        // Only adapter settings are changed; all subsequent vehicle queries use standard Mode 01.
        channel.request("ATZ", 5000, () -> mClosed);
        for (String command : new String[]{"ATE0", "ATL0", "ATS1", "ATH0", "ATSP0"})
        {
          String answer = channel.request(command, 4000, () -> mClosed).replace(command, "").trim();
          if (!answer.equalsIgnoreCase("OK")) throw new IOException("Adapter setup rejected");
        }
        mListener.status(Status.DISCOVERING);
        Set<Integer> supported = new HashSet<>();
        for (int page : new int[]{0, 0x20, 0x40})
        {
          if (page != 0 && !supported.contains(page)) break;
          var support = ObdProtocol.support(page, channel.request(String.format(java.util.Locale.ROOT, "01%02X", page), 20000, () -> mClosed));
          if (!support.valid()) throw new IOException("Cannot determine supported sensors");
          supported.addAll(support.pids());
        }
        var signals = ObdProtocol.signals(supported);
        if (signals.isEmpty()) { mListener.status(Status.NO_SENSORS); return; }
        if (!mDirectory.isDirectory() && !mDirectory.mkdirs()) throw new IOException("Cannot create local log directory");
        // Bound retained local data; never silently delete old measurements.
        File[] logs = mDirectory.listFiles((dir, name) -> name.endsWith(".csv"));
        long size = 0; if (logs != null) for (File log : logs) size += log.length();
        if (size >= 50L * 1024 * 1024 || (logs != null && logs.length >= 100))
        { mListener.status(Status.LIMIT_REACHED); return; }
        File file = new File(mDirectory, "obd-" + UUID.randomUUID() + ".csv");
        long started = SystemClock.elapsedRealtime();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)))
        {
          synchronized (mLogLock)
          {
            if (mClosed) return;
            ObdCsv.header(writer); writer.flush(); mLog = file;
          }
          mListener.status(Status.READING);
          while (!mClosed)
          {
            for (var signal : signals)
            {
              if (mClosed) break;
              String reply = channel.request(signal.command(), 5000, () -> mClosed);
              var sample = ObdProtocol.decode(signal, reply, Instant.now(), SystemClock.elapsedRealtime() - started);
              synchronized (mLogLock)
              {
                if (mClosed) break;
                ObdCsv.write(writer, sample); writer.flush();
              }
              mListener.sample(sample);
              // Conservative rate for unknown adapters: at most 5 requests/sec, usually less.
              Thread.sleep(200);
            }
            if (SystemClock.elapsedRealtime() - started >= 60 * 60 * 1000L || file.length() >= 5 * 1024 * 1024)
            { mListener.status(Status.LIMIT_REACHED); return; }
          }
        }
        mListener.status(Status.STOPPED);
      }
      catch (IOException | RuntimeException | InterruptedException error)
      { mListener.status(mClosed ? Status.STOPPED : Status.FAILED); }
      finally { close(); }
    });
  }
  public File log() { return mLog; }
  @Override public void close()
  {
    synchronized (mLogLock) { mClosed = true; }
    BluetoothSocket socket = mSocket;
    if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    mWorker.shutdownNow(); mTimer.shutdownNow();
  }
}
