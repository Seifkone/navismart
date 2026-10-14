package app.organicmaps.navismart;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.settings.SettingsActivity;
import com.navismart.obd.ObdProtocol;
import com.navismart.obd.ObdRecordingState;
import com.navismart.obd.ObdRecordingState.Status;
import java.util.concurrent.CopyOnWriteArraySet;

/** Explicit connected-device foreground service. No boot restart, automatic reconnect or network. */
public final class ObdRecordingService extends Service
{
  private static final String CHANNEL = "navismart_obd";
  private static final String START = "navismart.obd.START", STOP = "navismart.obd.STOP";
  private static final int NOTIFICATION_ID = 4179;
  private static final ObdRecordingState STATE = new ObdRecordingState();
  private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
  private static ObdRecordingService sInstance;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private ObdSession mSession;
  private PowerManager.WakeLock mWakeLock;
  private long mGeneration;
  public static ObdRecordingState.Snapshot snapshot() { return STATE.snapshot(); }
  public static void observe(Runnable listener) { LISTENERS.add(listener); }
  public static void removeObserver(Runnable listener) { LISTENERS.remove(listener); }
  private static void changed() { for (var listener : LISTENERS) listener.run(); }
  @SuppressLint("MissingPermission")
  public static void start(Context context, BluetoothDevice device)
  {
    if (STATE.snapshot().active()) return;
    long generation = STATE.begin(); changed();
    try { ContextCompat.startForegroundService(context, new Intent(context, ObdRecordingService.class).setAction(START)
        .putExtra("device", device.getAddress()).putExtra("generation", generation)); }
    catch (RuntimeException error) { STATE.status(generation, Status.FAILED); changed(); }
  }
  public static void stopRecording()
  {
    STATE.stop();
    if (sInstance != null) sInstance.finish();
    changed();
  }
  @Override public void onCreate()
  {
    super.onCreate(); sInstance = this;
    NotificationManagerCompat.from(this).createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.navismart_obd_title)).build());
  }
  @SuppressLint("MissingPermission")
  @Override public int onStartCommand(Intent intent, int flags, int startId)
  {
    if (intent != null && STOP.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
    try
    {
      int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type);
      if (intent == null || !START.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
      long generation = intent.getLongExtra("generation", -1);
      if (generation != STATE.snapshot().generation() || !STATE.snapshot().active()) { finish(); return START_NOT_STICKY; }
      if (mSession != null) return START_NOT_STICKY;
      mGeneration = generation;
      NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID + 1);
      var manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
      var adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth unavailable");
      String address = intent.getStringExtra("device");
      BluetoothDevice device = adapter.getBondedDevices().stream().filter(d -> d.getAddress().equals(address)).findFirst()
          .orElseThrow(() -> new IllegalStateException("Device is not paired"));
      PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
      mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaviSmart:OBD"); mWakeLock.acquire(65 * 60 * 1000L);
      mSession = new ObdSession(getApplicationContext(), new ObdSession.Listener() {
        public void status(Status status)
        {
          mMain.post(() -> {
            if (!STATE.status(generation, status)) return;
            changed();
            if (STATE.snapshot().active())
            {
              try { NotificationManagerCompat.from(ObdRecordingService.this).notify(NOTIFICATION_ID, notification()); }
              catch (RuntimeException ignored) {} // Notification permission can be revoked during a session.
            }
            else
            {
              finish();
              if (status == Status.FAILED || status == Status.LIMIT_REACHED)
                try { NotificationManagerCompat.from(ObdRecordingService.this).notify(NOTIFICATION_ID + 1, notification()); }
                catch (RuntimeException ignored) {}
            }
          });
        }
        public void sample(ObdProtocol.Sample sample)
        { mMain.post(() -> { if (STATE.sample(generation, sample)) changed(); }); }
      });
      mSession.start(device);
    }
    catch (RuntimeException error)
    { STATE.status(STATE.snapshot().generation(), Status.FAILED); changed(); finish(); }
    return START_NOT_STICKY;
  }
  private Notification notification()
  {
    var stop = PendingIntent.getService(this, 0, new Intent(this, ObdRecordingService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    var open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    boolean active = STATE.snapshot().active();
    var builder = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_logo_small)
        .setContentTitle(getString(R.string.navismart_obd_title))
        .setContentText(getResources().getStringArray(R.array.navismart_obd_status)[STATE.snapshot().status().ordinal()])
        .setContentIntent(open).setOngoing(active).setAutoCancel(!active).setOnlyAlertOnce(true).setSilent(true);
    if (active) builder.addAction(0, getString(R.string.navismart_obd_stop), stop);
    return builder.build();
  }
  private void finish()
  {
    if (mSession != null) { mSession.close(); mSession = null; }
    if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release(); mWakeLock = null;
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf();
  }
  @Override public void onDestroy()
  {
    if (STATE.snapshot().generation() == mGeneration && STATE.snapshot().active()) STATE.stop();
    finish(); if (sInstance == this) sInstance = null; changed(); super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent) { return null; }
}
