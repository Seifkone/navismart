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
