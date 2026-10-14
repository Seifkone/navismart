#!/usr/bin/env python3
"""Convert the official APRR PDF by column coordinates, never by splitting station names.

Requires pdfplumber. Any changed layout or malformed row aborts before publishing output.
This converter does not infer reverse tariffs, road topology or national coverage.
"""
import argparse
import csv
import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

HEADER = ['gare_entree', 'gare_sortie', 'distance_tarifaire_km'] + [f'tarif_classe_{i}' for i in range(1, 6)]
AREA_HEADER = ['id_gare_entree', 'libelle_gare_entree', 'id_gare_sortie', 'libelle_gare_sortie', 'distance_tarifaire_km'] + [f'tarif_classe_{i}' for i in range(1, 6)]
LIMITS = [150, 264, 304, 347, 390, 433, 476, 520]


def decode_row(words, network='APRR'):
    """One physical row in the known APRR layout; cells may contain several name words."""
    limits = LIMITS if network == 'APRR' else [84, 183, 239, 339, 377, 413, 450, 487, 525, 563]
    columns = [[] for _ in limits]
    for word in sorted(words, key=lambda w: w['x0']):
        x = word['x0']
        if x < 35 or x >= limits[-1]:
            raise ValueError('Unexpected content outside tariff columns')
        index = next(i for i, right in enumerate(limits) if x < right)
        if word['text'] not in ('€', '\uFFFD'):
            columns[index].append(word['text'])
    cells = [' '.join(c).strip() for c in columns]
    names = cells[:2] if network == 'APRR' else [cells[1], cells[3]]
    numeric_start = 2 if network == 'APRR' else 4
    if not all(names) or names[0] == names[1]:
        raise ValueError('Missing or self tariff station')
    if network == 'AREA' and not all(re.fullmatch(r'\d{1,12}', cells[i]) for i in (0, 2)):
        raise ValueError('Invalid AREA station code')
    for i in range(numeric_start, len(cells)):
        if not re.fullmatch(r'\d+,\d{2}', cells[i]):
            raise ValueError(f'Invalid numeric column {i}: {cells[i]!r}')
        amount = Decimal(cells[i].replace(',', '.'))
        if amount > (5000 if i == numeric_start else 10000):
            raise ValueError('Tariff value too large')
        cells[i] = str(amount)
    return cells


def extract_aprr(path, network='APRR'):
    import pdfplumber
    rows, seen = [], set()
    with pdfplumber.open(path) as pdf:
        if not 2 <= len(pdf.pages) <= 500:
            raise ValueError('Unexpected PDF page count')
        cover = pdf.pages[0].extract_text() or ''
        match = re.search(r'En vigueur au (\d{1,2})(?:er)? (février|janvier|juin) (20\d\d)', cover)
        if not match:
            raise ValueError('Missing explicit tariff effective date')
        month = {'janvier': 1, 'février': 2, 'juin': 6}[match[2]]
        effective = f'{match[3]}-{month:02}-{int(match[1]):02}'
        for number, page in enumerate(pdf.pages[1:], 2):
            words = [w for w in page.extract_words() if (105 if network == 'APRR' else 95) < w['top'] < 795]
            groups = []
            for word in sorted(words, key=lambda w: (w['top'], w['x0'])):
                if not groups or word['top'] - groups[-1][0]['top'] > 1:
                    groups.append([])
                groups[-1].append(word)
            for group in groups:
                row = decode_row(group, network)
                pair = tuple(row[:2]) if network == 'APRR' else (row[0], row[2])
                if pair in seen:
                    raise ValueError(f'Duplicate tariff pair on page {number}: {pair}')
                seen.add(pair)
                rows.append(row)
            if not groups:
                raise ValueError(f'Empty data page {number}')
            page.close()
    entries = {r[0] for r in rows}
    exits = {r[1 if network == 'APRR' else 2] for r in rows}
    if not (100 if network == 'APRR' else 20) <= len(entries | exits) <= 500 or len(rows) < (10000 if network == 'APRR' else 500):
        raise ValueError('Implausible number of stations or rows')
    # A few open-system/special stations occur only in one column. Thousands of
    # unmatched labels, as in the rejected text extraction, are never acceptable.
    if len(entries ^ exits) > 50:
        raise ValueError(f'Unbalanced closed-network station names: {entries ^ exits}')
    return effective, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pdf', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--network', choices=['APRR', 'AREA'], default='APRR')
    args = parser.parse_args()
    effective, rows = extract_aprr(args.pdf, args.network)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.pending')
    with temporary.open('w', encoding='utf-8', newline='') as target:
        writer = csv.writer(target)
        writer.writerow(HEADER if args.network == 'APRR' else AREA_HEADER)
        writer.writerows(rows)
    temporary.replace(args.output)
    print(json.dumps({'network': args.network, 'effectiveFrom': effective, 'pairs': len(rows),
                      'gates': len({r[0] for r in rows}),
                      'pdfSha256': hashlib.sha256(args.pdf.read_bytes()).hexdigest(),
                      'csvSha256': hashlib.sha256(args.output.read_bytes()).hexdigest()}, ensure_ascii=False))


if __name__ == '__main__':
    main()
