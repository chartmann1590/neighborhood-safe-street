import urllib.request
import urllib.parse
import json

cad_queries = [
    'CAD 911 calls type:"Feature Service"',
    'Police CAD dispatches type:"Feature Service"',
    'Fire 911 dispatch type:"Feature Service"',
    'Active 911 calls type:"Feature Service"',
    'Traffic incidents live type:"Feature Service"',
    'Emergency incidents live type:"Feature Service"'
]

for q in cad_queries:
    url = f"https://www.arcgis.com/sharing/rest/search?q={urllib.parse.quote(q)}&f=json&num=6"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        results = res.get('results', [])
        print(f"=== {q} ({len(results)} found) ===")
        for r in results:
            print(f"  Title: {r.get('title')}, URL: {r.get('url')}")
    except Exception as e:
        print(f"Error {q}: {e}")
