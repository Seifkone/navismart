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


def download(url, limit):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname != 'voyage.aprr.fr':
        raise ValueError('Unexpected operator URL')
    request = urllib.request.Request(url, headers={'User-Agent': 'NaviSmart tariff maintenance/1'})
    with urllib.request.urlopen(request, timeout=45) as response:
        if urllib.parse.urlsplit(response.url).hostname != parsed.hostname:
            raise ValueError('Unexpected operator redirect')
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Oversized operator document')
    return data


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
            prepared.append((old, existing.read_bytes()))
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
    manifest = {'schema': 1, 'checkedAt': datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'),
                'networks': [metadata for metadata, _ in prepared]}
    output.mkdir(parents=True, exist_ok=True)
    for metadata, data in prepared:
        target = output / metadata['file']
        pending = target.with_suffix('.pending')
        pending.write_bytes(data)
        pending.replace(target)
    pending = manifest_file.with_suffix('.pending')
    pending.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    pending.replace(manifest_file)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    build(parser.parse_args().output)
