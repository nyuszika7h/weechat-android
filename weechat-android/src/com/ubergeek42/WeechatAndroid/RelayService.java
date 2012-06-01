package com.ubergeek42.WeechatAndroid;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.preference.PreferenceManager;

import com.ubergeek42.WeechatAndroid.notifications.HotlistHandler;
import com.ubergeek42.WeechatAndroid.notifications.HotlistObserver;
import com.ubergeek42.weechat.relay.RelayConnection;
import com.ubergeek42.weechat.relay.RelayConnection.ConnectionType;
import com.ubergeek42.weechat.relay.RelayConnectionHandler;
import com.ubergeek42.weechat.relay.RelayMessageHandler;
import com.ubergeek42.weechat.relay.messagehandler.BufferManager;
import com.ubergeek42.weechat.relay.messagehandler.LineHandler;
import com.ubergeek42.weechat.relay.messagehandler.NicklistHandler;

public class RelayService extends Service implements RelayConnectionHandler, OnSharedPreferenceChangeListener, HotlistObserver {

	private static final int NOTIFICATION_ID = 42;
	private NotificationManager notificationManger;
	
	
	String host;
	String port;
	String pass;
	
	boolean useStunnel = false;
	String stunnelCert;
	String stunnelPass;

	RelayConnection relayConnection;
	BufferManager bufferManager;
	RelayMessageHandler msgHandler;
	NicklistHandler nickHandler;
	HotlistHandler hotlistHandler;
	RelayConnectionHandler connectionHandler;
	private SharedPreferences prefs;
	private boolean shutdown;
	private boolean disconnected;
	
	@Override
	public IBinder onBind(Intent arg0) {
		return new RelayServiceBinder(this);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		prefs.registerOnSharedPreferenceChangeListener(this);

		notificationManger = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		showNotification(null, "Tap to connect");

		disconnected = false;
		
		if (prefs.getBoolean("autoconnect", false)) {
			connect();
		}
	}

	@Override
	public void onDestroy() {
		notificationManger.cancel(NOTIFICATION_ID);
		super.onDestroy();
		
		// TODO: decide whether killing the process is necessary...
		android.os.Process.killProcess(android.os.Process.myPid());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			// Autoconnect if possible
			//if (prefs.getBoolean("autoconnect", false)) {
			//	connect();
			//}
		}
		return super.onStartCommand(intent, flags, startId);
	}
	
	public void connect() {
		// Load the preferences
		host = prefs.getString("host", null);
		pass = prefs.getString("password", "password");
		port = prefs.getString("port", "8001");
		
		stunnelCert = prefs.getString("stunnel_cert", "");
		stunnelPass = prefs.getString("stunnel_pass", "");
		if (!stunnelCert.equals(""))useStunnel = true;
					
		// If no host defined, signal them to edit their preferences
		if (host == null) {
			Notification notification = new Notification(R.drawable.ic_launcher, "Click to edit your preferences and connect", System.currentTimeMillis());
			Intent i = new Intent(this, WeechatPreferencesActivity.class);
			PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
			
			notification.setLatestEventInfo(this, getString(R.string.app_version), "Update settings", contentIntent);
			notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
			notificationManger.notify(NOTIFICATION_ID, notification);
			return;
		}
		
		// Only connect if we aren't already connected
		if ((relayConnection != null) && (relayConnection.isConnected())) {
			return;
		}
		
		bufferManager = new BufferManager();
		msgHandler = new LineHandler(bufferManager);
		nickHandler = new NicklistHandler(bufferManager);
		hotlistHandler = new HotlistHandler(bufferManager);
		
		hotlistHandler.registerHighlightHandler(this);
		
		relayConnection = new RelayConnection(host, port, pass);
		if (useStunnel) {
			relayConnection.setStunnelCert(stunnelCert);
			relayConnection.setStunnelKey(stunnelPass);
			relayConnection.setConnectionType(ConnectionType.STUNNEL);
		}
		relayConnection.setConnectionHandler(this);
		relayConnection.tryConnect();
	}
	
	void resetNotification() {
		showNotification(null, "Connected to " + relayConnection.getServer());
	}
	
	private void showNotification(String tickerText, String content) {
		Notification notification = new Notification(R.drawable.ic_launcher, tickerText, System.currentTimeMillis());
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, WeechatActivity.class), 0);
		notification.setLatestEventInfo(this, getString(R.string.app_version), content, contentIntent);
		notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		notificationManger.notify(NOTIFICATION_ID, notification);
	}

	@Override
	public void onConnect() {
		if (disconnected == true) {
			showNotification("Reconnected to " + relayConnection.getServer(), "Connected to " + relayConnection.getServer());
		} else {
			showNotification("Connected to " + relayConnection.getServer(), "Connected to " + relayConnection.getServer());
		}
		disconnected = false;
		
		// Handle us getting a listing of the buffers
		relayConnection.addHandler("listbuffers", bufferManager);

		// Handle weechat event messages regarding buffers
		relayConnection.addHandler("_buffer_opened", bufferManager);
		relayConnection.addHandler("_buffer_type_changed", bufferManager);
		relayConnection.addHandler("_buffer_moved", bufferManager);
		relayConnection.addHandler("_buffer_merged", bufferManager);
		relayConnection.addHandler("_buffer_unmerged", bufferManager);
		relayConnection.addHandler("_buffer_renamed", bufferManager);
		relayConnection.addHandler("_buffer_title_changed", bufferManager);
		relayConnection.addHandler("_buffer_localvar_added", bufferManager);
		relayConnection.addHandler("_buffer_localvar_changed", bufferManager);
		relayConnection.addHandler("_buffer_localvar_removed", bufferManager);
		relayConnection.addHandler("_buffer_closing", bufferManager);

		// Handle lines being added to buffers
		relayConnection.addHandler("_buffer_line_added", hotlistHandler);
		relayConnection.addHandler("_buffer_line_added", msgHandler);
		relayConnection.addHandler("listlines_reverse", msgHandler);

		// Handle changes to the nicklist for buffers
		relayConnection.addHandler("nicklist", nickHandler);
		relayConnection.addHandler("_nicklist", nickHandler);

		// Get a list of buffers current open, along with some information about them
		relayConnection.sendMsg("(listbuffers) hdata buffer:gui_buffers(*) number,full_name,short_name,type,title,nicklist,local_variables");

		// Subscribe to any future changes
		relayConnection.sendMsg("sync");
		
		if (connectionHandler != null)
			connectionHandler.onConnect();
	}

	@Override
	public void onDisconnect() {
		if(disconnected) return; // Only do the disconnect handler once
		// :( aww disconnected
		if (connectionHandler != null)
			connectionHandler.onDisconnect();

		disconnected = true;
		
		// Automatically attempt reconnection if enabled(and if we aren't shutting down)
		if (!shutdown && prefs.getBoolean("reconnect", true)) {
			connect();
		} else {
			showNotification("Disconnected", "Disconnected");
		}
	}

	public void shutdown() {
		if (relayConnection != null) {
			shutdown = true;
			relayConnection.disconnect();
		}
		// TODO: possibly call stopself?
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		// Load/refresh preferences
		// TODO: tell clients to refresh views
		//if (key.equals("chatview_color")) {
		//	sharedPreferences.getBoolean("chatview_color", true);
		//} else if (key.equals("chatview_timestamp")) {
		//	sharedPreferences.getBoolean("chatview_timestamp", true);
		//}
		if (key.equals("host")) {
			host = prefs.getString("host", null);
		}else if (key.equals("password")) {
			pass = prefs.getString("password", "password");
		} else if (key.equals("port")) {
			port = prefs.getString("port", "8001");
		}
	}

	@Override
	public void onHighlight(String bufferName, String message) {
		// TODO: on intent click, clear the notification
		Notification notification = new Notification(R.drawable.ic_launcher, message, System.currentTimeMillis());
		Intent i = new Intent(this, WeechatChatviewActivity.class);
		i.putExtra("buffer", bufferName);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
		
		notification.setLatestEventInfo(this, getString(R.string.app_version), message, contentIntent);
		notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		
		// Default notification sound if enabled
		if (prefs.getBoolean("notification_sounds", false)) {
			notification.defaults |= Notification.DEFAULT_SOUND;
		}
		
		notificationManger.notify(NOTIFICATION_ID, notification);
	}
	
}
