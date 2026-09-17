import urllib.request
import json

checks = {
    'atlanta_part1': 'https://services.arcgis.com/kNxiwRZHjxrUW86Z/ArcGIS/rest/services/Part1_Week/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'houston_recent': 'https://services.arcgis.com/aY6P1IjnU1hzETf0/arcgis/rest/services/HPD_RecentCrime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'stl_crime': 'https://services2.arcgis.com/bB9Y1bGKerz1PTl5/arcgis/rest/services/STL_Crime_Stats/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'milwaukee_crime': 'https://services1.arcgis.com/O7h3OCRVxKceyg19/arcgis/rest/services/Hot_Spots_Crime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'sandiego_calls': 'https://services1.arcgis.com/eGSDp8lpKe5izqVc/arcgis/rest/services/SDPD_Service_Calls1_WFL1/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'
}

for name, u in checks.items():
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
