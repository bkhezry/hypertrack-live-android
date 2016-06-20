package io.hypertrack.meta.view;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.AutocompletePrediction;
import com.google.android.gms.location.places.Place;
import com.google.android.gms.location.places.PlaceBuffer;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import io.hypertrack.lib.consumer.utils.HTCircleImageView;
import io.hypertrack.lib.transmitter.model.HTTrip;
import io.hypertrack.meta.model.MetaPlace;
import io.hypertrack.meta.model.TripETAResponse;
import io.hypertrack.meta.MetaApplication;
import io.hypertrack.meta.adapter.PlaceAutocompleteAdapter;
import io.hypertrack.meta.R;
import io.hypertrack.meta.service.RegistrationIntentService;
import io.hypertrack.meta.store.TripManager;
import io.hypertrack.meta.store.UserStore;
import io.hypertrack.meta.store.callback.TripETACallback;
import io.hypertrack.meta.store.callback.TripManagerCallback;
import io.hypertrack.meta.store.callback.TripManagerListener;
import io.hypertrack.meta.util.Constants;
import io.hypertrack.meta.util.PhoneUtils;

public class Home extends AppCompatActivity implements ResultCallback<Status>, LocationListener, OnMapReadyCallback, GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

    public static final int LOITERING_DELAY_MS = 30000;
    private static final String TAG = AppCompatActivity.class.getSimpleName();
    private static final String GEOFENCE_REQUEST_ID = "geofence";
    private static final float GEOFENCE_RADIUS_IN_METERS = 100;
    private static final int REQUEST_SHARE_CONTACT_CODE = 1;
    private static final long INTERVAL_TIME = 5000;

    protected GoogleApiClient mGoogleApiClient;
    private GoogleMap mMap;
    private PlaceAutocompleteAdapter mAdapter;
    private AutoCompleteTextView mAutocompleteView;
    private LatLngBounds mBounds;
    private LatLng currentLocation;
    private Marker currentLocationMarker;
    private Marker destinationLocationMarker;
    private Button sendETAButton;
    private ProgressDialog mProgressDialog;
    private InputMethodManager mIMEMgr;
    private SupportMapFragment mMapFragment;
    private Button shareButton;
    private Bitmap profilePicBitmap;
    private HTCircleImageView profileViewProfileImage;
    private View customMarkerView;

    private TripManager tripManager = new TripManager();

    Target target = new Target() {
        @Override
        public void onBitmapLoaded(final Bitmap bitmap, Picasso.LoadedFrom from) {
            profilePicBitmap = bitmap;
        }

        @Override
        public void onBitmapFailed(Drawable errorDrawable) {
            profilePicBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.default_profile_pic);
        }

        @Override
        public void onPrepareLoad(Drawable placeHolderDrawable) {
            profilePicBitmap = BitmapFactory.decodeResource(getResources(),R.drawable.default_profile_pic);
        }
    };

    private void onSelectPlace(Place place) {
        final Place selectedPlaces = place.freeze();

        this.getEtaForDestination(selectedPlaces.getLatLng(), new TripETACallback() {
            @Override
            public void OnSuccess(TripETAResponse etaResponse) {
                onETASuccess(etaResponse, selectedPlaces);
            }

            @Override
            public void OnError() {
                destinationLocationMarker.remove();
            }
        });
    }

    private void onETASuccess(TripETAResponse response, Place place) {
        showSendETAButton();
        updateDestinationMarker(place.getLatLng(), (int)response.getDuration()/60);
        updateMapBounds(place.getLatLng());
        this.tripManager.setPlace(new MetaPlace(place));
    }

    /**
     * Callback for results from a Places Geo Data API query that shows the first place result in
     * the details view on screen.
     */
    private ResultCallback<PlaceBuffer> mUpdatePlaceDetailsCallback
            = new ResultCallback<PlaceBuffer>() {
        @Override
        public void onResult(PlaceBuffer places) {
            if (!places.getStatus().isSuccess()) {
                // Request did not complete successfully
                Log.d(TAG, "MetaPlace query did not complete. Error: " + places.getStatus().toString());
                places.release();
                return;
            }

            if (places.getCount() == 0) {
                Log.d(TAG, "Places is empty");
                places.release();
                return;
            }

            // Get the MetaPlace object from the buffer.
            final Place place = places.get(0);

            Log.i(TAG, "MetaPlace details received: " + place.getName());
            mIMEMgr.hideSoftInputFromWindow(mAutocompleteView.getWindowToken(), 0);
            onSelectPlace(place);
            places.release();
        }
    };

    private TextWatcher mTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            String constraint = s != null ? s.toString() : "";

            if (constraint.length() > 0 ) {
                mAdapter.getFilter().filter(constraint);
            }
        }
    };

    private AdapterView.OnItemClickListener mAutocompleteClickListener
            = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            final AutocompletePrediction item = mAdapter.getItem(position);
            final String placeId = item.getPlaceId();
            final CharSequence primaryText = item.getPrimaryText(null);
            Log.d(TAG, "Autocomplete item selected: " + primaryText);

            PendingResult<PlaceBuffer> placeResult = Places.GeoDataApi
                    .getPlaceById(mGoogleApiClient, placeId);
            placeResult.setResultCallback(mUpdatePlaceDetailsCallback);
        }
    };

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Extract data included in the Intent
            boolean result = intent.getBooleanExtra("end_trip", false);

            if (result)
//                endTrip();//endTrip();

            Log.d("receiver", "Got message: " + result);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapsInitializer.initialize(getApplicationContext());
        checkIfUserIsOnBoard();

        setContentView(R.layout.activity_home);

        mMapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initGoogleClient();

        Intent intent = new Intent(this, RegistrationIntentService.class);
        startService(intent);

        mIMEMgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        mMapFragment.getMapAsync(this);

        mAdapter = new PlaceAutocompleteAdapter(this, mGoogleApiClient, mBounds,
                null);

        mAutocompleteView = (AutoCompleteTextView)
                findViewById(R.id.autocomplete_places);
        mAutocompleteView.setAdapter(mAdapter);
        mAutocompleteView.setOnItemClickListener(mAutocompleteClickListener);
        mAutocompleteView.addTextChangedListener(mTextWatcher);

        shareButton = (Button) findViewById(R.id.shareButton);
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                shareUrlViaShare();
            }
        });

        if (!TextUtils.equals("None", getImageToPreferences())) {
            profilePicBitmap = decodeToBase64(getImageToPreferences());
        } else {
            profilePicBitmap = BitmapFactory.decodeResource(getResources(),R.drawable.default_profile_pic);
        }

        setUpShareEtaButton();

        if (!isConnectedToInternet()) {
            Toast.makeText(this, "We could not detect internet on your mobile or there seems to be connectivity issues.", Toast.LENGTH_LONG).show();
        }
    }

    private void checkIfUserIsOnBoard() {
        boolean isUserOnboard = UserStore.isUserLoggedIn();
        if (!isUserOnboard) {
            startActivity(new Intent(this, Register.class));
            finish();
        } else {
            UserStore.sharedStore.initializeUser();
        }
    }

    private boolean isConnectedToInternet() {
        ConnectivityManager cm =
                (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();

        return isConnected;
    }

    private String getImageToPreferences() {
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sharedPreferences.getString(Constants.USER_PROFILE_PIC_ENCODED, "None");
    }

    public static Bitmap decodeToBase64(String decodeImageString) {
        byte[] decodedByte = Base64.decode(decodeImageString, 0);
        return BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
    }

    private void initGoogleClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .enableAutoManage(this, 0 /* clientId */, this)
                .addApi(Places.GEO_DATA_API)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .build();
    }

    private Bitmap createDrawableFromView(Context context, View view) {

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        view.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT));
        view.measure(displayMetrics.widthPixels, displayMetrics.heightPixels);
        view.layout(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels);
        view.buildDrawingCache();
        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);

        return bitmap;
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Mumbai, India.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);

        int paddingInDpForTop = 175;
        int paddingInDpForBottom = 50;
        final float scale = getResources().getDisplayMetrics().density;
        int paddingInPxForTop = (int) (paddingInDpForTop * scale + 0.5f);
        int paddingInPxForBottom = (int) (paddingInDpForBottom * scale + 0.5f);

        mMap.setPadding(0,paddingInPxForTop,0,paddingInPxForBottom);
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "onConnectionFailed: ConnectionResult.getErrorCode() = "
                + connectionResult.getErrorCode());

        // TODO(Developer): Check error code and notify the user of error state and resolution.
        Toast.makeText(this,
                "Could not connect to Google API Client: Error " + connectionResult.getErrorCode(),
                Toast.LENGTH_SHORT).show();
    }

    private void addMarkerToCurrentLocation() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            Location location = null;

            try{
                location = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            } catch (SecurityException exception) {
                Toast.makeText(Home.this, "Unable to request for location. Please check permissions in app settings.", Toast.LENGTH_LONG).show();
            }

            if (location != null) {
                if (currentLocationMarker != null)
                    currentLocationMarker.remove();

                mBounds = getBounds(location, 100000);

                currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
                updateCurrentMarkerLocation(location);

                CameraPosition cameraPosition =
                        new CameraPosition.Builder()
                                .target(currentLocation)
                                .zoom(mMap.getCameraPosition().zoom >= 16 ? mMap.getCameraPosition().zoom : 16)
                                .build();

                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
            }
        }
    }

    private void updateDestinationMarker(LatLng destinationLocation, int etaInMinutes) {
        if (destinationLocationMarker != null) {
            destinationLocationMarker.remove();
        }

        View markerView = this.getDestinationMarkerView(etaInMinutes);

        this.destinationLocationMarker = mMap.addMarker(new MarkerOptions()
                .position(destinationLocation)
                .icon(BitmapDescriptorFactory.fromBitmap(createDrawableFromView(this,markerView))));
    }

    private View getDestinationMarkerView(int etaInMinutes) {
        View marker = ((LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(R.layout.custom_destination_marker_layout, null);
        TextView etaTextView = (TextView) marker.findViewById(R.id.eta_txt);
        this.updateTextViewForMinutes(etaTextView, etaInMinutes);
        return marker;
    }

    private void getEtaForDestination(LatLng destinationLocation, final TripETACallback callback) {
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage("Getting ETA for the selected destination");
        mProgressDialog.setCancelable(false);
        mProgressDialog.show();

        if (currentLocation == null || destinationLocation == null) {
            return;
        }

        tripManager.getETA(currentLocation, destinationLocation, new TripETACallback() {
            @Override
            public void OnSuccess(TripETAResponse etaResponse) {
                mProgressDialog.dismiss();
                callback.OnSuccess(etaResponse);
            }

            @Override
            public void OnError() {
                mProgressDialog.dismiss();
                callback.OnError();
            }
        });
    }

    private void showSendETAButton() {
        sendETAButton.setText("Send ETA");
        sendETAButton.setVisibility(View.VISIBLE);
    }

    private void setUpShareEtaButton() {
        sendETAButton = (Button) findViewById(R.id.shareEtaButton);
        sendETAButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!tripManager.isTripActive()) {
                    startTrip();
                } else {
                    endTrip();
                }
            }
        });
    }

    private void startTrip() {
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setMessage("Fetching URL to share... ");
        mProgressDialog.show();

        this.tripManager.startTrip(new TripManagerCallback() {
            @Override
            public void OnSuccess() {
                mProgressDialog.dismiss();
                onTripStart();
            }

            @Override
            public void OnError() {
                mProgressDialog.dismiss();
            }
        });
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.v(TAG, "mGoogleApiClient is connected");
        addMarkerToCurrentLocation();
        requestForLocationUpdates();
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    private void requestForLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(INTERVAL_TIME);
        locationRequest.setFastestInterval(INTERVAL_TIME);

        try {
            LocationServices.FusedLocationApi.requestLocationUpdates(
                    mGoogleApiClient, locationRequest, this);
        } catch (SecurityException exception) {
            Toast.makeText(Home.this, "Unable to request for location. Please check permissions in app settings.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        updateCurrentMarkerLocation(location);
    }

    private void updateCurrentMarkerLocation(Location location) {
        if (currentLocationMarker != null)
            currentLocationMarker.remove();

        customMarkerView = ((LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(R.layout.custom_car_marker, null);
        profileViewProfileImage = (HTCircleImageView) customMarkerView.findViewById(R.id.profile_image);
        profileViewProfileImage.setImageBitmap(profilePicBitmap);

        currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
        currentLocationMarker = mMap.addMarker(
                new MarkerOptions()
                        .position(currentLocation)
                        .icon(BitmapDescriptorFactory.fromBitmap(createDrawableFromView(this,customMarkerView))));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SHARE_CONTACT_CODE) {
            if (resultCode == RESULT_OK) {
                this.didSelectContact(data);
            }
        }
    }

    private void didSelectContact(Intent data) {
        Uri uri = data.getData();
        String[] projection = { ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME };

        Cursor cursor = getContentResolver().query(uri, projection,
                null, null, null);
        cursor.moveToFirst();

        int numberColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
        String number = cursor.getString(numberColumnIndex);

        int nameColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
        String name = cursor.getString(nameColumnIndex);
        number = number.replaceAll("\\s","");
        Log.d(TAG, "Number : " + number + " , name : "+name);

        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        try {

            String locale = PhoneUtils.getCountryRegionFromPhone(this);
            Phonenumber.PhoneNumber phoneNumber = phoneUtil.parse(number, locale);
            Log.v(TAG, String.valueOf(phoneNumber.hasCountryCode()));

            boolean isValid = phoneUtil
                    .isValidNumber(phoneNumber);

            if (isValid) {
                String internationalFormat = phoneUtil.format(
                        phoneNumber,
                        PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);

                number = internationalFormat;

            }

        } catch (NumberParseException e) {
            System.err.println("NumberParseException was thrown: " + e.toString());
        }
    }

    private LatLngBounds getBounds(Location location, int mDistanceInMeters ){
        double latRadian = Math.toRadians(location.getLatitude());

        double degLatKm = 110.574235;
        double degLongKm = 110.572833 * Math.cos(latRadian);
        double deltaLat = mDistanceInMeters / 1000.0 / degLatKm;
        double deltaLong = mDistanceInMeters / 1000.0 / degLongKm;

        double minLat = location.getLatitude() - deltaLat;
        double minLong = location.getLongitude() - deltaLong;
        double maxLat = location.getLatitude() + deltaLat;
        double maxLong = location.getLongitude() + deltaLong;

        LatLngBounds.Builder b = new LatLngBounds.Builder();
        b.include(new LatLng(minLat, minLong));
        b.include(new LatLng(maxLat, maxLong));
        LatLngBounds bounds = b.build();

        return bounds;
    }

    private void endTrip() {

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setMessage("Stopping trip ... ");
        mProgressDialog.show();

        this.tripManager.endTrip(new TripManagerCallback() {
            @Override
            public void OnSuccess() {
                mProgressDialog.dismiss();
                OnTripEnd();
            }

            @Override
            public void OnError() {
                mProgressDialog.dismiss();
            }
        });
    }

    @Override
    public void onResult(Status status) {
        if (status.isSuccess()) {
            Log.v(TAG, "Geofencing added successfully");
        } else {
            Log.v(TAG, "Geofencing not added. There was an error");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mProgressDialog != null) mProgressDialog.dismiss();
        MetaApplication.getInstance().cancelPendingRequests(TAG);
    }

    // New Methods

    private void updateMapBounds(LatLng destinationLocation) {
        if (destinationLocation == null) {
            return;
        }

        LatLngBounds bounds = new LatLngBounds.Builder().include(currentLocation).include(destinationLocation).build();

        CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 50);
        mMap.animateCamera(cameraUpdate);
    }

    private void updateTextViewForMinutes(TextView textView, int etaInMinutes) {
        textView.setText(etaInMinutes + " m");
    }

    private void updateETAForOnGoingTrip() {
        HTTrip trip = this.tripManager.getHyperTrackTrip();

        Date ETA = trip.getETA();
        Date now = new Date();

        long etaInSecond = ETA.getTime() - now.getTime();
        int etaInMinutes = (int) etaInSecond / 60;

        LatLng destinationLocation = new LatLng(trip.getEndLocation().getCoordinates()[1], trip.getEndLocation().getCoordinates()[0]);

        this.updateDestinationMarker(destinationLocation, etaInMinutes);
    }

    private void onTripStart() {
        sendETAButton.setText("End Trip");
        shareButton.setVisibility(View.VISIBLE);

        this.tripManager.setTripRefreshedListener(new TripManagerListener() {
            @Override
            public void OnCallback() {
                updateETAForOnGoingTrip();
            }
        });

        this.tripManager.setTripEndedListener(new TripManagerListener() {
            @Override
            public void OnCallback() {
                OnTripEnd();
            }
        });
    }

    private void OnTripEnd() {
        sendETAButton.setVisibility(View.GONE);
        shareButton.setVisibility(View.GONE);

        mAutocompleteView.setVisibility(View.VISIBLE);
        mAutocompleteView.setText("");

        if (destinationLocationMarker != null) {
            destinationLocationMarker.remove();
        }
    }
}