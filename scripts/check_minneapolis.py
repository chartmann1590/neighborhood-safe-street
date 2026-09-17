import urllib.request
import json

url = 'https://services.arcgis.com/afSMGVsC7QlRK1kZ/arcgis/rest/services/Police_Incidents_2025/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2'
req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
res = json.loads(urllib.request.urlopen(req).read())
feats = res.get('features', [])
for f in feats:
    print(f['attributes'])
