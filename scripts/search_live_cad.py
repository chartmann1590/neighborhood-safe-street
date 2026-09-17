import urllib.request
import urllib.parse
import json

terms = [
    'traffic incidents',
    'calls for service',
    'active incidents',
    'emergency dispatch',
    'police calls',
    'fire calls',
    'real time crime',
    'cad incidents'
]

found = []
for term in terms:
    try:
        url = f"https://opendata.arcgis.com/api/v3/datasets?q={urllib.parse.quote(term)}&page[size]=10"
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=10).read())
        data = res.get('data', [])
        for d in data:
            attr = d.get('attributes', {})
            name = attr.get('name', '')
            srv_url = attr.get('url', '')
            org = attr.get('organization', '')
            if srv_url and ('FeatureServer' in srv_url or 'MapServer' in srv_url):
                found.append((name, srv_url, org))
    except Exception as e:
        print(f"Error {term}: {e}")

print(f"Found {len(found)} candidate layers:")
unique = {}
for name, url, org in found:
    if url not in unique:
        unique[url] = (name, org)

for url, (name, org) in list(unique.items())[:30]:
    print(f"- [{org}] {name}")
    print(f"  {url}")
