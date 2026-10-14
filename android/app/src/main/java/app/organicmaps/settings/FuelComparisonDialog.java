package app.organicmaps.settings;

import static com.navismart.economy.TripModel.*;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import app.organicmaps.R;
import app.organicmaps.navismart.CoMapsEconomyAdapter;
import app.organicmaps.navismart.FuelStopComparison;
import app.organicmaps.navismart.VehicleProfileRepository;
import app.organicmaps.sdk.Framework;
import com.navismart.economy.EconomyEngine;
import com.navismart.economy.Estimate;
import com.navismart.fuel.FuelData;
import com.navismart.fuel.RefuelPlanner;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FuelComparisonDialog
{
  private FuelComparisonDialog() {}
  static void show(Context context)
  {
    var pending = FuelStopComparison.current();
    if (pending == null) { notice(context, R.string.navismart_compare_unavailable); return; }
    var form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
    int padding = (int)(20 * context.getResources().getDisplayMetrics().density);
    form.setPadding(padding, padding/2, padding, padding);
    var note = new TextView(context); note.setText(R.string.navismart_compare_notice); form.addView(note);
    var quantity = field(context, form, R.string.navismart_purchase_litres);
    var duration = field(context, form, R.string.navismart_stop_minutes);
    var directFees = field(context, form, R.string.navismart_direct_fees);
    var stopFees = field(context, form, R.string.navismart_stop_fees);
    var error = new TextView(context); form.addView(error);
    var scroll = new ScrollView(context); scroll.addView(form);
    var dialog = new AlertDialog.Builder(context).setTitle(R.string.navismart_compare_stop).setView(scroll)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.navismart_compare_calculate, null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      try
      {
        if (FuelStopComparison.current() != pending) throw new IllegalArgumentException("Route changed");
        var profile = new VehicleProfileRepository(context).load().active();
        if (profile == null) throw new IllegalArgumentException("No active vehicle");
        var now = Instant.now();
        var vehicle = profile.forTrip(now, Duration.ofDays(1));
        if (vehicle.stores().size() != 1 || vehicle.stores().get(0).energy() != pending.energy())
          throw new IllegalArgumentException("Explicit compatible single-store vehicle required");
        var store = vehicle.stores().get(0);
        var price = pending.station().price(pending.energy(), now, Duration.ofDays(1));
        var availability = pending.station().availability(pending.energy(), now, pending.snapshotAt(), Duration.ofMinutes(30));
        if (!price.isFresh() || availability == FuelData.Availability.CLOSED || availability == FuelData.Availability.OUTAGE)
          throw new IllegalArgumentException("Fresh non-blocked station quote required");
        var legs = CoMapsEconomyAdapter.legs(Framework.nativeGetRouteLegMetrics());
        if (legs.size() != 2) throw new IllegalArgumentException("Unexpected road legs");
        var direct = new Route("direct", context.getString(R.string.navismart_direct_route), new ArrayList<Event>(pending.directLegs()),
            fee(directFees), Estimate.exact(0));
        double litres = number(quantity), minutes = number(duration);
        if (litres <= 0) throw new IllegalArgumentException("Positive quantity required");
        var option = new RefuelPlanner.StopOption(pending.station().id(), pending.station().label(), store.id(), legs.get(0), legs.get(1),
            price, Estimate.nominal(minutes, "manual stop duration"), Estimate.exact(0), fee(stopFees), Estimate.exact(0), List.of(litres));
        var result = RefuelPlanner.compare(vehicle, direct, List.of(option), profile.policy());
        StringBuilder message = new StringBuilder(context.getString(R.string.navismart_compare_conditional));
        for (var assessment : result.decision().assessments())
        {
          String feasibility = context.getResources().getStringArray(R.array.navismart_feasibility)[assessment.feasibility().ordinal()];
          String timeState = context.getResources().getStringArray(R.array.navismart_time_states)[result.decision().timeStatuses().get(assessment.route().id()).ordinal()];
          message.append("\n\n").append(assessment.route().name()).append("\n")
              .append(context.getString(R.string.navismart_comparison_values, amount(assessment.distanceKm().expected(), "km"),
                  amount(assessment.durationMinutes().expected(), "min"), amount(assessment.consumedEnergyCostEuro().expected(), "€"),
                  amount(assessment.immediateExpenseEuro().expected(), "€"), amount(assessment.economicCostEuro().expected(), "€"),
                  amount(assessment.remainingStock().get(store.id()), "L")))
              .append("\n").append(feasibility).append("\n").append(timeState);
        }
        message.append("\n\n").append(context.getString(R.string.navismart_time_allowance, amount(result.decision().allowedExtraMinutes(), "min")));
        new AlertDialog.Builder(context).setTitle(R.string.navismart_compare_stop).setMessage(message.toString())
            .setPositiveButton(android.R.string.ok, null).show(); dialog.dismiss();
      }
      catch (IOException | IllegalArgumentException errorValue) { error.setText(R.string.navismart_compare_invalid); }
    }));
    dialog.show();
  }
  private static String amount(Double value, String unit)
  { return value == null ? "?" : String.format(Locale.getDefault(), "≈ %.2f %s", value, unit); }
  private static Estimate fee(EditText field)
  { return field.getText().toString().isBlank() ? Estimate.missing(Estimate.State.UNKNOWN, "combined fees not supplied") : Estimate.exact(number(field)); }
  private static double number(EditText field)
  {
    double value = Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
    Estimate.checkNumber(value); return value;
  }
  private static EditText field(Context context, LinearLayout form, int title)
  {
    var label = new TextView(context); label.setText(title); form.addView(label);
    var input = new EditText(context); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    form.addView(input); return input;
  }
  private static void notice(Context context, int text)
  { new AlertDialog.Builder(context).setMessage(text).setPositiveButton(android.R.string.ok, null).show(); }
}
