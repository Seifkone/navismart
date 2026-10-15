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
import com.navismart.toll.TollGateHints;
import com.navismart.fuel.FuelData.Point;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;

@Keep
public final class TripCostSettingsFragment extends BaseXmlSettingsFragment
{
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final RoutingController.RouteChangedListener routeListener = () -> main.post(() -> { resetQuote(); render(); detectHints(); });
  private TollRepository repository;
  private TollRepository.Snapshot snapshot;
  private boolean busy, failed;
  private int generation, quoteRevision = -1, vehicleClass;
  private String profileId = "";
  private Estimate manualTolls;
  private Instant quoteAt;
  private boolean manualCatalogTolls;
  private int signatureRevision=-1;
  private String geometrySignature="";
  private final List<Estimate> selectedTolls = new ArrayList<>();
  private final List<String> selectedLabels = new ArrayList<>();
  private record Offer(String network,TollGateHints.Hint hint) {}
  private List<Offer> offers=List.of();
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
      } catch (IOException | RuntimeException e) {
        failure=true;
        try { result=repository.load(); }
        catch (IOException | RuntimeException ignored) { try {result=repository.bundled();}catch(IOException | RuntimeException unavailable){} }
      }
      var data=result; boolean error=failure;
      main.post(() -> { if (!isAdded() || ticket!=generation) return; snapshot=data; failed=error; busy=false; resetQuote(); render(); detectHints(); });
    });
  }
  private void resetQuote() { manualTolls=null; manualCatalogTolls=false; quoteAt=null; offers=List.of(); selectedTolls.clear(); selectedLabels.clear(); quoteRevision=-1; }
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
    if (!id.equals(profileId)) { profileId=id; vehicleClass=prefs.getInt("class_"+id,0); if(vehicleClass<0 || vehicleClass>5)vehicleClass=0; resetQuote(); }
    var classChoice=new ListPreference(requireContext()); classChoice.setPersistent(false);
    classChoice.setKey("navismart_toll_vehicle_class");
    classChoice.setTitle(R.string.navismart_toll_class); classChoice.setEntries(R.array.navismart_toll_classes);
    classChoice.setEntryValues(new String[]{"0","1","2","3","4","5"}); classChoice.setValue(Integer.toString(vehicleClass));
    classChoice.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    classChoice.setOnPreferenceChangeListener((p,v) -> { vehicleClass=Integer.parseInt((String)v); prefs.edit().putInt("class_"+profileId,vehicleClass).apply(); resetQuote(); TripCostSession.clear(); render(); detectHints(); return true; }); screen.addPreference(classChoice);
    var routing=RoutingController.get();
    if (!routing.isPlanning() || !routing.isBuilt() || !routing.isVehicleRouterType()) { info(getString(R.string.navismart_prepare_route)); return; }
    var now=Instant.now(); var points=Framework.nativeGetRoutePoints();
    String otherOptions=RoutingOptions.getActiveRoadTypes(Router.Vehicle).stream().filter(t->t!=RoadType.Toll).sorted().toList().toString()+profileId;
    TripCostSession.scope(points, vehicleClass, otherOptions, now);
    int revision=routing.getRouteGeometry().mRevision;
    if (quoteRevision!=-1 && (quoteRevision!=revision || quoteAt==null || now.isBefore(quoteAt) || now.isAfter(quoteAt.plusSeconds(1800)))) resetQuote();
    int flags=Framework.nativeGetRouteFeeFlags();
    try {
      var legs=CoMapsEconomyAdapter.legs(Framework.nativeGetRouteLegMetrics());
      Estimate tolls=manualTolls!=null ? manualTolls : flags>=0 && (flags&1)==0
          ? Estimate.nominal(0,"No toll segment marked in downloaded map") : Estimate.missing(Estimate.State.UNKNOWN,"Tolls not matched");
      Estimate other=flags>=0 && (flags&2)==0 ? Estimate.exact(0) : Estimate.missing(Estimate.State.UNKNOWN,"Ferry fare not supplied");
      boolean avoid=RoutingOptions.hasOption(RoadType.Toll, Router.Vehicle);
      if(signatureRevision!=revision){
        var geometry=new ArrayList<Point>();for(var p:routing.getRouteGeometry().mJunctions)geometry.add(new Point(p.mLat,p.mLon));
        geometrySignature=TripCostSession.signature(geometry);signatureRevision=revision;
      }
      boolean catalogTolls=manualTolls!=null && manualCatalogTolls;
      if(manualTolls==null && selectedTolls.isEmpty())for(var previous:TripCostSession.variants())
        if(previous.avoidTolls()==avoid && previous.signature().equals(geometrySignature)){
          tolls=previous.tolls();catalogTolls=previous.catalogTolls();
          if(catalogTolls && snapshot!=null && !snapshot.fresh(now))tolls=tolls.withObservation(Estimate.State.STALE,tolls.source(),tolls.observedAt());
        }
      var current=new TripCostSession.Variant(avoid, legs, tolls, other, now,geometrySignature,catalogTolls);
      TripCostSession.put(current);
      var engine=new EconomyEngine(); var vehicle=profile.forTrip(now,Duration.ofDays(1));
      info(getString(R.string.navismart_trip_current)+"\n"+summary(engine.evaluate(vehicle,current.route())));
      if (flags>=0 && (flags&1)==0 && manualTolls==null) info(getString(R.string.navismart_toll_map_zero));
      if (snapshot!=null && vehicleClass>0) action(R.string.navismart_toll_add_section,true,()->chooseNetwork(revision));
      if (snapshot!=null && vehicleClass>0) action(R.string.navismart_toll_detect,!busy,this::detectHints);
      for(var offer:offers) {
        String label=offer.network()+" · "+offer.hint().entry().name()+" → "+offer.hint().exit().name()+" · "+value(offer.hint().price(),"€");
        var suggested=new Preference(requireContext());suggested.setTitle(label);suggested.setSummary(R.string.navismart_toll_hint_notice);
        suggested.setOnPreferenceClickListener(p->{
          new AlertDialog.Builder(requireContext()).setMessage(R.string.navismart_toll_hint_confirm).setNegativeButton(android.R.string.cancel,null)
              .setPositiveButton(android.R.string.ok,(d,w)->{
                if(!sameRevision(revision) || selectedLabels.contains(label))return;
                selectedTolls.add(offer.hint().price());selectedLabels.add(label);manualTolls=null;manualCatalogTolls=false;quoteRevision=revision;quoteAt=Instant.now();render();
              }).show();return true;
        });screen.addPreference(suggested);
      }
      for (String label:selectedLabels) info(label);
      if (!selectedTolls.isEmpty()) action(R.string.navismart_toll_confirm_sections,true,()-> {
        new AlertDialog.Builder(requireContext()).setMessage(R.string.navismart_toll_complete_question)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)-> {
              if (!sameRevision(revision)) return; manualTolls=Estimate.sum(List.copyOf(selectedTolls)); manualCatalogTolls=true; quoteRevision=revision; quoteAt=Instant.now(); render();
            }).show();
      });
      action(R.string.navismart_toll_manual,true,()->manual(revision));
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
    return getString(R.string.navismart_trip_values,duration(a.durationMinutes()),value(a.distanceKm(),"km"),
        value(a.consumedEnergyCostEuro(),"€"),value(a.route().tolls(),"€"),value(a.economicCostEuro(),"€"))+"\n"+state+" · "+feasible;
  }
  private String duration(Estimate estimate) {
    if(estimate.expected()==null)return getString(R.string.navismart_unknown);
    long minutes=Math.round(estimate.expected());
    return minutes<60?String.format(Locale.getDefault(),"≈ %d min",minutes):String.format(Locale.getDefault(),"≈ %d h %02d min",minutes/60,minutes%60);
  }
  private void detectHints() {
    if(!isAdded() || busy || snapshot==null || vehicleClass<1)return;
    var routing=RoutingController.get();if(!routing.isPlanning() || !routing.isBuilt() || !routing.isVehicleRouterType())return;
    int revision=routing.getRouteGeometry().mRevision,capturedClass=vehicleClass;
    var geometry=routing.getRouteGeometry().mJunctions;
    if(geometry.length<2 || geometry.length>200000)return;
    var route=new ArrayList<Point>();for(var p:geometry)route.add(new Point(p.mLat,p.mLon));
    var data=snapshot;busy=true;render();int ticket=++generation;
    worker.execute(()->{
      List<Offer> result=new ArrayList<>();
      try {for(var catalog:data.catalogs())for(var hint:TollGateHints.find(catalog,data.locations(),route,capturedClass,Instant.now()))result.add(new Offer(catalog.source().network(),hint));}
      catch(RuntimeException ignored){result.clear();}
      var found=List.copyOf(result);
      main.post(()->{if(!isAdded() || ticket!=generation)return;busy=false;
        if(sameRevision(revision) && capturedClass==vehicleClass)offers=found;render();
      });
    });
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
                if (selectedLabels.contains(label)) return;
                selectedTolls.add(quote); selectedLabels.add(label); manualTolls=null; manualCatalogTolls=false; quoteRevision=revision; quoteAt=Instant.now(); render();
              }).show();
        }).show();
  }
  private void manual(int revision) {
    var input=new EditText(requireContext()); input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    var dialog=new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_toll_manual).setMessage(R.string.navismart_toll_manual_notice)
        .setView(input).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      try { double amount=Double.parseDouble(input.getText().toString().trim().replace(',','.')); Estimate.checkNumber(amount);
        if(Math.abs(amount*100-Math.rint(amount*100))>1e-6)throw new IllegalArgumentException("Whole cents required");
        if (amount>10000 || !sameRevision(revision)) return;
        manualTolls=Estimate.nominal(amount,"User confirmed total tolls"); manualCatalogTolls=false; quoteRevision=revision; quoteAt=Instant.now(); selectedTolls.clear(); selectedLabels.clear(); dialog.dismiss(); render();
      } catch (IllegalArgumentException e) { input.setError(getString(R.string.navismart_compare_invalid)); }
    })); dialog.show();
  }
  private void info(String text) { var row=new Preference(requireContext()); row.setSummary(text); row.setSelectable(false); getPreferenceScreen().addPreference(row); }
  private void action(int title,boolean enabled,Runnable callback) { var row=new Preference(requireContext()); row.setTitle(title); row.setEnabled(enabled); row.setOnPreferenceClickListener(p->{callback.run();return true;}); getPreferenceScreen().addPreference(row); }
  @Override public void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
