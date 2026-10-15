#!/usr/bin/env python3
"""Refresh the static tariff pack from official operator PDFs. No route service is used."""
import argparse
import csv
import hashlib
import io
import json
import re
import tempfile
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from build_toll_catalog import HEADER, AREA_HEADER, extract_aprr

PAGE = 'https://voyage.aprr.fr/tarifs-de-peage'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def download(url, limit, hosts=('voyage.aprr.fr',)):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in hosts:
        raise ValueError('Unexpected operator URL')
    request = urllib.request.Request(url, headers={'User-Agent': 'NaviSmart tariff maintenance/1'})
    with urllib.request.urlopen(request, timeout=45) as response:
        if urllib.parse.urlsplit(response.url).hostname not in hosts:
            raise ValueError('Unexpected operator redirect')
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Oversized operator document')
    return data


def geography():
    from pyproj import Transformer
    metadata_url = 'https://www.data.gouv.fr/api/1/datasets/gares-de-peage-du-reseau-routier-national-concede/'
    metadata = json.loads(download(metadata_url, 500_000, ('www.data.gouv.fr',)))
    resources = []
    for resource in metadata['resources']:
        title = resource.get('title', '')
        year = re.search(r'(?i)^gares[-_]peage_(20\d\d)$', title)
        if year and resource.get('format', '').lower() == 'csv':
            resources.append((int(year[1]), resource['url']))
    if not resources:
        raise ValueError('No supported government gate reference')
    year, url = max(resources)
    if not url.startswith('https://static.data.gouv.fr/resources/gares-de-peage-du-reseau-routier-national-concede/'):
        raise ValueError('Unexpected government gate resource')
    raw = download(url, 1_000_000, ('static.data.gouv.fr',))
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig')), delimiter=';')
    if not {'nomGare', 'x', 'y', 'dateReferentiel'} <= set(reader.fieldnames or []):
        raise ValueError('Unsupported government gate columns')
    transformer = Transformer.from_crs(2154, 4326, always_xy=True)
    rows = []
    for record in reader:
        name = record['nomGare'].strip()
        if not name or len(name) > 200 or any(c in name for c in '\n\r\t'):
            raise ValueError('Invalid government gate name')
        x, y = (float(record[k].replace(',', '.')) for k in ('x', 'y'))
        lon, lat = transformer.transform(x, y)
        if not -6 < lon < 10 or not 41 < lat < 52:
            raise ValueError('Government gate outside expected geographic bounds')
        rows.append([name, f'{lat:.7f}', f'{lon:.7f}'])
    if not 500 <= len(rows) <= 3000:
        raise ValueError('Unexpected government gate count')
    buffer = io.StringIO(newline='')
    writer = csv.writer(buffer, lineterminator='\n')
    writer.writerow(['name', 'latitude', 'longitude'])
    writer.writerows(rows)
    data = buffer.getvalue().encode('utf-8')
    return {'file': 'gates.csv', 'sha256': digest(data), 'locations': len(rows), 'sourceUrl': url, 'referenceYear': year}, data


def build(output):
    page = download(PAGE, 2_000_000).decode('utf-8')
    previous = {}
    manifest_file = output / 'manifest.json'
    if manifest_file.exists():
        previous = {n['id']: n for n in json.loads(manifest_file.read_text(encoding='utf-8'))['networks']}
    prepared = []
    for network in ('APRR', 'AREA'):
        matches = re.findall(r'(?:https://voyage\.aprr\.fr)?(/sites/default/files/[^"\s<>]+/TARIFS_' + network + r'\.pdf)', page)
        urls = sorted(set('https://voyage.aprr.fr' + path for path in matches))
        if len(urls) != 1:
            raise ValueError(f'Expected one current official {network} table, found {len(urls)}')
        source_url = urls[0]
        pdf = download(source_url, 20_000_000)
        if not pdf.startswith(b'%PDF-'):
            raise ValueError('Operator response is not a PDF')
        old = previous.get(network)
        existing = output / f'{network}.csv'
        if old and old.get('pdfSha256') == digest(pdf) and existing.exists() and digest(existing.read_bytes()) == old['sha256']:
            prepared.append((dict(old, sourceUrl=source_url), existing.read_bytes()))
            continue
        with tempfile.TemporaryDirectory(prefix='navismart-tariffs-') as temporary:
            document = Path(temporary) / f'{network}.pdf'
            document.write_bytes(pdf)
            effective, rows = extract_aprr(document, network)
        if datetime.fromisoformat(effective).date() > datetime.now(timezone.utc).date():
            raise ValueError('Future tariff pack; preserve the current pack until effective')
        if old and (effective < old['effectiveFrom'] or len(rows) < old['pairs'] * .98):
            raise ValueError('Tariff date or coverage regression')
        buffer = io.StringIO(newline='')
        writer = csv.writer(buffer, lineterminator='\n')
        writer.writerow(HEADER if network == 'APRR' else AREA_HEADER)
        writer.writerows(rows)
        data = buffer.getvalue().encode('utf-8')
        gates = {r[0] for r in rows} | {r[1 if network == 'APRR' else 2] for r in rows}
        metadata = {'id': network, 'file': f'{network}.csv', 'sourceUrl': source_url,
                    'effectiveFrom': effective, 'pairs': len(rows), 'gates': len(gates),
                    'pdfSha256': digest(pdf), 'sha256': digest(data)}
        prepared.append((metadata, data))
    # All sources must pass before any published file is changed. The manifest is written last;
    # readers verify all checksums and keep their previous pack if they see an in-flight update.
    geo_metadata, geo_data = geography()
    manifest = {'schema': 1, 'checkedAt': datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'),
                'networks': [metadata for metadata, _ in prepared], 'geography': geo_metadata}
    output.mkdir(parents=True, exist_ok=True)
    for metadata, data in prepared:
        target = output / metadata['file']
        pending = target.with_suffix('.pending')
        pending.write_bytes(data)
        pending.replace(target)
    (output / 'gates.pending').write_bytes(geo_data)
    (output / 'gates.pending').replace(output / 'gates.csv')
    pending = manifest_file.with_suffix('.pending')
    pending.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    pending.replace(manifest_file)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    build(parser.parse_args().output)
