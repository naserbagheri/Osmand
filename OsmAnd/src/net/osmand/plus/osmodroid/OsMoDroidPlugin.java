package net.osmand.plus.osmodroid;

import java.io.File;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.GPXUtilities;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.TargetPointsHelper;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.views.MonitoringInfoControl;
import net.osmand.plus.views.MonitoringInfoControl.MonitoringInfoControlServices;
import net.osmand.plus.views.OsmandMapTileView;

import org.apache.commons.logging.Log;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;

import com.OsMoDroid.IRemoteOsMoDroidListener;
import com.OsMoDroid.IRemoteOsMoDroidService;

public class OsMoDroidPlugin extends OsmandPlugin implements MonitoringInfoControlServices  {
	IRemoteOsMoDroidListener.Stub inter = new IRemoteOsMoDroidListener.Stub() {

		@Override
		public void channelUpdated() throws RemoteException {
			if (activity != null) {
				activity.refreshMap();
				// test
			}
		}

		@Override
		public void channelsListUpdated() throws RemoteException {
			if (activity != null && connected) {
				log.debug("update channels");
				for (OsMoDroidLayer myOsMoDroidLayer : osmoDroidLayerList) {
					activity.getMapView().removeLayer(myOsMoDroidLayer);
				}
				osmoDroidLayerList.clear();
				requestLayersFromOsMoDroid(activity);
				for (OsMoDroidLayer myOsMoDroidLayer : osmoDroidLayerList) {
					activity.getMapView().addLayer(myOsMoDroidLayer, 4.5f);

				}
				activity.refreshMap();
			}

		}
		
		public void reRouteTo(LatLon loc) {
			final OsmandApplication app = activity.getMyApplication();
			final TargetPointsHelper targets = app.getTargetPointsHelper();
			// If we are in following mode then just update target point
			targets.navigateToPoint(loc, true, -1);
			if(!app.getRoutingHelper().isFollowingMode()) {
				// If we are not in following mode then request new route to calculate
				// Use default application mode
				activity.runOnUiThread(new Runnable() {
					
					@Override
					public void run() {
						activity.followRoute(app.getSettings().getApplicationMode(), targets.getPointToNavigate(), targets.getIntermediatePoints(),app.getLastKnownLocation(), null);
					}
				});
				
			}
		}

		@Override
		public void routeTo(float Lat, float Lon) throws RemoteException {
			reRouteTo(new LatLon(Lat, Lon));
			
		}

	};

	@Override
	public void updateLayers(OsmandMapTileView mapView, MapActivity activity) {
		registerLayers(activity);
		super.updateLayers(mapView, activity);
		MonitoringInfoControl lock = activity.getMapLayers().getMapInfoLayer().getMonitoringInfoControl();
		if(lock != null && !lock.getMonitorActions().contains(this)) {
			lock.addMonitorActions(this);
		}
	}

	MapActivity activity;
	public static final String ID = "osmand.osmodroid";
	private static final Log log = PlatformUtil.getLog(OsMoDroidPlugin.class);
	private OsmandApplication app;
	IRemoteOsMoDroidService mIRemoteService;
	private ServiceConnection mConnection;
	private int OSMODROID_SUPPORTED_VERSION_MIN = 5;
	private OsMoDroidLayer osmoDroidLayer;
	protected boolean connected = false;
	ArrayList<OsMoDroidLayer> osmoDroidLayerList = new ArrayList<OsMoDroidLayer>();
	private AsyncTask<Void, Void, Void> task;

	public ArrayList<OsMoDroidPoint> getOsMoDroidPointArrayList(int id) {
		ArrayList<OsMoDroidPoint> result = new ArrayList<OsMoDroidPoint>();
		try {
			for (int i = 0; i < mIRemoteService.getNumberOfObjects(id); i++) {
				result.add(new OsMoDroidPoint(mIRemoteService.getObjectLat(id, mIRemoteService.getObjectId(id, i)), mIRemoteService
						.getObjectLon(id, mIRemoteService.getObjectId(id, i)), mIRemoteService.getObjectName(id,
						mIRemoteService.getObjectId(id, i)), mIRemoteService.getObjectDescription(id, mIRemoteService.getObjectId(id, i)),
						mIRemoteService.getObjectId(id, i), id, mIRemoteService.getObjectSpeed(id, mIRemoteService.getObjectId(id, i)),
						mIRemoteService.getObjectColor(id, mIRemoteService.getObjectId(id, i))));
			}
		} catch (RemoteException e) {

			log.error(e.getMessage(), e);
		}

		return result;

	}
	
	public ArrayList<OsMoDroidPoint> getOsMoDroidFixedPointArrayList(int id) {
		ArrayList<OsMoDroidPoint> result = new ArrayList<OsMoDroidPoint>();
		try {
			for (int i = 0; i < mIRemoteService.getNumberOfPoints(id); i++) {
				result.add(new OsMoDroidPoint(mIRemoteService.getPointLat(id, mIRemoteService.getPointId(id, i)), mIRemoteService
						.getPointLon(id, mIRemoteService.getPointId(id, i)), mIRemoteService.getPointName(id,
						mIRemoteService.getPointId(id, i)), mIRemoteService.getPointDescription(id, mIRemoteService.getPointId(id, i)),
						mIRemoteService.getPointId(id, i), id, null,
						mIRemoteService.getPointColor(id, mIRemoteService.getPointId(id, i))));
			}
		} catch (RemoteException e) {

			log.error(e.getMessage(), e);
		}

		return result;

	}

	@Override
	public String getId() {
		return ID;
	}

	public OsMoDroidPlugin(OsmandApplication app) {
		this.app = app;

	}

	@Override
	public String getDescription() {
		return app.getString(R.string.osmodroid_plugin_description);
	}

	@Override
	public String getName() {
		return app.getString(R.string.osmodroid_plugin_name);
	}

	// test
	@Override
	public boolean init(final OsmandApplication app) {
		mConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				mIRemoteService = IRemoteOsMoDroidService.Stub.asInterface(service);
				try {
					System.out.println(mIRemoteService.getVersion());
					if (mIRemoteService.getVersion() < OSMODROID_SUPPORTED_VERSION_MIN) {
						app.showToastMessage(R.string.osmodroid_plugin_old_ver_not_supported);
						shutdown(app);
					} else {
						mIRemoteService.registerListener(inter);
						connected = true;
					}

				} catch (RemoteException e) {
					log.error(e.getMessage(), e);
				}

			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				connected = false;
				mIRemoteService = null;
			}
		};
		Intent serviceIntent = (new Intent("OsMoDroid.remote"));
		app.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
		return true;
	}

	void requestLayersFromOsMoDroid(MapActivity activity) {
		try {
			for (int i = 0; i < mIRemoteService.getNumberOfLayers(); i++) {
				osmoDroidLayerList.add(new OsMoDroidLayer(activity, mIRemoteService.getLayerId(i), this, mIRemoteService
						.getLayerName(mIRemoteService.getLayerId(i)), mIRemoteService.getLayerDescription(mIRemoteService.getLayerId(i))));
			}
		} catch (RemoteException e) {

			log.error(e.getMessage(), e);
		}
		 getGpxArrayList(); 
	}

	@Override
	public void registerLayers(MapActivity activity) {
		this.activity = activity;
		if (connected) {

			for (OsMoDroidLayer myOsMoDroidLayer : osmoDroidLayerList) {
				activity.getMapView().removeLayer(myOsMoDroidLayer);
			}
			osmoDroidLayerList.clear();
			requestLayersFromOsMoDroid(activity);
			for (OsMoDroidLayer myOsMoDroidLayer : osmoDroidLayerList) {
				activity.getMapView().addLayer(myOsMoDroidLayer, 4.5f);

			}
		}

	}

	@Override
	public void registerLayerContextMenuActions(OsmandMapTileView mapView, ContextMenuAdapter adapter, MapActivity mapActivity) {
		for (OsMoDroidLayer myOsMoDroidLayer : osmoDroidLayerList) {
			adapter.item(myOsMoDroidLayer.layerName).reg();
		}

		super.registerLayerContextMenuActions(mapView, adapter, mapActivity);
	}

	@Override
	public void disable(OsmandApplication app) {
		shutdown(app);
	}

	private void shutdown(OsmandApplication app) {
		if (mIRemoteService != null) {
			if (connected) {
				try {
					mIRemoteService.unregisterListener(inter);
				} catch (RemoteException e) {
					log.error(e.getMessage(), e);
				}
			}
			app.unbindService(mConnection);
			mIRemoteService = null;
		}
	}
	

	@Override
	public void addMonitorActions(final ContextMenuAdapter qa, final MonitoringInfoControl li, final OsmandMapTileView view) {
		boolean o = true;
		try {
			o = mIRemoteService.isActive();	
		}
		catch(Exception e) {
					log.error(e.getMessage(), e);
			
				}
		final boolean off = !o;
		qa.item(off ? R.string.osmodroid_mode_off : R.string.osmodroid_mode_on
				).icon(  off ? R.drawable.monitoring_rec_inactive : R.drawable.monitoring_rec_big).listen(new OnContextMenuClick() {

			@Override
			public void onContextMenuClick(int itemId, int pos, boolean isChecked, DialogInterface dialog) {
				try {
				if (off) {
					mIRemoteService.Activate();
				} else {
					mIRemoteService.Deactivate();
				}
				} catch(Exception e) {
					log.error(e.getMessage(), e);
				}
			}
		}).reg();
		qa.item(R.string.osmodroid_refresh).icons(R.drawable.ic_action_grefresh_dark, R.drawable.ic_action_grefresh_light).listen(new OnContextMenuClick() {
			
			@Override
			public void onContextMenuClick(int itemId, int pos, boolean isChecked,
					DialogInterface dialog) {
				try {
					mIRemoteService.refreshChannels();
				} catch (RemoteException e) {
					log.error(e.getMessage(), e);
				}
				
			}
		}).reg();
qa.item(R.string.osmodroid_unseek).icons(R.drawable.abs__ic_commit_search_api_holo_dark, R.drawable.abs__ic_commit_search_api_holo_light).listen(new OnContextMenuClick() {
			
			@Override
			public void onContextMenuClick(int itemId, int pos, boolean isChecked,
					DialogInterface dialog) {
				for (OsMoDroidLayer l: osmoDroidLayerList){
					l.seekOsMoDroidPoint=null;
				}
				
			}
		}).reg();
	}

	public void getGpxArrayList() {
		if(task!=null){
			task.cancel(true);
		}
		 task = new AsyncTask<Void, Void, Void>() {
		@Override
			protected Void doInBackground(Void... params) {
				for (OsMoDroidLayer l : osmoDroidLayerList){
				ArrayList<ColoredGPX> temp = new ArrayList<ColoredGPX>();
				try {
					for (int i = 0; i < mIRemoteService.getNumberOfGpx(l.layerId); i++) {
						ColoredGPX cg = new ColoredGPX();
						cg.gpxFile =  GPXUtilities.loadGPXFile(app, new File(mIRemoteService.getGpxFile(l.layerId, i)));
						cg.color = mIRemoteService.getGpxColor(l.layerId, i); 
						temp.add(cg);
					}
					l.inGPXFilelist(temp);
				} catch (RemoteException e) {
					log.error(e.getMessage(), e);
				}
				 catch (ConcurrentModificationException e) {
					log.error(e.getMessage(), e);
				}
				
			}
				return null;
		
			}
		};
		task.execute();
	}
}
