package app.organicmaps.settings;

import static com.navismart.economy.TripModel.*;
import static com.navismart.economy.VehicleProfiles.*;

import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import app.organicmaps.R;
import app.organicmaps.navismart.VehicleProfileRepository;
import app.organicmaps.navismart.CoMapsEconomyAdapter;
import app.organicmaps.sdk.routing.RoutingController;
import com.navismart.economy.Estimate;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;

@Keep
public final class VehicleSettingsFragment extends BaseXmlSettingsFragment
{
  private Catalog mCatalog;
  private VehicleProfileRepository mRepository;
  private final java.util.concurrent.ExecutorService mTransferWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
  private final android.os.Handler mMain = new android.os.Handler(android.os.Looper.getMainLooper());
  private final ActivityResultLauncher<String> mExport = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
    if (uri == null) return;
    var resolver = requireContext().getContentResolver();
    mTransferWorker.execute(() -> {
      boolean success = false;
      try
      {
        var prepared = new java.io.ByteArrayOutputStream();
        com.navismart.economy.ProfileTransfer.write(mRepository.load(), prepared);
        try (var output = resolver.openOutputStream(uri, "w"))
        { if (output == null) throw new IOException("No destination"); output.write(prepared.toByteArray()); output.flush(); }
        success = true;
      }
      catch (IOException | RuntimeException ignored) {}
      transferResult(success);
    });
  });
  private final ActivityResultLauncher<String[]> mImport = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
    if (uri == null) return;
    var resolver = requireContext().getContentResolver();
    mTransferWorker.execute(() -> {
      try (var input = resolver.openInputStream(uri))
      {
        if (input == null) throw new IOException("No source");
        var imported = com.navismart.economy.ProfileTransfer.read(input);
        mMain.post(() -> {
          if (!isAdded()) return;
          new AlertDialog.Builder(requireContext()).setMessage(getString(R.string.navismart_import_confirm, imported.profiles().size()))
              .setPositiveButton(R.string.navismart_replace_profiles, (d,w) -> { if (save(imported)) reloadProfiles(); })
              .setNegativeButton(android.R.string.cancel, null).show();
        });
      }
      catch (IOException | RuntimeException error) { transferResult(false); }
    });
  });
  private void transferResult(boolean success)
  { mMain.post(() -> { if (isAdded()) Toast.makeText(requireContext(), success ? R.string.navismart_transfer_done : R.string.navismart_transfer_error, Toast.LENGTH_LONG).show(); }); }
  @Override protected int getXmlResources() { return R.xml.prefs_navismart_vehicles; }

  @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
  {
    super.onCreatePreferences(savedInstanceState, rootKey);
    mRepository = new VehicleProfileRepository(requireContext());
    reloadProfiles();
  }

  private void reloadProfiles()
  {
    var screen = getPreferenceScreen();
    screen.removeAll();
    var export = new Preference(requireContext()); export.setTitle(R.string.navismart_export_profiles);
    export.setOnPreferenceClickListener(ignored -> { mExport.launch("navismart-vehicles.properties"); return true; }); screen.addPreference(export);
    var restore = new Preference(requireContext()); restore.setTitle(R.string.navismart_import_profiles);
    restore.setOnPreferenceClickListener(ignored -> { mImport.launch(new String[]{"text/*", "application/octet-stream"}); return true; }); screen.addPreference(restore);
    try { mCatalog = mRepository.load(); }
    catch (IOException error)
    {
      addInfo(R.string.navismart_storage_error);
      return;
    }
    addInfo(R.string.navismart_profile_notice);
    if (!mCatalog.profiles().isEmpty())
    {
      var active = new ListPreference(requireContext());
      active.setKey("navismart_active_vehicle");
      active.setTitle(R.string.navismart_active_vehicle);
      active.setPersistent(false);
      active.setEntries(mCatalog.profiles().stream().map(p -> p.vehicle().name()).toArray(String[]::new));
      active.setEntryValues(mCatalog.profiles().stream().map(p -> p.vehicle().id()).toArray(String[]::new));
      active.setValue(mCatalog.activeId());
      active.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      active.setOnPreferenceChangeListener((preference, value) -> save(mCatalog.activate((String) value)));
      screen.addPreference(active);
    }
    for (Profile profile : mCatalog.profiles())
    {
      var pref = new Preference(requireContext());
      pref.setKey("navismart_vehicle_" + profile.vehicle().id());
      pref.setTitle(profile.vehicle().name());
      pref.setSummary(getResources().getStringArray(R.array.navismart_powertrains)[profile.vehicle().powertrain().ordinal()]);
      pref.setOnPreferenceClickListener(ignored -> { showEditor(profile); return true; });
      screen.addPreference(pref);
    }
    var add = new Preference(requireContext());
    add.setTitle(R.string.navismart_add_vehicle);
    add.setOnPreferenceClickListener(ignored -> {
      new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_powertrain)
          .setItems(R.array.navismart_powertrains, (dialog, index) -> showEditor(blank(Powertrain.values()[index])))
          .setNegativeButton(android.R.string.cancel, null).show();
      return true;
    });
    screen.addPreference(add);
    var preview = new Preference(requireContext());
    preview.setTitle(R.string.navismart_route_preview);
    preview.setSummary(R.string.navismart_route_preview_summary);
    preview.setOnPreferenceClickListener(ignored -> { showRoutePreview(); return true; });
    screen.addPreference(preview);
  }

  private void showRoutePreview()
  {
    var routing = RoutingController.get();
    var info = routing.getCachedRoutingInfo();
    if (mCatalog.active() == null || !routing.isPlanning() || !routing.isBuilt()
        || !routing.isVehicleRouterType() || info == null)
    {
      new AlertDialog.Builder(requireContext()).setMessage(R.string.navismart_prepare_route)
          .setPositiveButton(android.R.string.ok, null).show();
      return;
    }
    try
    {
      double[] metrics = app.organicmaps.sdk.Framework.nativeGetRouteLegMetrics();
      var result = metrics == null
          ? CoMapsEconomyAdapter.assess(mCatalog.active(), info.distToTarget, info.totalTimeInSeconds, Instant.now())
          : CoMapsEconomyAdapter.assess(mCatalog.active(), metrics, Instant.now());
      Estimate cost = result.consumedEnergyCostEuro();
      String value = cost.expected() == null ? getString(R.string.navismart_unknown)
          : String.format(Locale.getDefault(), "≈ %.1f €", cost.expected());
      String status = getResources().getStringArray(R.array.navismart_data_states)[cost.state().ordinal()];
      String reserve = getResources().getStringArray(R.array.navismart_feasibility)[result.feasibility().ordinal()];
      new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_route_preview)
          .setMessage(getString(R.string.navismart_energy_preview_result, mCatalog.active().vehicle().name(), value)
              + "\n" + status + "\n" + reserve
              + "\n\n" + getString(R.string.navismart_preview_notice))
          .setPositiveButton(android.R.string.ok, null).show();
    }
    catch (IllegalArgumentException error)
    {
      Toast.makeText(requireContext(), R.string.navismart_prepare_route, Toast.LENGTH_LONG).show();
    }
  }

  private void addInfo(int text)
  {
    var info = new Preference(requireContext());
    info.setSummary(text);
    info.setSelectable(false);
    getPreferenceScreen().addPreference(info);
  }

  private boolean save(Catalog catalog)
  {
    try { mRepository.save(catalog); mCatalog = catalog; return true; }
    catch (IOException error) { Toast.makeText(requireContext(), R.string.navismart_storage_error, Toast.LENGTH_LONG).show(); return false; }
  }

  private Profile blank(Powertrain type)
  {
    var stores = new ArrayList<Store>();
    if (type != Powertrain.ELECTRIC)
      stores.add(blankStore("tank", type == Powertrain.FLEXFUEL ? Energy.E85 : Energy.SP95_E10));
    if (type == Powertrain.ELECTRIC || type == Powertrain.HYBRID || type == Powertrain.PLUG_IN_HYBRID)
      stores.add(blankStore("battery", Energy.ELECTRICITY));
    return new Profile(new Vehicle(UUID.randomUUID().toString(), getString(R.string.navismart_new_vehicle), type, stores),
        new Policy(5, 0.5, 0.25), Characteristics.unknown(), null);
  }
  private static Store blankStore(String id, Energy energy)
  {
    return new Store(id, energy, null, null, null, Estimate.missing(Estimate.State.UNKNOWN, "not supplied"),
                     Estimate.missing(Estimate.State.UNKNOWN, "not supplied"));
  }

  private void showEditor(Profile profile)
  {
    var form = new LinearLayout(requireContext());
    form.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    form.setPadding(padding, padding / 2, padding, padding);
    var scroll = new ScrollView(requireContext());
    scroll.addView(form);
    label(form, getString(R.string.navismart_profile_notice));
    EditText name = field(form, R.string.navismart_name, profile.vehicle().name(), false);
    EditText mass = field(form, R.string.navismart_mass, profile.characteristics().massKg(), true);
    Spinner gearbox = spinner(form, R.string.navismart_gearbox, getResources().getStringArray(R.array.navismart_gearboxes),
                              profile.characteristics().gearbox().ordinal());
    var storeForms = new ArrayList<StoreForm>();
    for (Store store : profile.vehicle().stores()) storeForms.add(new StoreForm(form, store, profile.vehicle().powertrain()));
    var confirmStock = new CheckBox(requireContext());
    confirmStock.setText(R.string.navismart_confirm_stock);
    form.addView(confirmStock);
    label(form, getString(R.string.navismart_stock_notice));
    EditText extra = field(form, R.string.navismart_extra_time, profile.policy().extraTimePercent(), true);
    EditText minimum = field(form, R.string.navismart_minimum_gain, profile.policy().minimumSavingsEuro(), true);
    EditText penalty = field(form, R.string.navismart_switch_penalty, profile.policy().switchingPenaltyEuro(), true);
    var error = new TextView(requireContext());
    error.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    form.addView(error);
    AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.navismart_edit_vehicle)
        .setView(scroll).setPositiveButton(R.string.navismart_save, null)
        .setNeutralButton(R.string.navismart_delete_profile, (d,w) -> {
          if (mCatalog.profiles().stream().noneMatch(p -> p.vehicle().id().equals(profile.vehicle().id()))) return;
          new AlertDialog.Builder(requireContext()).setMessage(R.string.navismart_delete_profile_confirm)
              .setPositiveButton(R.string.navismart_delete_profile, (confirm,which) -> { if (save(mCatalog.remove(profile.vehicle().id()))) reloadProfiles(); })
              .setNegativeButton(android.R.string.cancel, null).show();
        })
        .setNegativeButton(android.R.string.cancel, null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      try
      {
        List<Store> stores = storeForms.stream().map(StoreForm::read).toList();
        boolean stockChanged = false;
        for (int i = 0; i < stores.size(); i++)
          stockChanged |= !Objects.equals(stores.get(i).initialStock(), profile.vehicle().stores().get(i).initialStock());
        if (stockChanged && !confirmStock.isChecked())
        {
          error.setText(R.string.navismart_stock_confirmation_required);
          return;
        }
        var updated = new Profile(new Vehicle(profile.vehicle().id(), name.getText().toString().trim(),
            profile.vehicle().powertrain(), stores), new Policy(requiredNumber(extra), requiredNumber(minimum), requiredNumber(penalty)),
            new Characteristics(number(mass), Gearbox.values()[gearbox.getSelectedItemPosition()], profile.characteristics().gearRatios()),
            confirmStock.isChecked() ? Instant.now() : profile.stockObservedAt());
        if (save(mCatalog.put(updated))) { dialog.dismiss(); reloadProfiles(); }
        else error.setText(R.string.navismart_storage_error);
      }
      catch (IllegalArgumentException exception) { error.setText(R.string.navismart_invalid_profile); }
    }));
    dialog.show();
  }

  private final class StoreForm
  {
    private final Store mOriginal;
    private final List<Energy> mEnergies;
    private final Spinner mEnergy;
    private final CheckBox mConfirmFuel;
    private final EditText mCapacity, mStock, mReserve, mRate, mRateLow, mRateHigh, mPrice, mPriceLow, mPriceHigh;
    StoreForm(LinearLayout form, Store store, Powertrain type)
    {
      mOriginal = store;
      boolean electric = store.energy() == Energy.ELECTRICITY;
      mEnergies = electric ? List.of(Energy.ELECTRICITY) : type == Powertrain.FLEXFUEL
          ? List.of(Energy.SP95_E10, Energy.SP95, Energy.SP98, Energy.E85)
          : List.of(Energy.SP95_E10, Energy.SP95, Energy.SP98, Energy.DIESEL, Energy.E85, Energy.LPG);
      var allLabels = getResources().getStringArray(R.array.navismart_energies);
      mEnergy = spinner(form, R.string.navismart_energy, mEnergies.stream().map(e -> allLabels[e.ordinal()]).toArray(String[]::new),
                        mEnergies.indexOf(store.energy()));
      int unit = electric ? R.string.navismart_unit_kwh : R.string.navismart_unit_litres;
      label(form, getString(unit));
      mCapacity = field(form, R.string.navismart_capacity, store.capacity(), true);
      mStock = field(form, R.string.navismart_stock, store.initialStock(), true);
      mReserve = field(form, R.string.navismart_reserve, store.reserve(), true);
      mRate = field(form, R.string.navismart_consumption, store.consumptionPer100Km().expected(), true);
      mRateLow = field(form, R.string.navismart_lower, store.consumptionPer100Km().lower(), true);
      mRateHigh = field(form, R.string.navismart_upper, store.consumptionPer100Km().upper(), true);
      mPrice = field(form, R.string.navismart_price, store.referencePrice().expected(), true);
      mPriceLow = field(form, R.string.navismart_lower, store.referencePrice().lower(), true);
      mPriceHigh = field(form, R.string.navismart_upper, store.referencePrice().upper(), true);
      mConfirmFuel = new CheckBox(requireContext());
      mConfirmFuel.setText(R.string.navismart_confirm_fuel);
      form.addView(mConfirmFuel);
    }
    Store read()
    {
      Energy energy = mEnergies.get(mEnergy.getSelectedItemPosition());
      // A different fuel requires its own explicit consumption and acquisition price.
      if (energy != mOriginal.energy() && !mConfirmFuel.isChecked())
        throw new IllegalArgumentException("Confirm consumption and price for the selected fuel");
      Estimate rate = manual(mRate, mRateLow, mRateHigh, mOriginal.consumptionPer100Km());
      Estimate price = manual(mPrice, mPriceLow, mPriceHigh, mOriginal.referencePrice());
      if (energy != mOriginal.energy())
      {
        rate = rate.withObservation(rate.expected() == null ? Estimate.State.UNKNOWN : Estimate.State.FRESH,
                                    "user-entered assumption for " + energy, null);
        price = price.withObservation(price.expected() == null ? Estimate.State.UNKNOWN : Estimate.State.FRESH,
                                      "user-entered assumption for " + energy, null);
      }
      return new Store(mOriginal.id(), energy, number(mCapacity), number(mStock), number(mReserve),
                       rate, price);
    }
  }
  private void label(LinearLayout form, String text)
  {
    var label = new TextView(requireContext()); label.setText(text); form.addView(label);
  }
  private EditText field(LinearLayout form, int title, Object value, boolean numeric)
  {
    label(form, getString(title));
    var input = new EditText(requireContext());
    input.setSingleLine(true);
    input.setContentDescription(getString(title));
    input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT);
    if (value != null) input.setText(value.toString());
    form.addView(input); return input;
  }
  private Spinner spinner(LinearLayout form, int title, String[] entries, int selected)
  {
    label(form, getString(title));
    var spinner = new Spinner(requireContext());
    var adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, entries);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter); spinner.setSelection(selected); form.addView(spinner); return spinner;
  }
  private static Double number(EditText input)
  {
    String text = input.getText().toString().trim();
    return text.isEmpty() ? null : Double.valueOf(text.replace(',', '.'));
  }
  private static double requiredNumber(EditText input)
  {
    Double value = number(input);
    if (value == null) throw new IllegalArgumentException("A policy value is required");
    return value;
  }
  private static Estimate manual(EditText value, EditText lower, EditText upper, Estimate original)
  {
    Double v = number(value), l = number(lower), u = number(upper);
    if (Objects.equals(v, original.expected()) && Objects.equals(l, original.lower()) && Objects.equals(u, original.upper()))
      return original;
    return new Estimate(v, l, u, v == null ? Estimate.State.UNKNOWN : Estimate.State.FRESH, "user-entered assumption", null);
  }
  @Override public void onDestroy() { mTransferWorker.shutdown(); super.onDestroy(); }
}
