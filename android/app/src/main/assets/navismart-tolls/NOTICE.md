# Tariff dataset attribution

APRR.csv and AREA.csv contain factual tariff amounts converted by NaviSmart from
the current official operator documents linked at:
https://voyage.aprr.fr/tarifs-de-peage

The manifest records source PDF URLs, explicit effective dates, SHA-256 hashes,
pair counts and station counts. PDF documents are not bundled. The conversion
uses physical column coordinates; it does not reuse the community APRR CSV,
whose station labels failed validation during development.

NaviSmart preserves directed pairs and classes; missing values remain unknown.
No reverse price is inferred. Data check time is distinct from effective date.
The data is not a guarantee of nationwide coverage or correct route matching.

gates.csv: positions from the French ministry dataset "Gares de péage du réseau
routier national concédé", converted from Lambert-93 (EPSG:2154) to WGS84.
Source: https://www.data.gouv.fr/datasets/gares-de-peage-du-reseau-routier-national-concede
Licence Ouverte / Open Licence 2.0:
https://www.etalab.gouv.fr/licence-ouverte-open-licence/
The manifest retains the resource URL and reference year. These positions are
only used to suggest possible station identities; no lane or direction guarantee
is inferred from proximity alone.
