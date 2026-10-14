package app.organicmaps.settings;

import android.os.*;
import android.text.InputType;
import android.widget.EditText;
import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.*;
import app.organicmaps.R;
import app.organicmaps.navismart.*;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.*;
import app.organicmaps.sdk.settings.RoadType;
import com.navismart.economy.*;
import com.navismart.toll.TollCatalog;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;

@Keep
public final class TripCostSettingsFragment extends BaseXmlSettingsFragment
{
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final RoutingController.RouteChangedListener routeListener = () -> main.post(() -> { resetQuote(); render(); });
  private TollRepository repository;
  private TollRepository.Snapshot snapshot;
  private boolean busy, failed;
  private int generation, quoteRevision = -1, vehicleClass;
  private String profileId = "";
  private Estimate manualTolls;
  private final List<Estimate> selectedTolls = new ArrayList<>();
  private final List<String> selectedLabels = new ArrayList<>();
  @Override protected int getXmlResources() { return R.xml.prefs_navismart_vehicles; }
  @Override public void onCreatePreferences(Bundle state, String root) {
    super.onCreatePreferences(state, root); repository = new TollRepository(requireContext()); load(false);
  }
  @Override public void onStart() { super.onStart(); RoutingController.get().addRouteChangedListener(routeListener); render(); }
  @Override public void onStop() { RoutingController.get().removeRouteChangedListener(routeListener); super.onStop(); }
  private void load(boolean refresh) {
    if (busy) return; busy=true; render(); int ticket=++generation;
    worker.execute(() -> {
      TollRepository.Snapshot result=null; boolean failure=false;
      try {
        result = refresh ? repository.refresh(Instant.now()) : repository.load();
        if (!refresh && result != null && !result.fresh(Instant.now())) {
          try { result=repository.refresh(Instant.now()); } catch (IOException | RuntimeException e) { failure=true; }
        }
      } catch (IOException | RuntimeException e) { failure=true; try { result=repository.load(); } catch (IOException | RuntimeException ignored) {} }
      var data=result; boolean error=failure;
      main.post(() -> { if (!isAdded() || ticket!=generation) return; snapshot=data; failed=error; busy=false; resetQuote(); render(); });
    });
  }
  private void resetQuote() { manualTolls=null; selectedTolls.clear(); selectedLabels.clear(); quoteRevision=-1; }
  private void render() {
    if (!isAdded()) return;
    var screen=getPreferenceScreen(); if (screen==null) return; screen.removeAll();
    action(R.string.navismart_vehicles_title,true,()-> {
      var preference=new Preference(requireContext()); preference.setFragment(VehicleSettingsFragment.class.getName());
      preference.setTitle(R.string.navismart_vehicles_title);
      if(requireActivity() instanceof SettingsActivity activity) activity.onPreferenceStartFragment(this,preference);
    });
    info(getString(R.string.navismart_toll_notice));
    action(busy ? R.string.navismart_loading : R.string.navismart_toll_refresh, !busy, () -> load(true));
    if (failed) info(getString(R.string.navismart_fuel_fetch_error));
    if (snapshot != null) {
      info(getString(R.string.navismart_toll_catalog_date, snapshot.receivedAt().toString()));
      for (var catalog : snapshot.catalogs()) info(catalog.source().network()+" · "+catalog.source().effectiveFrom()+" · "+catalog.pairCount()+" "+getString(R.string.navismart_toll_relations));
    }
    VehicleProfiles.Profile profile;
    try { profile=new VehicleProfileRepository(requireContext()).load().active(); }
    catch (IOException e) { info(getString(R.string.navismart_storage_error)); return; }
    if (profile==null) { info(getString(R.string.navismart_prepare_route)); return; }
    var prefs=requireContext().getSharedPreferences("navismart_tolls",0);
    String id=profile.vehicle().id();
    if (!id.equals(profileId)) { profileId=id; vehicleClass=prefs.getInt("class_"+id,0); resetQuote(); TripCostSession.clear(); }
    var classChoice=new ListPreference(requireContext()); classChoice.setPersistent(false);
    classChoice.setTitle(R.string.navismart_toll_class); classChoice.setEntries(R.array.navismart_toll_classes);
    classChoice.setEntryValues(new String[]{"0","1","2","3","4","5"}); classChoice.setValue(Integer.toString(vehicleClass));
    classChoice.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    classChoice.setOnPreferenceChangeListener((p,v) -> { vehicleClass=Integer.parseInt((String)v); prefs.edit().putInt("class_"+profileId,vehicleClass).apply(); resetQuote(); TripCostSession.clear(); render(); return true; }); screen.addPreference(classChoice);
    var routing=RoutingController.get();
    if (!routing.isPlanning() || !routing.isBuilt() || !routing.isVehicleRouterType()) { info(getString(R.string.navismart_prepare_route)); return; }
    var now=Instant.now(); var points=Framework.nativeGetRoutePoints();
    String otherOptions=RoutingOptions.getActiveRoadTypes(Router.Vehicle).stream().filter(t->t!=RoadType.Toll).sorted().toList().toString()+profileId;
    TripCostSession.scope(points, vehicleClass, otherOptions, now);
    int revision=routing.getRouteGeometry().mRevision;
    if (quoteRevision!=-1 && quoteRevision!=revision) resetQuote();
    int flags=Framework.nativeGetRouteFeeFlags();
    try {
      var legs=CoMapsEconomyAdapter.legs(Framework.nativeGetRouteLegMetrics());
      Estimate tolls=manualTolls!=null ? manualTolls : flags>=0 && (flags&1)==0
          ? Estimate.nominal(0,"No toll segment marked in downloaded map") : Estimate.missing(Estimate.State.UNKNOWN,"Tolls not matched");
      Estimate other=flags>=0 && (flags&2)==0 ? Estimate.exact(0) : Estimate.missing(Estimate.State.UNKNOWN,"Ferry fare not supplied");
      boolean avoid=RoutingOptions.hasOption(RoadType.Toll, Router.Vehicle);
      var current=new TripCostSession.Variant(avoid, legs, tolls, other, now);
      var engine=new EconomyEngine(); var vehicle=profile.forTrip(now,Duration.ofDays(1));
      info(getString(R.string.navismart_trip_current)+"\n"+summary(engine.evaluate(vehicle,current.route())));
      if (flags>=0 && (flags&1)==0 && manualTolls==null) info(getString(R.string.navismart_toll_map_zero));
      if (snapshot!=null && vehicleClass>0) action(R.string.navismart_toll_add_section,true,()->chooseNetwork(revision));
      for (String label:selectedLabels) info(label);
      if (!selectedTolls.isEmpty()) action(R.string.navismart_toll_confirm_sections,true,()-> {
        new AlertDialog.Builder(requireContext()).setMessage(R.string.navismart_toll_complete_question)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)-> {
              if (!sameRevision(revision)) return; manualTolls=Estimate.sum(List.copyOf(selectedTolls)); quoteRevision=revision; render();
            }).show();
      });
      action(R.string.navismart_toll_manual,true,()->manual(revision));
      action(R.string.navismart_trip_save,true,()-> { TripCostSession.put(current); render(); });
      action(avoid ? R.string.navismart_trip_standard : R.string.navismart_trip_avoid,true,()-> {
        TripCostSession.put(current); rebuild(!avoid);
      });
      if (!TripCostSession.variants().isEmpty()) {
        info(getString(R.string.navismart_trip_variants));
        for (var variant:TripCostSession.variants()) {
          var row=new Preference(requireContext()); row.setTitle(variant.avoidTolls()?R.string.navismart_trip_avoid:R.string.navismart_trip_standard);
          var assessment=engine.evaluate(vehicle,variant.route()); row.setSummary(summary(assessment));
          row.setOnPreferenceClickListener(p->{ if (variant.avoidTolls()!=avoid) rebuild(variant.avoidTolls()); else requireActivity().finish(); return true; }); screen.addPreference(row);
        }
        if (TripCostSession.variants().size()==2) {
          var decision=engine.compare(vehicle,TripCostSession.variants().stream().map(TripCostSession.Variant::route).toList(),profile.policy(),null);
          info(getString(R.string.navismart_trip_allowance,decision.allowedExtraMinutes()));
        }
      }
    } catch (IllegalArgumentException e) { info(getString(R.string.navismart_compare_invalid)); }
  }
  private String summary(EconomyEngine.Assessment a) {
    String state=getResources().getStringArray(R.array.navismart_data_states)[a.economicCostEuro().state().ordinal()];
    String feasible=getResources().getStringArray(R.array.navismart_feasibility)[a.feasibility().ordinal()];
    return getString(R.string.navismart_trip_values,value(a.durationMinutes(),"min"),value(a.distanceKm(),"km"),
        value(a.consumedEnergyCostEuro(),"€"),value(a.route().tolls(),"€"),value(a.economicCostEuro(),"€"))+"\n"+state+" · "+feasible;
  }
  private String value(Estimate e,String unit) { return e.expected()==null?getString(R.string.navismart_unknown):String.format(Locale.getDefault(),"≈ %.2f %s",e.expected(),unit); }
  private boolean sameRevision(int revision) { var r=RoutingController.get(); return isAdded() && r.isPlanning() && r.isBuilt() && r.getRouteGeometry().mRevision==revision; }
  private void rebuild(boolean avoid) {
    var r=RoutingController.get(); if (!r.isPlanning() || !r.isBuilt()) return;
    if (avoid) RoutingOptions.addOption(RoadType.Toll,Router.Vehicle); else RoutingOptions.removeOption(RoadType.Toll,Router.Vehicle);
    resetQuote(); if (r.rebuildPlanningRoute()) render();
  }
  private void chooseNetwork(int revision) {
    var data=snapshot; if (data==null) return;
    new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_toll_network)
        .setItems(data.catalogs().stream().map(c->c.source().network()).toArray(String[]::new),(d,w)->chooseEntry(data.catalogs().get(w),revision)).show();
  }
  private void chooseEntry(TollCatalog catalog,int revision) {
    var gates=catalog.gates();
    new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_toll_entry)
        .setItems(gates.stream().map(TollCatalog.Gate::name).toArray(String[]::new),(d,w)->chooseExit(catalog,gates.get(w),revision)).show();
  }
  private void chooseExit(TollCatalog catalog,TollCatalog.Gate entry,int revision) {
    var gates=catalog.exits(entry.id());
    new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_toll_exit)
        .setItems(gates.stream().map(TollCatalog.Gate::name).toArray(String[]::new),(d,w)-> {
          var exit=gates.get(w); var quote=catalog.quote(entry.id(),exit.id(),vehicleClass,Instant.now());
          String label=catalog.source().network()+" · "+entry.name()+" → "+exit.name()+" · "+value(quote,"€");
          new AlertDialog.Builder(requireContext()).setTitle(label).setMessage(catalog.source().effectiveFrom()+"\n"+quote.source()+"\n\n"+getString(R.string.navismart_toll_notice))
              .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(a,b)-> {
                if (!sameRevision(revision) || !quote.isFresh()) return;
                selectedTolls.add(quote); selectedLabels.add(label); manualTolls=null; quoteRevision=revision; render();
              }).show();
        }).show();
  }
  private void manual(int revision) {
    var input=new EditText(requireContext()); input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    var dialog=new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_toll_manual).setMessage(R.string.navismart_toll_manual_notice)
        .setView(input).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      try { double amount=Double.parseDouble(input.getText().toString().trim().replace(',','.')); Estimate.checkNumber(amount);
        if (amount>10000 || !sameRevision(revision)) return;
        manualTolls=Estimate.nominal(amount,"User confirmed total tolls"); quoteRevision=revision; selectedTolls.clear(); selectedLabels.clear(); dialog.dismiss(); render();
      } catch (IllegalArgumentException e) { input.setError(getString(R.string.navismart_compare_invalid)); }
    })); dialog.show();
  }
  private void info(String text) { var row=new Preference(requireContext()); row.setSummary(text); row.setSelectable(false); getPreferenceScreen().addPreference(row); }
  private void action(int title,boolean enabled,Runnable callback) { var row=new Preference(requireContext()); row.setTitle(title); row.setEnabled(enabled); row.setOnPreferenceClickListener(p->{callback.run();return true;}); getPreferenceScreen().addPreference(row); }
  @Override public void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
