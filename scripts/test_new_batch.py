import urllib.request
import json

tests = {
    'tampa': 'https://services1.arcgis.com/IbNXlmt2RVVRCZ6M/arcgis/rest/services/crimes_public_365days/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'sacramento': 'https://services5.arcgis.com/54falWtcpty3V47Z/arcgis/rest/services/Sacramento_Call_for_Service_Data_2025/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'colorado': 'https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'portland': 'https://services.arcgis.com/HRPe58bUyBqyyiCt/arcgis/rest/services/PPB_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'
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
