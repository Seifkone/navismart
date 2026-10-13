package com.navismart.economy;

import static com.navismart.economy.TripModel.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Synthetic, reproducible scenarios. No network, GPS, Android or real vehicle calibration. */
public final class Simulator
{
  private Simulator() {}

  public static Vehicle combustion(double stock)
  {
    return new Vehicle("thermal-demo", "Thermique simulé", Powertrain.COMBUSTION,
        List.of(new Store("tank", Energy.SP95_E10, 50.0, stock, 2.0,
                          Estimate.exact(6), Estimate.exact(1.8))));
  }

  public static Route route(String id, double km, double minutes, double tolls)
  {
    return new Route(id, id, List.of(new Leg(id, Estimate.exact(km), Estimate.exact(minutes), Map.of())),
                     Estimate.exact(tolls), Estimate.exact(0));
  }

  public static void main(String[] args) throws Exception
  {
    Locale.setDefault(Locale.FRANCE);
    System.out.println("NaviSmart — simulateur économique local / scénarios synthétiques");
    EconomyEngine engine = new EconomyEngine();
    if (args.length > 0)
    {
      var options = new java.util.HashMap<String, String>();
      for (int i = 0; i < args.length; i += 2)
      {
        if (i + 1 >= args.length || !List.of("--scenario", "--profiles", "--vehicle", "--at").contains(args[i])
            || options.put(args[i], args[i + 1]) != null)
          throw new IllegalArgumentException("Usage: --scenario file [--profiles file] [--vehicle id|all] [--at ISO-8601]");
      }
      if (!options.containsKey("--scenario") || (options.containsKey("--vehicle") && !options.containsKey("--profiles")))
        throw new IllegalArgumentException("A scenario is required; --vehicle requires --profiles");
      var now = options.containsKey("--at") ? java.time.Instant.parse(options.get("--at")) : java.time.Instant.now();
      try (var input = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(options.get("--scenario")),
                                                           java.nio.charset.StandardCharsets.UTF_8))
      {
        var scenario = new ScenarioReader().read(input, now);
        if (options.containsKey("--profiles"))
        {
          try (var profilesInput = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(options.get("--profiles")),
                                                                       java.nio.charset.StandardCharsets.UTF_8))
          {
            var catalog = new ProfileCodec().read(profilesInput);
            String selected = options.getOrDefault("--vehicle", catalog.activeId());
            if (selected == null) throw new IllegalArgumentException("Select an active vehicle first");
            if (!selected.equals("all")) catalog = new VehicleProfiles.Catalog(List.of(catalog.get(selected)), selected);
            for (var comparison : VehicleProfiles.compare(catalog, scenario.routes(), now, java.time.Duration.ofDays(1)))
              printDecision(comparison.profile().vehicle().name(), comparison.decision());
          }
        }
        else
          printDecision(scenario.vehicle().name(), engine.compare(scenario.vehicle(), scenario.routes(), scenario.policy(),
                                                                  scenario.currentRouteId()));
      }
      return;
    }
    List<Route> alternatives = List.of(route("rapide", 100, 100, 10),
                                       route("économique", 120, 105, 0),
                                       route("trop-lent", 90, 112, 0));
    var decision = engine.compare(combustion(40), alternatives, new Policy(5, 0.5, 0.25), null);
    System.out.printf("\nRetard autorisé : 5 %% = %.1f min sur le trajet de référence\n",
                      decision.allowedExtraMinutes());
    for (var assessment : decision.assessments()) print(assessment, decision.timeStatuses().get(assessment.route().id()));
    System.out.println("Décision : " + decision.choice() + " / " + decision.recommendedRouteId());
    decision.explanations().forEach(System.out::println);

    Route refuel = new Route("ravitaillement", "Arrêt avec achat partiel", List.of(
        new Leg("Avant station", Estimate.exact(100), Estimate.exact(65), Map.of()),
        new Purchase("Station simulée", "tank", 12, 12, Estimate.exact(1.55), Estimate.exact(0), Estimate.exact(5)),
        new Leg("Après station", Estimate.exact(200), Estimate.exact(130), Map.of())),
        Estimate.exact(0), Estimate.exact(0));
    System.out.println("\nÉnergie consommée et argent dépensé sont deux résultats distincts :");
    print(engine.evaluate(combustion(10), refuel), null);

    Route unknownTolls = new Route("péages-inconnus", "Péages non renseignés",
        route("temp", 100, 100, 0).events(), Estimate.missing(Estimate.State.UNKNOWN, "no tariff data"),
        Estimate.exact(0));
    System.out.println("\nUne donnée absente ne vaut pas zéro :");
    print(engine.evaluate(combustion(40), unknownTolls), null);
  }

  private static void printDecision(String vehicleName, EconomyEngine.Decision result)
  {
    System.out.println("\nVéhicule : " + vehicleName);
    System.out.println("Retard maximal de référence (min) : " + result.allowedExtraMinutes());
    for (var assessment : result.assessments()) print(assessment, result.timeStatuses().get(assessment.route().id()));
    System.out.println("Décision : " + result.choice() + " / " + result.recommendedRouteId());
    result.explanations().forEach(System.out::println);
  }

  private static void print(EconomyEngine.Assessment assessment, EconomyEngine.TimeStatus time)
  {
    System.out.println("\n" + assessment.route().name());
    System.out.println("  Énergie consommée : " + amount(assessment.consumedEnergyCostEuro()));
    System.out.println("  Dépense immédiate : " + amount(assessment.immediateExpenseEuro()));
    System.out.println("  Coût économique, frais inclus : " + amount(assessment.economicCostEuro()));
    System.out.println("  Énergie / réserve : " + assessment.feasibility());
    if (time != null) System.out.println("  Contrainte de temps : " + time);
    assessment.explanations().forEach(text -> System.out.println("  " + text));
  }

  private static String amount(Estimate estimate)
  {
    if (estimate.expected() == null) return "inconnu (" + estimate.state() + ")";
    String bounds = estimate.hasBounds() ? String.format(" [%.1f–%.1f €]", estimate.lower(), estimate.upper())
                                        : " / incertitude non quantifiée";
    return String.format("≈ %.1f €", estimate.expected()) + bounds + " / " + estimate.state();
  }
}
