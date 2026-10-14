package app.organicmaps.settings;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import app.organicmaps.R;
import app.organicmaps.navismart.FuelRepository;
import app.organicmaps.navismart.VehicleProfileRepository;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import com.navismart.economy.TripModel.Energy;
import com.navismart.fuel.FuelData;
import com.navismart.fuel.StationSearch;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

@Keep
public final class FuelSettingsFragment extends BaseXmlSettingsFragment
{
  private final java.util.concurrent.ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private FuelRepository mRepository;
  private FuelData.Snapshot mSnapshot;
  private FuelData.Point mOrigin;
  private Energy mEnergy = Energy.SP95_E10;
  private boolean mBusy;
  private String mError;
  private int mGeneration;
  @Override protected int getXmlResources() { return R.xml.prefs_navismart_vehicles; }
  @Override public void onCreatePreferences(Bundle bundle, String root)
  {
    super.onCreatePreferences(bundle, root);
    mRepository = new FuelRepository(requireContext().getApplicationContext());
    double[] center = Framework.nativeGetScreenRectCenter();
    var start = RoutingController.get().getStartPoint();
    mOrigin = start == null ? new FuelData.Point(center[0], center[1]) : new FuelData.Point(start.getLat(), start.getLon());
    try
    {
      var profile = new VehicleProfileRepository(requireContext()).load().active();
      if (profile != null) mEnergy = profile.vehicle().stores().get(0).energy();
    }
    catch (IOException ignored) { mError = getString(R.string.navismart_storage_error); }
    if (bundle != null)
      try { mEnergy = Energy.valueOf(bundle.getString("energy", mEnergy.name())); }
      catch (IllegalArgumentException ignored) {}
    load(false);
  }
  private void load(boolean refresh)
  {
    if (mBusy) return;
    mBusy = true; render(); int generation = ++mGeneration;
    mWorker.execute(() -> {
      FuelData.Snapshot snapshot = null; boolean failed = false;
      try { snapshot = refresh ? mRepository.refresh(Instant.now()) : mRepository.load(); }
      catch (IOException | RuntimeException error)
      {
        failed = true;
        try { snapshot = mRepository.load(); } catch (IOException | RuntimeException ignored) {}
      }
      FuelData.Snapshot result = snapshot; boolean failure = failed;
      mMain.post(() -> {
        if (!isAdded() || generation != mGeneration) return;
        mBusy = false; mSnapshot = result;
        mError = failure ? getString(R.string.navismart_fuel_fetch_error) : null;
        render();
      });
    });
  }
  private void render()
  {
    if (!isAdded()) return;
    var screen = getPreferenceScreen(); screen.removeAll();
    info(getString(R.string.navismart_fuel_notice));
    var compare = new Preference(requireContext());
    compare.setTitle(R.string.navismart_compare_stop); compare.setSummary(R.string.navismart_compare_entry);
    compare.setOnPreferenceClickListener(ignored -> { FuelComparisonDialog.show(requireContext()); return true; });
    screen.addPreference(compare);
    if (mError != null) info(mError);
    var select = new ListPreference(requireContext());
    select.setKey("navismart_fuel_energy"); select.setPersistent(false);
    select.setTitle(R.string.navismart_energy);
    select.setEntries(getResources().getStringArray(R.array.navismart_energies));
    select.setEntryValues(java.util.Arrays.stream(Energy.values()).map(Enum::name).toArray(String[]::new));
    select.setValue(mEnergy.name()); select.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    select.setOnPreferenceChangeListener((preference, value) -> { mEnergy = Energy.valueOf((String) value); mMain.post(this::render); return true; });
    screen.addPreference(select);
    var refresh = new Preference(requireContext());
    refresh.setTitle(mBusy ? R.string.navismart_loading : R.string.navismart_fuel_refresh);
    refresh.setEnabled(!mBusy); refresh.setOnPreferenceClickListener(pref -> { load(true); return true; }); screen.addPreference(refresh);
    if (mSnapshot == null) { info(getString(R.string.navismart_fuel_no_cache)); return; }
    info(getString(R.string.navismart_fuel_cache_date, format(mSnapshot.receivedAt()), mSnapshot.stations().size()));
    if (mEnergy == Energy.ELECTRICITY) { info(getString(R.string.navismart_fuel_no_ev)); return; }
    List<StationSearch.Match> matches = StationSearch.nearby(mSnapshot, mOrigin, mEnergy, Instant.now(), 30, 30, Duration.ofDays(7));
    if (matches.isEmpty()) info(getString(R.string.navismart_fuel_no_matches));
    for (var match : matches)
    {
      var station = match.station();
      var pref = new Preference(requireContext()); pref.setKey("fuel_" + station.id());
      pref.setTitle(station.label());
      String state = getResources().getStringArray(R.array.navismart_data_states)[match.price().state().ordinal()];
      String availability = getResources().getStringArray(R.array.navismart_fuel_availability)[match.availability().ordinal()];
      pref.setSummary(String.format(Locale.getDefault(), "%.3f €/L · %.1f km\n%s · %s\n%s",
          match.price().expected(), match.straightLineKm(), state, availability, format(match.price().observedAt())));
      pref.setOnPreferenceClickListener(ignored -> { showStation(match); return true; }); screen.addPreference(pref);
    }
  }
  private void showStation(StationSearch.Match match)
  {
    var station = match.station();
    var dialog = new AlertDialog.Builder(requireContext()).setTitle(station.label())
        .setMessage(getString(R.string.navismart_station_detail, station.id(), format(match.price().observedAt())))
        .setPositiveButton(R.string.navismart_show_map, (d,w) -> {
          Framework.nativeStopLocationFollow(); Framework.nativeSetViewportCenter(station.point().latitude(), station.point().longitude(), 16);
          requireActivity().finish();
        }).setNegativeButton(android.R.string.cancel, null);
    var routing = RoutingController.get();
    if (routing.isPlanning() && routing.isBuilt() && routing.isVehicleRouterType()
        && match.price().state() != com.navismart.economy.Estimate.State.INVALID
        && match.availability() != FuelData.Availability.CLOSED && match.availability() != FuelData.Availability.OUTAGE)
      dialog.setNeutralButton(R.string.navismart_add_fuel_stop, (d,w) -> {
        app.organicmaps.navismart.FuelStopComparison.capture(station, mEnergy, match.sourceReceivedAt());
        var object = MapObject.createMapObject(FeatureId.EMPTY, MapObject.POI, station.label(), "", station.point().latitude(), station.point().longitude());
        routing.addStop(object); requireActivity().finish();
      });
    dialog.show();
  }
  private String format(Instant time)
  { return time == null ? getString(R.string.navismart_unknown) : DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withZone(ZoneId.systemDefault()).format(time); }
  private void info(String text) { var p = new Preference(requireContext()); p.setSummary(text); p.setSelectable(false); getPreferenceScreen().addPreference(p); }
  @Override public void onDestroy() { mGeneration++; mWorker.shutdownNow(); super.onDestroy(); }
  @Override public void onSaveInstanceState(Bundle state) { state.putString("energy", mEnergy.name()); super.onSaveInstanceState(state); }
}
