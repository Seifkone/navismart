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
      if (args.length != 2 || !args[0].equals("--scenario"))
        throw new IllegalArgumentException("Usage: Simulator [--scenario path.properties]");
      try (var input = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[1]),
                                                           java.nio.charset.StandardCharsets.UTF_8))
      {
        var scenario = new ScenarioReader().read(input);
        var result = engine.compare(scenario.vehicle(), scenario.routes(), scenario.policy(), scenario.currentRouteId());
        System.out.println("Véhicule : " + scenario.vehicle().name());
        System.out.println("Retard maximal de référence (min) : " + result.allowedExtraMinutes());
        for (var assessment : result.assessments()) print(assessment, result.timeStatuses().get(assessment.route().id()));
        System.out.println("Décision : " + result.choice() + " / " + result.recommendedRouteId());
        result.explanations().forEach(System.out::println);
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
