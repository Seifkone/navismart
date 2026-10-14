package app.organicmaps.settings;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import app.organicmaps.R;
import app.organicmaps.navismart.ObdSession;
import com.navismart.obd.ObdProtocol;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.Executors;

@Keep
public final class ObdSettingsFragment extends BaseXmlSettingsFragment
{
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final EnumMap<ObdProtocol.Signal, ObdProtocol.Sample> mSamples = new EnumMap<>(ObdProtocol.Signal.class);
  private final java.util.concurrent.ExecutorService mExportWorker = Executors.newSingleThreadExecutor();
  private ObdSession mSession;
  private File mLastLog, mExportFile;
  private String mMessage;
  private int mGeneration;
  private final ActivityResultLauncher<String> mPermission = registerForActivityResult(
      new ActivityResultContracts.RequestPermission(), granted -> { if (granted) selectDevice(); else message(R.string.navismart_obd_permission); });
  private final ActivityResultLauncher<String> mExport = registerForActivityResult(
      new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
        File file = mExportFile; mExportFile = null;
        if (uri == null || file == null) return;
        var resolver = requireContext().getContentResolver();
        mExportWorker.execute(() -> {
          boolean success = false;
          try (var input = new FileInputStream(file); var output = resolver.openOutputStream(uri, "w"))
          {
            if (output == null) throw new IOException("Export unavailable");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush(); success = true;
          }
          catch (IOException | RuntimeException ignored) {}
          boolean result = success;
          mMain.post(() -> { if (isAdded()) message(result ? R.string.navismart_obd_exported : R.string.navismart_storage_error); });
        });
      });
  @Override protected int getXmlResources() { return R.xml.prefs_navismart_vehicles; }
  @Override public void onCreatePreferences(Bundle state, String root)
  { super.onCreatePreferences(state, root); render(); }
  private void message(int res) { if (isAdded()) { mMessage = getString(res); render(); } }
  private void render()
  {
    if (!isAdded()) return;
    var screen = getPreferenceScreen(); screen.removeAll();
    info(getString(R.string.navismart_obd_notice));
    if (mMessage != null) info(mMessage);
    action(getString(mSession == null ? R.string.navismart_obd_connect : R.string.navismart_obd_stop), () -> {
      if (mSession != null) { stop(); message(R.string.navismart_obd_stopped); }
      else if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        mPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
      else selectDevice();
    });
    action(getString(R.string.navismart_obd_logs), this::showLogs);
    for (var sample : mSamples.values())
    {
      var p = new Preference(requireContext());
      p.setTitle(getResources().getStringArray(R.array.navismart_obd_signals)[sample.signal().ordinal()]);
      p.setSummary((sample.value() == null ? getResources().getStringArray(R.array.navismart_obd_states)[sample.state().ordinal()]
          : String.format(Locale.getDefault(), "%.2f %s", sample.value(), sample.signal().unit)) + "\n" + sample.observedAt());
      p.setSelectable(false); screen.addPreference(p);
    }
  }
  @SuppressLint("MissingPermission")
  private void selectDevice()
  {
    if (!isAdded() || mSession != null) return;
    try
    {
      BluetoothManager manager = (BluetoothManager) requireContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE);
      var adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) { message(R.string.navismart_obd_enable); return; }
      var devices = new ArrayList<>(adapter.getBondedDevices());
      devices.sort(Comparator.comparing(BluetoothDevice::getAddress));
      if (devices.isEmpty()) { message(R.string.navismart_obd_pair); return; }
      String[] names = devices.stream().map(d -> (d.getName() == null ? "OBD" : d.getName()) + " · " + d.getAddress()).toArray(String[]::new);
      new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_obd_device)
          .setItems(names, (dialog, index) -> start(devices.get(index)))
          .setNegativeButton(android.R.string.cancel, null).show();
    }
    catch (RuntimeException error) { message(R.string.navismart_obd_failed); }
  }
  private void start(BluetoothDevice device)
  {
    stop(); mSamples.clear(); int generation = ++mGeneration;
    mSession = new ObdSession(requireContext().getApplicationContext(), new ObdSession.Listener() {
      public void status(ObdSession.Status status)
      {
        mMain.post(() -> {
          if (!isAdded() || generation != mGeneration) return;
          mMessage = getResources().getStringArray(R.array.navismart_obd_status)[status.ordinal()];
          if (status.ordinal() >= ObdSession.Status.NO_SENSORS.ordinal())
          { mLastLog = mSession == null ? mLastLog : mSession.log(); mSession = null; }
          render();
        });
      }
      public void sample(ObdProtocol.Sample sample)
      { mMain.post(() -> { if (isAdded() && generation == mGeneration) { mSamples.put(sample.signal(), sample); render(); } }); }
    });
    mSession.start(device); render();
  }
  private void stop()
  {
    mGeneration++;
    if (mSession != null) { mLastLog = mSession.log(); mSession.close(); mSession = null; }
  }
  private void showLogs()
  {
    stop(); render();
    File directory = new File(requireContext().getFilesDir(), "navismart-obd");
    File[] logs = directory.listFiles((dir, name) -> name.endsWith(".csv"));
    if (logs == null || logs.length == 0) { message(R.string.navismart_obd_no_logs); return; }
    java.util.Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
    String[] labels = java.util.Arrays.stream(logs).map(f -> java.time.Instant.ofEpochMilli(f.lastModified()) + " · " + f.length()/1024 + " KiB").toArray(String[]::new);
    new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_obd_logs).setItems(labels, (dialog, index) -> {
      File file = logs[index];
      new AlertDialog.Builder(requireContext()).setTitle(labels[index])
          .setPositiveButton(R.string.navismart_obd_export, (d,w) -> { mExportFile = file; mExport.launch(file.getName()); })
          .setNeutralButton(R.string.navismart_obd_delete, (d,w) -> new AlertDialog.Builder(requireContext())
              .setMessage(R.string.navismart_obd_delete_confirm).setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(R.string.navismart_obd_delete, (confirm,which) -> { if (!file.delete()) message(R.string.navismart_storage_error); else showLogs(); }).show())
          .setNegativeButton(android.R.string.cancel, null).show();
    }).setNegativeButton(android.R.string.cancel, null).show();
  }
  private void info(String text) { var p = new Preference(requireContext()); p.setSummary(text); p.setSelectable(false); getPreferenceScreen().addPreference(p); }
  private void action(String text, Runnable action)
  { var p = new Preference(requireContext()); p.setTitle(text); p.setOnPreferenceClickListener(ignored -> { action.run(); return true; }); getPreferenceScreen().addPreference(p); }
  @Override public void onStop() { stop(); if (!mSamples.isEmpty()) mMessage = getString(R.string.navismart_obd_stopped); super.onStop(); }
  @Override public void onResume() { super.onResume(); render(); }
  @Override public void onDestroy() { stop(); mExportWorker.shutdown(); super.onDestroy(); }
}
