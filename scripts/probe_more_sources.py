import urllib.request
import urllib.parse
import json

candidates = {
    # Atlanta
    'atlanta_arcgis': 'https://services5.arcgis.com/Z57Q9sh3tzj0aJtm/arcgis/rest/services/Atlanta_Police_Crime_Reports__UCR__2009_to_March_2020/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    # Baltimore
    'baltimore_open': 'https://data.baltimorecity.gov/resource/448v-2527.json?$limit=2',
    'baltimore_cad': 'https://data.baltimorecity.gov/resource/m8be-4543.json?$limit=2',
    # Boston
    'boston_crime': 'https://data.boston.gov/api/3/action/datastore_search?resource_id=12cb3883-56f5-47de-afa5-3b199464006c&limit=2',
    # San Diego
    'sandiego_calls': 'https://services1.arcgis.com/eGSDp8lpKe5izqVc/arcgis/rest/services/SDPD_Service_Calls/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    # San Jose
    'sanjose_calls': 'https://data.sanjoseca.gov/api/3/action/datastore_search?resource_id=police-calls-for-service&limit=2',
    # Portland
    'portland_crime': 'https://services.arcgis.com/HRPe58bUyBqyyiCt/arcgis/rest/services/PPB_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    # Louisville
    'louisville_crime': 'https://data.louisvilleky.gov/api/3/action/datastore_search',
    # Richmond
    'richmond_crime': 'https://services3.arcgis.com/dty2kHktVXHrqO8i/arcgis/rest/services/Crime_Incidents_P1RMS/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    # Colorado CDOT
    'colorado_closures': 'https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    # Minnesota
    'minnesota_incidents': 'https://services.arcgis.com/qWbGMYB49y8mLbRt/arcgis/rest/services/Road_Incident_Management_Plans_-_Feature_Layer_view/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
}

for name, url in candidates.items():
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        print(f"SUCCESS: {name}")
    except Exception as e:
        print(f"FAIL {name}: {e}")
