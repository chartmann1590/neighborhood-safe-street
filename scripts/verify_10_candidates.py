import urllib.request
import json
import time

endpoints = {
    'loudoun_cad': 'https://services1.arcgis.com/MxjRokvPm7bjslyR/arcgis/rest/services/CAD_CuurentTrafficIncidents_UD/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'baltimore_crime': 'https://services1.arcgis.com/UWYHeuuJISiGmgXx/arcgis/rest/services/NIBRS_GroupA_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'boston_crime': 'https://services6.arcgis.com/u2Q4oAfciDZpDAD8/arcgis/rest/services/BostonCrime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'tampa_crime': 'https://services1.arcgis.com/IbNXlmt2RVVRCZ6M/arcgis/rest/services/crimes_public_365days/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'houston_crime': 'https://services.arcgis.com/aY6P1IjnU1hzETf0/arcgis/rest/services/HPD_RecentCrime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'stlouis_cad': 'https://services3.arcgis.com/IQJ58yRLLWrcV29u/arcgis/rest/services/CAD_EMS_NEW/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'colorado_hazards': 'https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'portland_crime': 'https://services.arcgis.com/HRPe58bUyBqyyiCt/arcgis/rest/services/PPB_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'boulder_cad': 'https://services.arcgis.com/ePKBjXrBZ2vEEgWd/arcgis/rest/services/Boulder_PD_Calls_For_Service/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'glendale_crime': 'https://services1.arcgis.com/9fVTQQSiODPjLUTa/arcgis/rest/services/GPD_CRIME_DATA_REDACTED/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
}

for name, url in endpoints.items():
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=10).read())
        feats = res.get('features', [])
        print(f"\n================ {name} ({len(feats)} features) ================")
        if feats:
            sample = feats[0]
            print("Geometry:", sample.get('geometry'))
            attrs = sample.get('attributes', {})
            print("Attributes keys:", list(attrs.keys()))
            print("Attribute sample:", {k: attrs[k] for k in list(attrs.keys())[:8]})
    except Exception as e:
        print(f"Error {name}: {e}")
