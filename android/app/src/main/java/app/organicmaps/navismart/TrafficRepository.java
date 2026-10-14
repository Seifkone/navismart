package app.organicmaps.navismart;

import android.content.Context;
import android.util.AtomicFile;
import com.navismart.traffic.GovernmentTrafficReader;
import com.navismart.traffic.TrafficData;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

/** Government hourly national event aggregate, with a private atomic last-valid cache. */
public final class TrafficRepository
{
  private static final int MAGIC = 0x4E535431;
  private final AtomicFile mFile;
  private final android.content.SharedPreferences mPreferences;
  public TrafficRepository(Context context)
  {
    mFile = new AtomicFile(new File(context.getFilesDir(), "navismart-traffic-v1.bin"));
    mPreferences = context.getSharedPreferences("navismart_traffic", Context.MODE_PRIVATE);
  }
  public TrafficData.Snapshot load() throws IOException
  {
    if (!mFile.getBaseFile().exists() && !new File(mFile.getBaseFile().getPath() + ".bak").exists()) return null;
    try (var input = new DataInputStream(mFile.openRead()))
    {
      if (input.readInt() != MAGIC) throw new IOException("Unsupported traffic cache");
      return new GovernmentTrafficReader().read(input, Instant.ofEpochMilli(input.readLong()));
    }
  }
  public synchronized TrafficData.Snapshot refresh(Instant now) throws IOException
  {
    TrafficData.Snapshot cached = null;
    try { cached = load(); } catch (IOException ignored) {}
    if (cached != null && !cached.receivedAt().isAfter(now)
        && Duration.between(cached.receivedAt(), now).compareTo(Duration.ofHours(1)) < 0) return cached;
    long previous = mPreferences.getLong("last_attempt", 0);
    if (previous > 0 && previous <= now.toEpochMilli() && now.toEpochMilli() - previous < 120_000)
      throw new IOException("Traffic download backoff active");
    mPreferences.edit().putLong("last_attempt", now.toEpochMilli()).apply();
    var connection = (HttpURLConnection) new URL(TrafficData.SOURCE).openConnection();
    connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000); connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("User-Agent", "NaviSmart/0.3"); connection.setRequestProperty("Accept", "application/xml,text/xml");
    byte[] bytes;
    try
    {
      if (connection.getResponseCode() != 200 || connection.getContentLength() > 20_000_000) throw new IOException("Traffic feed unavailable");
      try (InputStream input = connection.getInputStream(); var output = new ByteArrayOutputStream())
      {
        long deadline = System.nanoTime() + 120_000_000_000L;
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1)
        {
          if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("Traffic download cancelled or timed out");
          if (output.size() + count > 20_000_000) throw new IOException("Oversized traffic feed");
          output.write(buffer, 0, count);
        }
        bytes = output.toByteArray();
      }
    }
    finally { connection.disconnect(); }
    var snapshot = new GovernmentTrafficReader().read(new ByteArrayInputStream(bytes), now);
    FileOutputStream output = null;
    try
    {
      output = mFile.startWrite(); var data = new DataOutputStream(output);
      data.writeInt(MAGIC); data.writeLong(now.toEpochMilli()); data.write(bytes); data.flush();
      mFile.finishWrite(output); return snapshot;
    }
    catch (IOException error) { if (output != null) mFile.failWrite(output); throw error; }
  }
}
