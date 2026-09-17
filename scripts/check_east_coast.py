import urllib.request
import json

tests = {
    'baltimore_nibrs': 'https://services1.arcgis.com/UWYHeuuJISiGmgXx/arcgis/rest/services/NIBRS_GroupA_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'virginia_calls': 'https://services2.arcgis.com/CyVvlIiUfRBmMQuu/arcgis/rest/services/Police_Calls_for_Service_/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'boston_crime': 'https://services6.arcgis.com/u2Q4oAfciDZpDAD8/arcgis/rest/services/BostonCrime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'miami_cad': 'https://services6.arcgis.com/trDj8btvplGdvJ9a/arcgis/rest/services/Police_CAD_Services/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'
}

for name, u in tests.items():
    try:
        req = urllib.request.Request(u, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"=== {name} ({len(feats)} feats) ===")
        if feats:
            print("  Attrs:", list(feats[0]['attributes'].keys())[:10])
            print("  Sample:", {k: feats[0]['attributes'][k] for k in list(feats[0]['attributes'].keys())[:6]})
            print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")
