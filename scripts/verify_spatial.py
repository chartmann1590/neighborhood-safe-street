import urllib.request
import urllib.parse
import json

cities = [
    ('Loudoun CAD', -77.74, 38.96, 'https://services1.arcgis.com/MxjRokvPm7bjslyR/arcgis/rest/services/CAD_CuurentTrafficIncidents_UD/FeatureServer/0'),
    ('Baltimore Crime', -76.61, 39.36, 'https://services1.arcgis.com/UWYHeuuJISiGmgXx/arcgis/rest/services/NIBRS_GroupA_Crime_Data/FeatureServer/0'),
    ('Boston Crime', -71.01, 42.39, 'https://services6.arcgis.com/u2Q4oAfciDZpDAD8/arcgis/rest/services/BostonCrime/FeatureServer/0'),
    ('Tampa Crime', -82.53, 27.94, 'https://services1.arcgis.com/IbNXlmt2RVVRCZ6M/arcgis/rest/services/crimes_public_365days/FeatureServer/0'),
    ('Houston Crime', -95.32, 29.83, 'https://services.arcgis.com/aY6P1IjnU1hzETf0/arcgis/rest/services/HPD_RecentCrime/FeatureServer/0'),
    ('St. Louis CAD', -90.92, 38.96, 'https://services3.arcgis.com/IQJ58yRLLWrcV29u/arcgis/rest/services/CAD_EMS_NEW/FeatureServer/0'),
    ('Colorado Hazards', -106.13, 38.83, 'https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0'),
    ('Portland Crime', -122.66, 45.52, 'https://services.arcgis.com/HRPe58bUyBqyyiCt/arcgis/rest/services/PPB_Crime_Data/FeatureServer/0'),
    ('Boulder CAD', -105.23, 39.98, 'https://services.arcgis.com/ePKBjXrBZ2vEEgWd/arcgis/rest/services/Boulder_PD_Calls_For_Service/FeatureServer/0'),
    ('Minneapolis Crime', -93.29, 44.99, 'https://services.arcgis.com/afSMGVsC7QlRK1kZ/arcgis/rest/services/Police_Incidents_2025/FeatureServer/0'),
    ('Princeton CAD', -96.50, 33.20, 'https://services6.arcgis.com/KL1aiRJt0tw3BM7h/arcgis/rest/services/Princeton_Calls_For_Service_2026/FeatureServer/0')
]

for name, lon, lat, base_url in cities:
    url = f"{base_url}/query?geometryType=esriGeometryPoint&geometry={lon},{lat}&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=3"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=10).read())
        feats = res.get('features', [])
        print(f"PASS {name}: returned {len(feats)} features")
    except Exception as e:
        print(f"FAIL {name}: {e}")
