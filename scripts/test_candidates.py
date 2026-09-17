import urllib.request
import json

tests = [
    ('Dallas Police Active Calls', 'https://www.dallasopendata.com/resource/9fxf-t2tr.json?$limit=5'),
    ('Philadelphia Crime', 'https://services.arcgis.com/P3ePLMYs2RVChkJx/ArcGIS/rest/services/Philadelphia_Crime_Map_WFL1/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'),
    ('CMPD Violent Crime', 'https://gis.charlottenc.gov/arcgis/rest/services/ODP/ViolentCrimeData/MapServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'),
    ('Detroit Crime Geocoded', 'https://services.arcgis.com/7CRlmWNEbeCqEJ6a/arcgis/rest/services/Detroit_crime_geocoded/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3')
]

for name, url in tests:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        content = urllib.request.urlopen(req, timeout=8).read()
        data = json.loads(content)
        if isinstance(data, list):
            print(f"=== {name} (list {len(data)}) ===")
            if data:
                print("  Keys:", list(data[0].keys())[:8])
                print("  Sample:", data[0])
        elif isinstance(data, dict):
            feats = data.get('features', [])
            print(f"=== {name} (features {len(feats)}) ===")
            if feats:
                print("  Attrs:", list(feats[0]['attributes'].keys())[:8])
                print("  Sample:", feats[0]['attributes'])
                print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")
