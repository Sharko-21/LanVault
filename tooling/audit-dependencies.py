"""Query OSV for the resolved Windows graph and resolved Android runtime versions."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--cargo-metadata', type=Path, required=True)
parser.add_argument('--gradle-report', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
metadata = json.loads(args.cargo_metadata.read_text(encoding='utf-8-sig'))
nodes = {node['id']: node for node in metadata['resolve']['nodes']}
reachable = set()
queue = list(metadata['workspace_members'])
while queue:
    node = queue.pop()
    if node in reachable:
        continue
    reachable.add(node)
    queue.extend(nodes[node]['dependencies'])
packages = {('crates.io', package['name'], package['version']) for package in metadata['packages'] if package['id'] in reachable and (package.get('source') or '').startswith('registry+')}
for line in args.gradle_report.read_text(encoding='utf-8-sig').splitlines():
    # A module replacement can contain a full coordinate after the arrow.
    if ' -> ' in line and re.search(r' -> [\w.-]+:[\w.-]+:', line):
        line = line.split(' -> ', 1)[1]
    match = re.search(r'([\w.-]+):([\w.-]+):([0-9][\w.+-]*)(?: -> ([0-9][\w.+-]*))?', line)
    if match:
        group, artifact, version, selected = match.groups()
        packages.add(('Maven', group + ':' + artifact, selected or version))
packages = sorted(packages)
if not any(p[0] == 'Maven' for p in packages) or not any(p[0] == 'crates.io' for p in packages):
    raise SystemExit('Both resolved dependency graphs must be present')


def query(data):
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps(data).encode(), headers={'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(request, timeout=45))


def scan(batch):
    response = query({'queries': [{'package': {'ecosystem': ecosystem, 'name': name}, 'version': version} for ecosystem, name, version in batch]})
    if len(response['results']) != len(batch):
        raise RuntimeError('Incomplete OSV response')
    findings = []
    for package, result in zip(batch, response['results']):
        ids = {v['id'] for v in result.get('vulns', [])}
        while result.get('next_page_token'):
            result = query({'queries': [{'package': {'ecosystem': package[0], 'name': package[1]}, 'version': package[2], 'page_token': result['next_page_token']}]})['results'][0]
            ids.update(v['id'] for v in result.get('vulns', []))
        if ids:
            findings.append({'ecosystem': package[0], 'name': package[1], 'version': package[2], 'advisories': sorted(ids)})
    return findings


findings = []
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    for result in pool.map(scan, [packages[i:i + 150] for i in range(0, len(packages), 150)]):
        findings.extend(result)
report = {'provider': 'OSV', 'scope': 'filtered Cargo graph and Android runtime', 'scannedPackages': len(packages), 'findings': findings, 'packages': [{'ecosystem':e,'name':n,'version':v} for e,n,v in packages]}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k:report[k] for k in ['provider','scope','scannedPackages','findings']}))
raise SystemExit(bool(findings))
