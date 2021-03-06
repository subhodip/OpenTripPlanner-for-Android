/*
 * Copyright 2011 Marcy Gordon
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.usf.cutr.opentripplanner.android.tasks;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.osmdroid.util.GeoPoint;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;
import au.com.bytecode.opencsv.CSVReader;
import de.mastacode.http.Http;
import edu.usf.cutr.opentripplanner.android.listeners.OTPLocationListener;
import edu.usf.cutr.opentripplanner.android.listeners.ServerSelectorCompleteListener;
import edu.usf.cutr.opentripplanner.android.model.Server;
import edu.usf.cutr.opentripplanner.android.sqlite.ServersDataSource;
import edu.usf.cutr.opentripplanner.android.util.LocationUtil;
import static edu.usf.cutr.opentripplanner.android.OTPApp.*;

/**
 * A task that retrieves the list of OTP servers from the Google Docs directory,
 * and if specified, automatically chooses the server based on the geographic bounds
 * and user current location
 * 
 * @author Marcy Gordon
 * @author Khoa Tran
 */

public class ServerSelector extends AsyncTask<GeoPoint, Integer, Long> {
	private Server selectedServer;
	private static final String TAG = "OTP";
	private ProgressDialog progressDialog;
	private Context context;
	private static List<Server> knownServers = new ArrayList<Server>();
	private boolean mustRefreshList = false;
	private boolean isAutoDetectEnabled = true;
	private ServerSelectorCompleteListener callback;
	
	public double currentLat = 0.0;
    public double currentLon = 0.0;
    private GeoPoint currentLocation;

    public LocationManager lm;
    public ArrayList<OTPLocationListener> otpLocationListenerList;    
    OTPLocationListener otpLocationListener;
    
    public ServersDataSource dataSource = null;
    
    private List<String> providers = new ArrayList<String>();

    /**
     * Constructs a new ServerSelector
     * @param context
     * @param dataSource
     * @param callback
     * @param mustRefreshList true if we should download a new list of servers from the Google Doc, false if we should use cached list of servers
     * @param isAutoDetectEnabled true if we should automatically compare the user's current location to the bounding boxes of OTP servers, false if we should prompt the user to pick the OTP server manually
     */
	public ServerSelector(Context context, ServersDataSource dataSource, ServerSelectorCompleteListener callback, boolean mustRefreshList, boolean isAutoDetectEnabled) {
		this.context = context;
		this.dataSource = dataSource;
		this.callback = callback;
		this.mustRefreshList = mustRefreshList;
		this.isAutoDetectEnabled = isAutoDetectEnabled;
		progressDialog = new ProgressDialog(context);
	}

	protected void onPreExecute() {
		progressDialog = ProgressDialog.show(context, "",
				"Detecting optimal server. Please wait...", true);
		progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(true);
        progressDialog.show();
        
        if(isAutoDetectEnabled){
        	prepareLocationListener();
        }
	}
	
	private void prepareLocationListener(){
		otpLocationListenerList = new ArrayList<OTPLocationListener>();
        
        lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        
		providers.addAll(lm.getProviders(true));

		for (int i=0; i<providers.size(); i++) {
			otpLocationListener = new OTPLocationListener();
			lm.requestLocationUpdates(providers.get(i), 
									  0, 
									  0,
									  otpLocationListener);
			otpLocationListenerList.add(otpLocationListener);
		}
	}

	protected Long doInBackground(GeoPoint... currLoc) {
		long startMillis = SystemClock.currentThreadTimeMillis();
		long deltaMillis = startMillis;
		
		List<Server> serverList = null;
		
		// If not forced to refresh list
		if(!mustRefreshList){
			// Check if servers are stored in SQLite?
			Log.v(TAG, "Attempt retrieving servers from sqlite");
			serverList = getServerFromSQLite();
		}
		
		// If forced to refresh list OR
		// If severs are not stored, download list from the Google Spreadsheet and Insert to database
		if(serverList == null || serverList.isEmpty() || mustRefreshList){
			Log.v(TAG, "No data from sqlite. Attempt retrieving servers from google spreadsheet");
			serverList = downloadServerList("https://spreadsheets.google.com/spreadsheet/pub?hl=en_US&hl=en_US&key=0AgWy8ujaGosCdDhxTC04cUZNeHo0eGFBQTBpU2dxN0E&single=true&gid=0&output=csv");

			// Insert new list to database
			if(serverList!=null && !serverList.isEmpty()){
				insertServerListToDatabase(serverList);
			}
		
			// If still null
			if (serverList == null || serverList.isEmpty()) {
				return null;
			}
		}
		
		knownServers.clear();
		knownServers.addAll(serverList);
		
		//If we're autodetecting a server, get the location find the optimal server
		if(isAutoDetectEnabled){
			while ((currentLat == 0.0) && deltaMillis < 5000) {
				deltaMillis = SystemClock.currentThreadTimeMillis() - startMillis;
	        	for(int i=0; i<providers.size(); i++){
	        		currentLat = otpLocationListenerList.get(i).getCurrentLat();
	        		currentLon = otpLocationListenerList.get(i).getCurrentLon();
	        		if(currentLat!=0.0)
	        			break;
	        	}
	        }
			currentLocation =new GeoPoint(currentLat, currentLon);
			
			for (int i=0; i<providers.size(); i++) {
				otpLocationListenerList.set(i, null);
			}
			
			selectedServer = findOptimalSever(currentLocation);
			
		} else {
			currentLocation = currLoc[0];
		}
		
		long totalSize = currLoc.length;
		return totalSize;
	}

	private List<Server> getServerFromSQLite(){
		List<Server> servers = new ArrayList<Server>();
		
		dataSource.open();
		List<Server> values = dataSource.getMostRecentServers();
		String shown = "";
		for(int i=0; i<values.size(); i++){
			Server s = values.get(i);
			shown += s.getRegion() + s.getDate().toString()+"\n";
			servers.add(new Server(s.getDate(), s.getRegion(), s.getBaseURL(), s.getBounds(),
								   s.getLanguage(), s.getContactName(), s.getContactEmail()));
		}
		Log.v(TAG, shown);
		dataSource.close();
//		Toast.makeText(activity.getApplicationContext(), shown, Toast.LENGTH_SHORT).show();
		
		return servers;
	}

	/**
	 * Downloads the list of OTP servers from the Google Doc directory
	 * @param url URL address of the Google Doc
	 * @return Server a list of OTP servers contained in the Google Doc
	 */
	private List<Server> downloadServerList(String url){
		List<Server> serverList = new ArrayList<Server>();

		HttpClient client = new DefaultHttpClient();
		String result = "";
		try {
			result = Http.get(url).use(client).charset("UTF-8").followRedirects(true).asString();
			Log.d(TAG, "Spreadsheet: " + result);
		} catch (IOException e) {
			Log.e(TAG, "Unable to download spreadsheet with server list: " + e.getMessage());
			return null;
		}

		CSVReader reader = new CSVReader(new StringReader(result));
		try {
			Date currentTime = Calendar.getInstance().getTime();
			
			List<String[]> entries = reader.readAll();
			for (String[] e : entries) {
				if(e[0].equalsIgnoreCase("Region")) {
					continue; //Ignore the first line of the file
				}
				
				
				Server s = new Server(currentTime, e[0], e[1], e[2], e[3], e[4], e[5]);
				serverList.add(s);
			}
		} catch (IOException e) {
			Log.e(TAG, "Problem reading CSV server file: " + e.getMessage());
			
			if(reader != null){
				try {
					reader.close();
				} catch (IOException e2) {
					Log.e(TAG, "Error closing CSVReader file: " + e2);
				}
			}
			
			return null;
		}finally{
			if(reader != null){
				try {
					reader.close();
				} catch (IOException e) {
					Log.e(TAG, "Error closing CSVReader file: " + e);
				}
			}
		}

		Log.d(TAG, "Servers: " + serverList.size());

		return serverList;
	}
	
	private void insertServerListToDatabase(List<Server> servers){
		dataSource.open();
		for(int i=0; i<servers.size(); i++){
			dataSource.createServer(servers.get(i));
		}
		dataSource.close();
	}
	
	/**
	 * Automatically detects the correct OTP server based on the location of the device
	 * @param currLoc location of the device
	 * @return Server the OTP server that the location is within
	 */
	private Server findOptimalSever(GeoPoint currLoc) {
		if(currLoc == null){
			return null;
		}
		
		//If we've already selected a server, just return the one we selected
		if(selectedServer != null) {
			return selectedServer;
		}

		boolean isInBoundingBox = false;
		Server server = null;
		for (int i=0; i<knownServers.size(); i++) {
			// Check bounds here to find server - acceptable error is set to 1000m = 1km
			isInBoundingBox = LocationUtil.checkPointInBoundingBox(currLoc, knownServers.get(i), 1000);
			
			if(isInBoundingBox){
				server = knownServers.get(i);
				break;
			}
		}

		return server;
	}

	protected void onPostExecute(Long result) {
		if (progressDialog.isShowing()) {
			progressDialog.dismiss();
		}
		
		//Remove locationlisteners
		for (int i=0; i<providers.size(); i++) {			
			lm.removeUpdates(otpLocationListener);			
		}

		if (selectedServer != null) {
			//We've already auto-selected a server
			Toast.makeText(context.getApplicationContext(), 
						   "Detected "+selectedServer.getRegion() + ". Change this manually in Settings", 
						   Toast.LENGTH_SHORT).show();
			callback.onServerSelectorComplete(currentLocation, selectedServer);
		} else if (knownServers != null && !knownServers.isEmpty()){
			Log.d(TAG, "No server automatically selected.  User will need to choose the OTP server.");
			
			// Create dialog for user to choose
			List<String> serverNames = new ArrayList<String>();
			for (Server server : knownServers) {
				serverNames.add(server.getRegion());
			}
			serverNames.add("Custom Server");

			final CharSequence[] items = serverNames.toArray(new CharSequence[serverNames.size()]);

			AlertDialog.Builder builder = new AlertDialog.Builder(context);
			builder.setTitle("Choose OpenTripPlanner Server");
			builder.setItems(items, new DialogInterface.OnClickListener() {

				public void onClick(DialogInterface dialog, int item) {

					//If the user selected to enter a custom URL, they are shown this EditText box to enter it
					if(items[item].equals("Custom Server")) {
						final EditText tbBaseURL = new EditText(context);

						AlertDialog.Builder urlAlert = new AlertDialog.Builder(context);
						urlAlert.setTitle("Enter a custom OTP server domain");
						urlAlert.setView(tbBaseURL);
						urlAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int whichButton) {
								String value = tbBaseURL.getText().toString().trim();
								SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
								Editor e = prefs.edit();
								e.putBoolean(PREFERENCE_KEY_AUTO_DETECT_SERVER, false);
								e.putString(PREFERENCE_KEY_CUSTOM_SERVER_URL, value);
								e.commit();
							}
						});
						urlAlert.create().show();

					} else { 
						//User picked server from the list
						for (Server server : knownServers) {
							//If this server region matches what the user picked, then set the server as the selected server
							if (server.getRegion().equals(items[item])) {
								selectedServer = server;
								GeoPoint centerPoint = new GeoPoint(selectedServer.getCenterLatitude(), selectedServer.getCenterLongitude());
								callback.onServerSelectorComplete(centerPoint, selectedServer);
								break;
							}
						}
						//TODO - clear custom url pref here?
					}
					Log.v(TAG, "Chosen: " + items[item]);
				}
			});
			AlertDialog alert = builder.create();
			alert.show();

		} else {
			//TODO - handle error here that server list cannot be loaded
			Log.e(TAG, "Server list could not be downloaded!!");
		}
	}
}
