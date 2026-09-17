import urllib.request
import urllib.parse
import json

targets = [
    'Baltimore crime police',
    'Boston crime police',
    'San Jose police calls',
    'Phoenix police calls',
    'Atlanta police 911',
    'Miami police CAD',
    'San Antonio police incidents',
    'Milwaukee police calls',
    'Providence police crime',
    'Hartford police crime',
    'Honolulu police incidents',
    'Virginia police CAD',
    'Georgia 511 incidents',
    'Indiana DOT incidents',
    'Wisconsin DOT incidents',
    'Missouri state police incidents',
    'Texas DOT road conditions'
]

for t in targets:
    url = f"https://www.arcgis.com/sharing/rest/search?q={urllib.parse.quote(t)}+AND+type:%22Feature+Service%22&f=json&num=2"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=6).read())
        results = res.get('results', [])
        print(f"Target: {t} -> {len(results)} results")
        for r in results:
            print(f"  {r.get('title')}: {r.get('url')}")
    except Exception as e:
        print(f"Error {t}: {e}")
