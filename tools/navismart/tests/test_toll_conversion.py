import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('build_toll_catalog', Path(__file__).resolve().parents[1] / 'build_toll_catalog.py')
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


def word(text, x):
    return {'text': text, 'x0': x}


class TollConversionTests(unittest.TestCase):
    def row(self):
        return [word('ENTRY', 38), word('WITH', 62), word('SPACES', 86), word('EXIT', 153),
                word('462,61', 271)] + [w for x, amount in zip((312, 355, 394, 437, 484), ('58,20', '89,10', '143,00', '190,70', '32,80'))
                                       for w in (word(amount, x), word('€', x+20))]

    def test_names_kept_in_their_columns(self):
        row = CONVERTER.decode_row(self.row())
        self.assertEqual(['ENTRY WITH SPACES', 'EXIT', '462.61', '58.20'], row[:4])

    def test_reversed_word_order_sorted_by_position(self):
        self.assertEqual(CONVERTER.decode_row(self.row()), CONVERTER.decode_row(list(reversed(self.row()))))

    def test_missing_price_rejected(self):
        with self.assertRaises(ValueError):
            CONVERTER.decode_row([w for w in self.row() if w['text'] != '58,20'])

    def test_changed_column_layout_rejected(self):
        rows = self.row()
        rows[4] = word('462,61', 310)
        with self.assertRaises(ValueError):
            CONVERTER.decode_row(rows)

    def test_unexpected_outside_column_rejected(self):
        with self.assertRaises(ValueError):
            CONVERTER.decode_row(self.row() + [word('OUTSIDE', 600)])

    def test_non_finite_price_rejected(self):
        with self.assertRaises(ValueError):
            CONVERTER.decode_row([dict(w, text='NaN') if w['text'] == '58,20' else w for w in self.row()])

    def test_unsupported_precision_rejected(self):
        with self.assertRaises(ValueError):
            CONVERTER.decode_row([dict(w, text='58,201') if w['text'] == '58,20' else w for w in self.row()])

    def test_area_numeric_gate_identity_preserved(self):
        rows = [word(t, x) for t, x in [('3007', 38), ('ENTRY', 94), ('3010', 193), ('EXIT', 249),
                    ('24,00', 352), ('3,50', 387), ('5,50', 425), ('7,60', 462), ('10,20', 496), ('1,60', 537)]]
        self.assertEqual(['3007', 'ENTRY', '3010', 'EXIT', '24.00', '3.50'], CONVERTER.decode_row(rows, 'AREA')[:6])


if __name__ == '__main__':
    unittest.main()
