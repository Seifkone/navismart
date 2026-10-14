package app.organicmaps.settings;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import app.organicmaps.R;
import app.organicmaps.navismart.TrafficRepository;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RoutingController;
import com.navismart.fuel.FuelData.Point;
import com.navismart.traffic.TrafficData;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;

@Keep
public final class TrafficSettingsFragment extends BaseXmlSettingsFragment
{
  private final java.util.concurrent.ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private TrafficRepository mRepository;
  private TrafficData.Snapshot mSnapshot;
  private Point mOrigin;
  private boolean mBusy, mFailed;
  private int mGeneration;
  @Override protected int getXmlResources() { return R.xml.prefs_navismart_vehicles; }
  @Override public void onCreatePreferences(Bundle bundle, String root)
  {
    super.onCreatePreferences(bundle, root); mRepository = new TrafficRepository(requireContext().getApplicationContext());
    var start = RoutingController.get().getStartPoint(); double[] center = Framework.nativeGetScreenRectCenter();
    mOrigin = start == null ? new Point(center[0], center[1]) : new Point(start.getLat(), start.getLon());
    load(false);
  }
  private void load(boolean refresh)
  {
    if (mBusy) return;
    mBusy = true; render(); int generation = ++mGeneration;
    mWorker.execute(() -> {
      TrafficData.Snapshot snapshot = null; boolean failed = false;
      try { snapshot = refresh ? mRepository.refresh(Instant.now()) : mRepository.load(); }
      catch (IOException | RuntimeException error)
      { failed = true; try { snapshot = mRepository.load(); } catch (IOException | RuntimeException ignored) {} }
      TrafficData.Snapshot result = snapshot; boolean failure = failed;
      mMain.post(() -> {
        if (!isAdded() || generation != mGeneration) return;
        mSnapshot = result; mFailed = failure; mBusy = false; render();
      });
    });
  }
  private void render()
  {
    if (!isAdded()) return;
    var screen = getPreferenceScreen(); screen.removeAll(); info(getString(R.string.navismart_traffic_notice));
    if (mFailed) info(getString(R.string.navismart_fuel_fetch_error));
    var refresh = new Preference(requireContext()); refresh.setTitle(mBusy ? R.string.navismart_loading : R.string.navismart_traffic_refresh);
    refresh.setEnabled(!mBusy); refresh.setOnPreferenceClickListener(ignored -> { load(true); return true; }); screen.addPreference(refresh);
    if (mSnapshot == null) { info(getString(R.string.navismart_traffic_no_cache)); return; }
    var now = Instant.now();
    info(getString(R.string.navismart_traffic_publication, format(mSnapshot.publishedAt()), format(mSnapshot.receivedAt())));
    if (!mSnapshot.fresh(now)) info(getString(R.string.navismart_traffic_stale));
    var matches = TrafficData.nearby(mSnapshot, mOrigin, now, 50, 40);
    if (matches.isEmpty()) info(getString(R.string.navismart_traffic_no_matches));
    for (var match : matches)
    {
      var event = match.event(); var row = new Preference(requireContext());
      row.setTitle((event.road().isEmpty() ? getString(R.string.navismart_traffic_event) : event.road())
          + " · " + String.format(Locale.getDefault(), "%.1f km", match.distanceKm()));
      String status = getResources().getStringArray(R.array.navismart_traffic_states)[event.state(now).ordinal()];
      row.setSummary(status + "\n" + event.details());
      row.setOnPreferenceClickListener(ignored -> {
        String direction = switch (event.direction()) {
          case "northBound" -> getString(R.string.navismart_north);
          case "southBound" -> getString(R.string.navismart_south);
          case "eastBound" -> getString(R.string.navismart_east);
          case "westBound" -> getString(R.string.navismart_west);
          case "bothWays" -> getString(R.string.navismart_both_ways);
          default -> getString(R.string.navismart_unknown);
        };
        new AlertDialog.Builder(requireContext()).setTitle(row.getTitle())
            .setMessage(event.details() + "\n\n" + getString(R.string.navismart_traffic_dates, format(event.updatedAt()), format(event.startsAt()), format(event.endsAt()), direction))
            .setPositiveButton(R.string.navismart_show_map, (d,w) -> {
              Point point = event.points().get(0); Framework.nativeStopLocationFollow();
              Framework.nativeSetViewportCenter(point.latitude(), point.longitude(), 15); requireActivity().finish();
            }).setNegativeButton(android.R.string.cancel, null).show();
        return true;
      }); screen.addPreference(row);
    }
  }
  private void info(String text) { var p = new Preference(requireContext()); p.setSummary(text); p.setSelectable(false); getPreferenceScreen().addPreference(p); }
  private String format(Instant time)
  { return time == null ? getString(R.string.navismart_unknown) : DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withZone(ZoneId.systemDefault()).format(time); }
  @Override public void onDestroy() { mGeneration++; mWorker.shutdownNow(); super.onDestroy(); }
}
