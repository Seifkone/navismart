import csv
import hashlib
import json
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
import unittest

PACK = Path(__file__).resolve().parents[3] / 'android/app/src/main/assets/navismart-tolls'


class TollPackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((PACK / 'manifest.json').read_text(encoding='utf-8'))

    def test_manifest_sources_and_dates(self):
        self.assertEqual(1, self.manifest['schema'])
        self.assertIsNotNone(datetime.fromisoformat(self.manifest['checkedAt'].replace('Z', '+00:00')).tzinfo)
        self.assertEqual({'APRR', 'AREA'}, {n['id'] for n in self.manifest['networks']})
        for network in self.manifest['networks']:
            self.assertTrue(network['sourceUrl'].startswith('https://voyage.aprr.fr/sites/default/files/'))
            date.fromisoformat(network['effectiveFrom'])
            self.assertRegex(network['pdfSha256'], r'^[0-9a-f]{64}$')

    def test_all_tariff_checksums_and_counts(self):
        for network in self.manifest['networks']:
            with self.subTest(network=network['id']):
                path = PACK / network['file']
                self.assertEqual(network['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                with path.open(encoding='utf-8', newline='') as source:
                    rows = list(csv.DictReader(source))
                self.assertEqual(network['pairs'], len(rows))
                fields = ('gare_entree', 'gare_sortie') if network['id'] == 'APRR' else ('id_gare_entree', 'id_gare_sortie')
                gates = {r[fields[0]] for r in rows} | {r[fields[1]] for r in rows}
                self.assertEqual(network['gates'], len(gates))
                self.assertEqual(len(rows), len({(r[fields[0]], r[fields[1]]) for r in rows}))
                for row in rows:
                    for i in range(1, 6):
                        amount = Decimal(row[f'tarif_classe_{i}'])
                        self.assertGreaterEqual(amount, 0)
                        self.assertEqual(amount * 100, (amount * 100).to_integral_value())

    def test_geography_integrity_and_coordinate_bounds(self):
        geography = self.manifest['geography']
        path = PACK / geography['file']
        self.assertEqual(geography['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        with path.open(encoding='utf-8', newline='') as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(geography['locations'], len(rows))
        for row in rows:
            self.assertTrue(row['name'].strip())
            self.assertTrue(41 < float(row['latitude']) < 52)
            self.assertTrue(-6 < float(row['longitude']) < 10)


if __name__ == '__main__':
    unittest.main()
