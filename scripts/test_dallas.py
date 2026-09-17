import urllib.request
import json

url = 'https://www.dallasopendata.com/resource/9fxf-t2tr.json?$limit=5'
req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
res = json.loads(urllib.request.urlopen(req).read())
for r in res:
    print(r)
