import urllib.request
import urllib.parse
import json

queries = [
    'Denver Crime',
    'Dallas Police',
    'Philadelphia Crime',
    'Seattle Real Time Fire 911',
    'Detroit Crime',
    'Austin Crime',
    'Charlotte Mecklenburg Crime',
    'Kansas City Crime',
    'Minneapolis Police Incidents',
    'Phoenix Crime',
    'Memphis Crime',
    'Indianapolis Crime',
    'Nashville Police',
    'Columbus Police',
    'San Antonio Police',
    'San Jose Police',
    'San Francisco Police',
    'San Diego Police Calls',
    'Virginia State Police CAD',
    'Maryland Police Incidents',
    'Georgia DOT Navigational Hazards'
]

results = {}
for q in queries:
    try:
        url = f"https://opendata.arcgis.com/api/v3/datasets?q={urllib.parse.quote(q)}&page[size]=2"
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        items = res.get('data', [])
        print(f"\n=== {q} ({len(items)}) ===")
        for item in items:
            attrs = item.get('attributes', {})
            name = attrs.get('name')
            srv_url = attrs.get('url')
            print(f"  - {name}: {srv_url}")
    except Exception as e:
        print(f"Error {q}: {e}")
