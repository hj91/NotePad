package com.nononsenseapps.notepad;

import java.util.List;

import com.github.espiandev.showcaseview.ShowcaseView;
import com.github.espiandev.showcaseview.ShowcaseView.ConfigOptions;
import com.googlecode.androidannotations.annotations.AfterViews;
import com.googlecode.androidannotations.annotations.Background;
import com.googlecode.androidannotations.annotations.EActivity;
import com.googlecode.androidannotations.annotations.InstanceState;
import com.googlecode.androidannotations.annotations.OnActivityResult;
import com.googlecode.androidannotations.annotations.SystemService;
import com.googlecode.androidannotations.annotations.UiThread;
import com.googlecode.androidannotations.annotations.ViewById;
import com.nononsenseapps.billing.IabHelper;
import com.nononsenseapps.billing.IabResult;
import com.nononsenseapps.billing.Inventory;
import com.nononsenseapps.billing.Purchase;
import com.nononsenseapps.helpers.ActivityHelper;
import com.nononsenseapps.helpers.NotificationHelper;
import com.nononsenseapps.helpers.SyncHelper;
import com.nononsenseapps.helpers.SyncStatusMonitor;
import com.nononsenseapps.helpers.SyncStatusMonitor.OnSyncStartStopListener;
import com.nononsenseapps.notepad.database.LegacyDBHelper;
import com.nononsenseapps.notepad.database.LegacyDBHelper.NotePad;
import com.nononsenseapps.notepad.database.Notification;
import com.nononsenseapps.notepad.database.Task;
import com.nononsenseapps.notepad.database.TaskList;
import com.nononsenseapps.notepad.fragments.DialogConfirmBase;
import com.nononsenseapps.notepad.fragments.DialogEditList.EditListDialogListener;
import com.nononsenseapps.notepad.fragments.DialogEditList_;
import com.nononsenseapps.notepad.fragments.TaskDetailFragment;
import com.nononsenseapps.notepad.fragments.TaskDetailFragment_;
import com.nononsenseapps.notepad.fragments.TaskListViewPagerFragment;
import com.nononsenseapps.notepad.interfaces.MenuStateController;
import com.nononsenseapps.notepad.interfaces.OnFragmentInteractionListener;
import com.nononsenseapps.notepad.legacy.DonateMigrator;
import com.nononsenseapps.notepad.legacy.DonateMigrator_;
import com.nononsenseapps.notepad.prefs.AccountDialog4;
import com.nononsenseapps.notepad.prefs.MainPrefs;
import com.nononsenseapps.notepad.prefs.PrefsActivity;
import com.nononsenseapps.utils.ViewsHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.Toast;

@EActivity(R.layout.activity_main)
public class ActivityMain extends FragmentActivity implements
		OnFragmentInteractionListener, OnSyncStartStopListener,
		MenuStateController, OnSharedPreferenceChangeListener {

	public static interface ListOpener {
		public void openList(final long id);
	}

	// Intent notification argument
	public static final String NOTIFICATION_CANCEL_ARG = "notification_cancel_arg";
	public static final String NOTIFICATION_DELETE_ARG = "notification_delete_arg";

	// In-app donate identifier
	public static final String SKU_INAPP_PREMIUM = "donate_inapp";
	// User bought old donate version or in-app
	public static final String PREMIUMSTATUS = "donate_inapp_or_oldversion";
	// For static testing
	// static final String SKU_DONATE = "android.test.purchased";
	// static final String SKU_DONATE = "android.test.cancelled";
	static final int SKU_DONATE_REQUEST_CODE = 7331;

	// Set to true in bundle if exits should be animated
	public static final String ANIMATEEXIT = "animateexit";
	// Using tags for test
	public static final String DETAILTAG = "detailfragment";
	public static final String LISTPAGERTAG = "listpagerfragment";
	private static final String SHOWCASED_MAIN = "showcased_main_window";
	private static final String SHOWCASED_DRAWER = "showcased_main_drawer";

	@ViewById
	ListView leftDrawer;
	@ViewById
	DrawerLayout drawerLayout;

	@ViewById
	View fragment1;

	// Only present on tablets
	@ViewById
	View fragment2;

	// Shown on tablets on start up. Hide on selection
	@ViewById
	View taskHint;

	@SystemService
	LayoutInflater layoutInflater;
	@SystemService
	InputMethodManager inputManager;
	// View mRefreshIndeterminateProgressView = null;
	private Menu mMenu;
	// private MenuItem mSyncMenuItem;

	boolean mAnimateExit = false;
	IabHelper mBillingHelper;
	// True if user has access to premium features, through in-app purchase or
	// old donate version
	boolean mHasPremiumAccess = false;
	// True if user donated in-app
	protected boolean mDonatedInApp = false;
	private ActionBarDrawerToggle mDrawerToggle;

	// Changes depending on what we're showing since the started activity can
	// receive new intents
	@InstanceState
	boolean showingEditor = false;

	boolean isDrawerClosed = true;

	boolean alreadyShowcased = false;
	boolean alreadyShowcasedDrawer = false;

	SyncStatusMonitor syncStatusReceiver = null;
	// Only not if opening note directly
	private boolean shouldAddToBackStack = true;

	// WIll only be the viewpager fragment
	ListOpener listOpener = null;
	private Bundle state;

	@Override
	public void onCreate(Bundle b) {
		// Must do this before super.onCreate
		ActivityHelper.readAndSetSettings(this);
		super.onCreate(b);

		syncStatusReceiver = new SyncStatusMonitor();

		// First load, then don't add to backstack
		shouldAddToBackStack = false;

		// To know if we should animate exits
		if (getIntent() != null
				&& getIntent().getBooleanExtra(ANIMATEEXIT, false)) {
			mAnimateExit = true;
		}

		// If user has donated some other time
		final SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		mHasPremiumAccess = prefs.getBoolean(PREMIUMSTATUS, false);
		mDonatedInApp = prefs.getBoolean(SKU_INAPP_PREMIUM, false);

		alreadyShowcased = prefs.getBoolean(SHOWCASED_MAIN, false);
		alreadyShowcasedDrawer = prefs.getBoolean(SHOWCASED_DRAWER, false);

		// For in-app billing
		final String base64EncodedPublicKey = new StringBuilder(
				"MIIBIjANBgkqhki")
				.append("G9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkNMrvFQmGKm5YoSD7UMCvKMvlEguAHVNCzEb")
				.append("Bww7T8iQHPr5H7Ltag03HLT4oToG1hDKsbEV7tks2tjwAm1ftzlud+gFMEG/GCL6G")
				.append("F+aisWKLJJZtpODRzidAAJVjlDaIROJBsnDnBQ2f8uoukSrXNaT42k/plIjhCiCdZ")
				.append("AmMb7Yb48v6aMvnB7FHXBffrkI2mpn4c8kSe721ROovZXNDw7/U94ZODKkOrnZGON")
				.append("rJ1isUwibFA3MhOfRdemE1aZF6KwCMD2EvgV8y9KVOPwF3lE0ucsJ4I56eEQpmzzR")
				.append("jItb/Gn8iHp0qa7IhSR/vXBL4p+byCcoQDlfvjeAmjY6dQIDAQAB")
				.toString();
		try {
			mBillingHelper = new IabHelper(this, base64EncodedPublicKey);
			mBillingHelper.enableDebugLogging(true, "nononsenseapps billing");
			mBillingHelper
					.startSetup(new IabHelper.OnIabSetupFinishedListener() {
						public void onIabSetupFinished(IabResult result) {
							if (!result.isSuccess()) {
								// Oh noes, there was a problem.
								Log.d("nononsenseapps billing",
										"Problem setting up In-app Billing: "
												+ result);
								return;
							}
							// Hooray, IAB is fully set up!
							mBillingHelper
									.queryInventoryAsync(mBillingInventoryListener);
						}
					});
		}
		catch (Exception e) {
			Log.d("nononsenseapps billing",
					"InApp billing cant be allowed to crash app, EVER");
		}

		// See if the donate version is installed and offer to import if so
		isOldDonateVersionInstalled();

		// To listen on fragment changes
		getSupportFragmentManager().addOnBackStackChangedListener(
				new FragmentManager.OnBackStackChangedListener() {
					public void onBackStackChanged() {
						// Crash report about null pointer, think it was this
						final View focusView = ActivityMain.this
								.getCurrentFocus();
						if (focusView != null && inputManager != null) {
							inputManager.hideSoftInputFromWindow(
									focusView.getWindowToken(),
									InputMethodManager.HIDE_NOT_ALWAYS);
						}

						if (showingEditor && !isNoteIntent(getIntent())) {
							resetActionBar();
						}
						// Always update menu
						invalidateOptionsMenu();
					}
				});

		if (b != null) {
			Log.d("nononsenseapps list", "Activity Saved not null: " + b);
			this.state = b;
		}
		
		// Clear possible notifications, schedule future ones
		final Intent intent = getIntent();
		// Clear notification if present
		clearNotification(intent);
		// Schedule notifications
		NotificationHelper.schedule(this);
	}

	@Background
	void isOldDonateVersionInstalled() {
		List<ApplicationInfo> packages;
		PackageManager pm;
		pm = getPackageManager();
		packages = pm.getInstalledApplications(0);
		for (ApplicationInfo packageInfo : packages) {
			if (packageInfo.packageName
					.equals("com.nononsenseapps.notepad_donate")) {
				migrateDonateUser();
				mHasPremiumAccess = true;
				// Allow them to donate again
				PreferenceManager
						.getDefaultSharedPreferences(ActivityMain.this).edit()
						.putBoolean(PREMIUMSTATUS, true).commit();
				// Stop loop
				break;
			}
		}
	}

	@SuppressLint("ValidFragment")
	@UiThread
	void migrateDonateUser() {
		// migrate user
		if (!DonateMigrator.hasImported(this)) {
			final DialogConfirmBase dialog = new DialogConfirmBase() {

				@Override
				public void onOKClick() {
					startService(new Intent(ActivityMain.this,
							DonateMigrator_.class));
				}

				@Override
				public int getTitle() {
					return R.string.import_data_question;
				}

				@Override
				public int getMessage() {
					return R.string.import_data_msg;
				}
			};
			dialog.show(getSupportFragmentManager(), "migrate_question");
		}
	}

	@Override
	public void onResume() {
		if (shouldRestart) {
			restartAndRefresh();
		}
		super.onResume();
		// activate monitor
		if (syncStatusReceiver != null) {
			syncStatusReceiver.startMonitoring(this);
		}

		// Sync if appropriate
		SyncHelper.requestSyncIf(this, SyncHelper.ONAPPSTART);
	}

	private void restartAndRefresh() {
		shouldRestart = false;
		Intent intent = getIntent();
		overridePendingTransition(0, 0);
		intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
		finish();
		overridePendingTransition(0, 0);
		startActivity(intent);
	}

	@Override
	public void onPause() {
		super.onPause();
		// deactivate monitor
		if (syncStatusReceiver != null) {
			syncStatusReceiver.stopMonitoring();
		}
	}

	@UiThread
	@Override
	public void onSyncStartStop(final boolean ongoing) {
		if (mMenu == null) {
			return;
		}
		final MenuItem syncMenuItem = mMenu.findItem(R.id.menu_sync);
		if (syncMenuItem == null) {
			return;
		}

		if (ongoing) {
			syncMenuItem
					.setActionView(R.layout.actionbar_indeterminate_progress);
		}
		else {
			syncMenuItem.setActionView(null);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mBillingHelper != null) mBillingHelper.dispose();
		mBillingHelper = null;
	}

	// Listener that's called when we finish querying the items and
	// subscriptions we own
	IabHelper.QueryInventoryFinishedListener mBillingInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
		public void onQueryInventoryFinished(IabResult result,
				Inventory inventory) {
			if (result.isFailure()) {
				Log.d("nononsenseapps billing", "Failed to query inventory: "
						+ result);
				return;
			}

			Log.d("nononsenseapps billing", "Query inventory was successful.");

			/*
			 * Check for items we own. Notice that for each purchase, we check
			 * the developer payload to see if it's correct! See
			 * verifyDeveloperPayload().
			 */

			// Do we have the premium upgrade?
			// Purchase premiumPurchase = inventory.getPurchase(SKU_DONATE);
			// mIsDonate = (premiumPurchase != null);// &&
			// verifyDeveloperPayload(premiumPurchase));
			if (!mHasPremiumAccess) {
				mHasPremiumAccess = inventory.hasPurchase(SKU_INAPP_PREMIUM);

				if (mHasPremiumAccess) {
					// Save in prefs
					PreferenceManager
							.getDefaultSharedPreferences(ActivityMain.this)
							.edit().putBoolean(SKU_INAPP_PREMIUM, true)
							.putBoolean(PREMIUMSTATUS, mHasPremiumAccess)
							.commit();
					// Update relevant parts of UI
					updateUiDonate();
				}

				Log.d("nononsenseapps billing", "User is "
						+ (mHasPremiumAccess ? "PREMIUM" : "NOT PREMIUM"));
			}
		}
	};
	protected boolean reverseAnimation = false;
	private boolean shouldRestart = false;
	private ShowcaseView sv;

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		// Sync the toggle state after onRestoreInstanceState has occurred.
		if (mDrawerToggle != null) mDrawerToggle.syncState();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (mDrawerToggle != null)
			mDrawerToggle.onConfigurationChanged(newConfig);
	}

	/**
	 * Load a list of lists in the left
	 */
	@AfterViews
	protected void loadLeftDrawer() {
		// TODO handle being called repeatably better?
		// Set a listener on drawer events
		// TODO strings
		if (mDrawerToggle == null) {
			mDrawerToggle = new ActionBarDrawerToggle(this, drawerLayout,
					R.drawable.ic_drawer_dark, R.string.ok, R.string.about) {

				/**
				 * Called when a drawer has settled in a completely closed
				 * state.
				 */
				public void onDrawerClosed(View view) {
					getActionBar().setTitle(R.string.app_name);
					isDrawerClosed = true;
					invalidateOptionsMenu(); // creates call to
												// onPrepareOptionsMenu()
				}

				/** Called when a drawer has settled in a completely open state. */
				public void onDrawerOpened(View drawerView) {
					getActionBar().setTitle(R.string.show_from_all_lists);
					isDrawerClosed = false;
					invalidateOptionsMenu(); // creates call to
												// onPrepareOptionsMenu()

					showcaseDrawerPress();
				}
			};

			// Set the drawer toggle as the DrawerListener
			drawerLayout.setDrawerListener(mDrawerToggle);
		}

		getActionBar().setDisplayHomeAsUpEnabled(true);
		getActionBar().setHomeButtonEnabled(true);

		// Adapter for list titles and ids
		final SimpleCursorAdapter adapter = new SimpleCursorAdapter(this,
				R.layout.simple_light_list_item_1, null,
				new String[] { TaskList.Columns.TITLE },
				new int[] { android.R.id.text1 }, 0);
		leftDrawer.setAdapter(adapter);
		// Set click handler
		leftDrawer.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> arg0, View v, int pos,
					long id) {
				openList(id);
			}
		});
		leftDrawer.setOnItemLongClickListener(new OnItemLongClickListener() {

			@Override
			public boolean onItemLongClick(AdapterView<?> arg0, View arg1,
					int pos, long id) {
				// Open dialog to edit list
				if (id > 0) {
					DialogEditList_ dialog = DialogEditList_.getInstance(id);
					dialog.show(getSupportFragmentManager(),
							"fragment_edit_list");
				}
				return true;
			}
		});

		// Load actual data
		getSupportLoaderManager().restartLoader(0, null,
				new LoaderCallbacks<Cursor>() {

					@Override
					public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
						return new CursorLoader(ActivityMain.this,
								TaskList.URI, new String[] {
										TaskList.Columns._ID,
										TaskList.Columns.TITLE }, null, null,
								getResources().getString(
										R.string.const_as_alphabetic,
										TaskList.Columns.TITLE));
					}

					@Override
					public void onLoadFinished(Loader<Cursor> arg0, Cursor c) {
						adapter.swapCursor(c);
					}

					@Override
					public void onLoaderReset(Loader<Cursor> arg0) {
						adapter.swapCursor(null);
					}
				});
	}

	/**
	 * Opens the specified list and closes the left drawer
	 */
	void openList(final long id) {
		// Open list
		Intent i = new Intent(ActivityMain.this, ActivityMain_.class);
		i.setAction(Intent.ACTION_VIEW).setData(TaskList.getUri(id))
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

		// If editor is on screen, we need to reload fragments
		if (listOpener == null) {
			while (getSupportFragmentManager().popBackStackImmediate()) {
				// Need to pop the entire stack and then load
			}
			reverseAnimation = true;
			startActivity(i);
		}
		else {
			// If not popped, then send the call to the fragment
			// directly
			listOpener.openList(id);
		}

		// And then close drawer
		if (drawerLayout != null && leftDrawer != null) {
			drawerLayout.closeDrawer(leftDrawer);
		}
	}

	/**
	 * Loads the appropriate fragments depending on state and intent.
	 */
	@AfterViews
	protected void loadContent() {
		loadFragments();

		if (!showingEditor || fragment2 != null) {
			showcaseDrawer();
		}
	}

	@UiThread
	void loadFragments() {
		final Intent intent = getIntent();

		// Mandatory
		Fragment left = null;
		String leftTag = null;
		// Only if fragment2 is not null
		Fragment right = null;

		if (this.state != null) {
			this.state = null;
			if (showingEditor && fragment2 != null) {
				// Should only be true in portrait
				showingEditor = false;
			}

			// Find fragments
			// This is an instance state variable
			if (showingEditor) {
				// Portrait, with editor, modify action bar
				setDoneActionBar();
				// Done
				return;
			}
			else {
				// Find the listpager
				left = getSupportFragmentManager().findFragmentByTag(
						LISTPAGERTAG);
				listOpener = (ListOpener) left;

				if (left != null && fragment2 == null) {
					// Done
					return;
				}
				else if (left != null && fragment2 != null) {
					right = getSupportFragmentManager().findFragmentByTag(
							DETAILTAG);
				}

				if (left != null && right != null) {
					// Done
					return;
				}
			}
		}

		// Load stuff
		final FragmentTransaction transaction = getSupportFragmentManager()
				.beginTransaction();
		if (reverseAnimation) {
			reverseAnimation = false;
			transaction.setCustomAnimations(R.anim.slide_in_bottom,
					R.anim.slide_out_top, R.anim.slide_in_top,
					R.anim.slide_out_bottom);
		}
		else {
			transaction.setCustomAnimations(R.anim.slide_in_top,
					R.anim.slide_out_bottom, R.anim.slide_in_bottom,
					R.anim.slide_out_top);
		}

		/*
		 * If it contains a noteId, load an editor. If also tablet, load the
		 * lists.
		 */
		if (fragment2 != null) {
			if (right == null) {
				if (getNoteId(intent) > 0) {
					right = TaskDetailFragment_.getInstance(getNoteId(intent));
				}
				else if (getNoteShareText(intent).length() > 0) {
					right = TaskDetailFragment_.getInstance(
							getNoteShareText(intent),
							TaskListViewPagerFragment.getAList(this,
									getListId(intent),
									getString(R.string.pref_defaultlist)));
				}
			}
		}
		else if (isNoteIntent(intent)) {
			showingEditor = true;
			listOpener = null;
			leftTag = DETAILTAG;
			if (getNoteId(intent) > 0) {
				left = TaskDetailFragment_.getInstance(getNoteId(intent));
			}
			else {
				// Get a share text (null safe)
				// In a list (if specified, or default otherwise)
				left = TaskDetailFragment_.getInstance(
						getNoteShareText(intent), TaskListViewPagerFragment
								.getAList(this, getListId(intent),
										getString(R.string.pref_defaultlist)));
			}
			// fucking stack
			while (getSupportFragmentManager().popBackStackImmediate()) {
				// Need to pop the entire stack and then load
			}
			if (shouldAddToBackStack) {
				transaction.addToBackStack(null);
			}

			setDoneActionBar();
		}
		/*
		 * Other case, is a list id or a tablet
		 */
		if (!isNoteIntent(intent) || fragment2 != null) {
			// If we're no longer in the editor, reset the action bar
			if (fragment2 == null) {
				resetActionBar();
			}

			showingEditor = false;

			left = TaskListViewPagerFragment
					.getInstance(getListIdToShow(intent));
			leftTag = LISTPAGERTAG;
			listOpener = (ListOpener) left;
		}

		if (fragment2 != null && right != null) {
			transaction.replace(R.id.fragment2, right, DETAILTAG);
			taskHint.setVisibility(View.GONE);
		}
		transaction.replace(R.id.fragment1, left, leftTag);

		// Commit transaction
		// Allow state loss as workaround for bug
		// https://code.google.com/p/android/issues/detail?id=19917
		transaction.commitAllowingStateLoss();
		// Next go, always add
		shouldAddToBackStack = true;
	}

	void setDoneActionBar() {
		// Courtesy of Mr Roman Nurik
		// Inflate a "Done" custom action bar view to serve as the "Up"
		// affordance.
		LayoutInflater inflater = (LayoutInflater) getActionBar()
				.getThemedContext().getSystemService(LAYOUT_INFLATER_SERVICE);
		final View customActionBarView = inflater.inflate(
				R.layout.actionbar_custom_view_done, null);
		customActionBarView.findViewById(R.id.actionbar_done)
				.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						// "Done"

						// Should load the same list again
						// Try getting the list from the original intent
						final long listId = getListId(getIntent());

						final Intent intent = new Intent().setAction(
								Intent.ACTION_VIEW).setClass(ActivityMain.this,
								ActivityMain_.class);
						if (listId > 0) {
							intent.setData(TaskList.getUri(listId));
						}

						// Set the intent before, so we set the correct
						// action bar
						setIntent(intent);
						while (getSupportFragmentManager()
								.popBackStackImmediate()) {
							// Need to pop the entire stack and then load
						}

						reverseAnimation = true;
						Log.d("nononsenseapps fragment", "starting activity");

						intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
								| Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(intent);
					}
				});

		// Show the custom action bar view and hide the normal Home icon and
		// title.
		final ActionBar actionBar = getActionBar();
		actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
				ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME
						| ActionBar.DISPLAY_SHOW_TITLE);
		actionBar.setCustomView(customActionBarView);
	}

	/**
	 * On first load, show some functionality hints
	 */
	private void showcaseDrawer() {
		if (alreadyShowcased) {
			return;
		}
		final ConfigOptions options = new ConfigOptions();
		options.shotType = ShowcaseView.TYPE_NO_LIMIT;
		options.block = true;
		// Used in saving state
		options.showcaseId = 1;
		final int vertDp = ViewsHelper.convertDip2Pixels(this, 200);
		final int horDp = ViewsHelper.convertDip2Pixels(this, 200);
		sv = ShowcaseView.insertShowcaseViewWithType(
				ShowcaseView.ITEM_ACTION_HOME, android.R.id.home, this,
				R.string.showcase_main_title, R.string.showcase_main_msg,
				options);
		sv.animateGesture(0, vertDp, horDp, vertDp);

		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.putBoolean(SHOWCASED_MAIN, true).commit();
		alreadyShowcased = true;
	}

	private void showcaseDrawerPress() {
		// only show on first boot
		if (alreadyShowcasedDrawer) {
			return;
		}

		final int vertDp = ViewsHelper.convertDip2Pixels(this, 110);
		final int horDp = ViewsHelper.convertDip2Pixels(this, 60);

		if (sv != null) {
			sv.setText(R.string.showcase_drawer_title,
					R.string.showcase_drawer_msg);
			sv.setShowcasePosition(horDp, vertDp);
			sv.show();
		}
		else {
			final ConfigOptions options = new ConfigOptions();
			options.shotType = ShowcaseView.TYPE_NO_LIMIT;
			options.block = true;
			// Used in saving state
			options.showcaseId = 2;
			sv = ShowcaseView.insertShowcaseView(horDp, vertDp, this,
					R.string.showcase_drawer_title,
					R.string.showcase_drawer_msg, options);
			sv.show();
		}
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.putBoolean(SHOWCASED_DRAWER, true).commit();
		alreadyShowcasedDrawer = true;
	}

	private void clearNotification(final Intent intent) {
		if (intent != null
				&& intent.getLongExtra(NOTIFICATION_DELETE_ARG, -1) > 0) {
			Notification.deleteOrReschedule(this, Notification.getUri(intent
					.getLongExtra(NOTIFICATION_DELETE_ARG, -1)));
		}
		if (intent != null
				&& intent.getLongExtra(NOTIFICATION_CANCEL_ARG, -1) > 0) {
			NotificationHelper.cancelNotification(this,
					(int) intent.getLongExtra(NOTIFICATION_CANCEL_ARG, -1));
		}

	}

	private void simulateBack() {
		if (getSupportFragmentManager().getBackStackEntryCount() <= 1) {
			setIntent(new Intent(this, ActivityMain_.class));
		}

		if (!getSupportFragmentManager().popBackStackImmediate()) {
			finish();
			// Intent i = new Intent(this, ActivityMain_.class);
			// i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
			// startActivity(i);
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if ((keyCode == KeyEvent.KEYCODE_BACK)) {
			// Reset intent so we get proper fragment handling when the stack
			// pops
			if (getSupportFragmentManager().getBackStackEntryCount() <= 1)
				setIntent(new Intent(this, ActivityMain_.class));
		}
		return super.onKeyDown(keyCode, event);
	}

	void resetActionBar() {
		// Reset Done button
		final ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME
					| ActionBar.DISPLAY_SHOW_TITLE,
					ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME
							| ActionBar.DISPLAY_SHOW_TITLE);
			actionBar.setTitle(R.string.app_name);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		// Do absolutely NOT call super class here. Will bug out the viewpager!
		super.onSaveInstanceState(outState);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getMenuInflater().inflate(R.menu.activity_main, menu);

		this.mMenu = menu;

		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.setGroupVisible(R.id.activity_menu_group, isDrawerClosed);
		menu.setGroupVisible(R.id.activity_reverse_menu_group, !isDrawerClosed);

		final MenuItem donateItem = menu.findItem(R.id.menu_donate);
		if (donateItem != null) {
			donateItem.setVisible(!mDonatedInApp);
		}

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Pass the event to ActionBarDrawerToggle, if it returns
		// true, then it has handled the app icon touch event
		if (mDrawerToggle.onOptionsItemSelected(item)) {
			return true;
		}
		// Handle your other action bar items...
		switch (item.getItemId()) {
		case android.R.id.home:
			// Handled by drawer
			return true;
		case R.id.drawer_menu_createlist:
			// Show fragment
			DialogEditList_ dialog = DialogEditList_.getInstance();
			dialog.setListener(new EditListDialogListener() {

				@Override
				public void onFinishEditDialog(long id) {
					openList(id);
				}
			});
			dialog.show(getSupportFragmentManager(), "fragment_create_list");
			return true;
		case R.id.menu_preferences:
			Intent intent = new Intent();
			intent.setClass(this, PrefsActivity.class);
			startActivity(intent);
			return true;
		case R.id.menu_donate:
			try {
				mBillingHelper.launchPurchaseFlow(this, SKU_INAPP_PREMIUM,
						SKU_DONATE_REQUEST_CODE,
						new IabHelper.OnIabPurchaseFinishedListener() {
							public void onIabPurchaseFinished(IabResult result,
									Purchase purchase) {
								if (result.isFailure()) {
									Log.d("nononsenseapps billing",
											"Error purchasing: " + result);
									return;
								}
								else if (purchase.getSku().equals(
										SKU_INAPP_PREMIUM)) {
									mHasPremiumAccess = true;
									mDonatedInApp = true;
									// Save in prefs
									PreferenceManager
											.getDefaultSharedPreferences(
													ActivityMain.this)
											.edit()
											.putBoolean(SKU_INAPP_PREMIUM, true)
											.putBoolean(PREMIUMSTATUS, true)
											.commit();
									// Update relevant parts of UI
									updateUiDonate();
									// Notify user of success
									Toast.makeText(
											ActivityMain.this,
											R.string.premiums_unlocked_and_thanks,
											Toast.LENGTH_SHORT).show();
								}
							}
						});
			}
			catch (Exception e) {
				Log.d("nononsenseapps billing",
						"Shouldnt start two purchases! "
								+ e.getLocalizedMessage());
			}
			return true;
		case R.id.menu_sync:
			if (SyncHelper.shouldSyncAtAll(this)) {
				SyncHelper.requestSyncIf(this, SyncHelper.MANUAL);
			}
			else {
				FragmentTransaction ft = getSupportFragmentManager()
						.beginTransaction();
				Fragment prev = getSupportFragmentManager().findFragmentByTag(
						"accountdialog");
				if (prev != null) {
					ft.remove(prev);
				}
				ft.addToBackStack(null);

				// Create and show the dialog.
				AccountDialog4 newFragment = new AccountDialog4();
				newFragment.show(ft, "accountdialog");
			}
			return true;
		case R.id.menu_delete:
		default:
			return false;
		}
	}

	@OnActivityResult(SKU_DONATE_REQUEST_CODE)
	void onDonatePurchased(int resultCode, Intent data) {
		if (mBillingHelper != null)
			mBillingHelper.handleActivityResult(SKU_DONATE_REQUEST_CODE,
					resultCode, data);
	}

	void updateUiDonate() {
		// check correct variableF
		if (mDonatedInApp) {
			invalidateOptionsMenu();
		}
	}

	/**
	 * Returns a note id from an intent if it contains one, either as part of
	 * its URI or as an extra
	 * 
	 * Returns -1 if no id was contained, this includes insert actions
	 */
	long getNoteId(final Intent intent) {
		long retval = -1;
		if (intent != null
				&& intent.getData() != null
				&& (Intent.ACTION_EDIT.equals(intent.getAction()) || Intent.ACTION_VIEW
						.equals(intent.getAction()))) {
			if (intent.getData().getPath().startsWith(TaskList.URI.getPath())) {
				// Find it in the extras. See DashClock extension for an example
				retval = intent.getLongExtra(Task.TABLE_NAME, -1);
			}
			else if ((intent
					.getData()
					.getPath()
					.startsWith(LegacyDBHelper.NotePad.Notes.PATH_VISIBLE_NOTES)
					|| intent
							.getData()
							.getPath()
							.startsWith(LegacyDBHelper.NotePad.Notes.PATH_NOTES) || intent
					.getData().getPath().startsWith(Task.URI.getPath()))) {
				retval = Long.parseLong(intent.getData().getLastPathSegment());
			}
			// else if (null != intent
			// .getStringExtra(TaskDetailFragment.ARG_ITEM_ID)) {
			// retval = Long.parseLong(intent
			// .getStringExtra(TaskDetailFragment.ARG_ITEM_ID));
			// }
		}
		return retval;
	}

	/**
	 * Returns the text that has been shared with the app. Does not check
	 * anything other than EXTRA_SUBJECT AND EXTRA_TEXT
	 * 
	 * If it is a Google Now intent, will ignore the subject which is
	 * "Note to self"
	 */
	String getNoteShareText(final Intent intent) {
		if (intent == null || intent.getExtras() == null) {
			return "";
		}

		StringBuilder retval = new StringBuilder();
		// possible title
		if (intent.getExtras().containsKey(Intent.EXTRA_SUBJECT)
				&& !"com.google.android.gm.action.AUTO_SEND".equals(intent
						.getAction())) {
			retval.append(intent.getExtras().get(Intent.EXTRA_SUBJECT));
		}
		// possible note
		if (intent.getExtras().containsKey(Intent.EXTRA_TEXT)) {
			if (retval.length() > 0) {
				retval.append("\n");
			}
			retval.append(intent.getExtras().get(Intent.EXTRA_TEXT));
		}
		return retval.toString();
	}

	/**
	 * Returns true the intent URI targets a note. Either an edit/view or
	 * insert.
	 */
	boolean isNoteIntent(final Intent intent) {
		if (intent == null) {
			return false;
		}
		if (Intent.ACTION_SEND.equals(intent.getAction())
				|| "com.google.android.gm.action.AUTO_SEND".equals(intent
						.getAction())) {
			return true;
		}

		if (intent.getData() != null
				&& (Intent.ACTION_EDIT.equals(intent.getAction())
						|| Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_INSERT
							.equals(intent.getAction()))
				&& (intent
						.getData()
						.getPath()
						.startsWith(
								LegacyDBHelper.NotePad.Notes.PATH_VISIBLE_NOTES)
						|| intent
								.getData()
								.getPath()
								.startsWith(
										LegacyDBHelper.NotePad.Notes.PATH_NOTES) || intent
						.getData().getPath().startsWith(Task.URI.getPath()))
				&& !intent.getData().getPath()
						.startsWith(TaskList.URI.getPath())) {
			return true;
		}

		return false;
	}

	/**
	 * Returns a list id from an intent if it contains one, either as part of
	 * its URI or as an extra
	 * 
	 * Returns -1 if no id was contained, this includes insert actions
	 */
	long getListId(final Intent intent) {
		long retval = -1;
		if (intent != null
				&& intent.getData() != null
				&& (Intent.ACTION_EDIT.equals(intent.getAction())
						|| Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_INSERT
							.equals(intent.getAction()))) {
			if ((intent.getData().getPath()
					.startsWith(NotePad.Lists.PATH_VISIBLE_LISTS)
					|| intent.getData().getPath()
							.startsWith(NotePad.Lists.PATH_LISTS) || intent
					.getData().getPath().startsWith(TaskList.URI.getPath()))) {
				try {
					retval = Long.parseLong(intent.getData()
							.getLastPathSegment());
				}
				catch (NumberFormatException e) {
					retval = -1;
				}
			}
			else if (0 < intent.getLongExtra(
					LegacyDBHelper.NotePad.Notes.COLUMN_NAME_LIST, -1)) {
				retval = intent.getLongExtra(
						LegacyDBHelper.NotePad.Notes.COLUMN_NAME_LIST, -1);
			}
			else if (0 < intent.getLongExtra(
					TaskDetailFragment.ARG_ITEM_LIST_ID, -1)) {
				retval = intent.getLongExtra(
						TaskDetailFragment.ARG_ITEM_LIST_ID, -1);
			}
			else if (0 < intent.getLongExtra(Task.Columns.DBLIST, -1)) {
				retval = intent.getLongExtra(Task.Columns.DBLIST, -1);
			}
		}
		return retval;
	}

	/**
	 * If intent contains a list_id, returns that. Else, checks preferences for
	 * default list setting. Else, -1.
	 */
	long getListIdToShow(final Intent intent) {
		long result = getListId(intent);
		return TaskListViewPagerFragment.getAList(this, result,
				getString(R.string.pref_defaultlist));
	}

	@Override
	public void onNewIntent(Intent intent) {
		setIntent(intent);
		loadFragments();
		// Just to be sure it gets done
		// Clear notification if present
		clearNotification(intent);
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	@Override
	public void onFragmentInteraction(final Uri taskUri, final long listId,
			final View origin) {
		final Intent intent = new Intent().setAction(Intent.ACTION_EDIT)
				.setClass(this, ActivityMain_.class).setData(taskUri)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
				.putExtra(TaskDetailFragment.ARG_ITEM_LIST_ID, listId);
		// User clicked a task in the list
		// tablet
		if (fragment2 != null) {
			// Set the intent here also so rotations open the same item
			setIntent(intent);
			getSupportFragmentManager()
					.beginTransaction()
					.setCustomAnimations(R.anim.slide_in_top,
							R.anim.slide_out_bottom)
					.replace(R.id.fragment2,
							TaskDetailFragment_.getInstance(taskUri))
					.commitAllowingStateLoss();
			taskHint.setVisibility(View.GONE);
		}
		// phone
		else {

			// if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
			// && origin != null) {
			// Log.d("nononsenseapps animation", "Animating");
			// //intent.putExtra(ANIMATEEXIT, true);
			// startActivity(
			// intent,
			// ActivityOptions.makeCustomAnimation(this,
			// R.anim.activity_slide_in_left,
			// R.anim.activity_slide_out_left).toBundle());

			// }
			// else {
			Log.d("nononsenseapps animation", "Not animating");
			startActivity(intent);
			// }
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	@Override
	public void addTaskInList(final String text, final long listId) {
		final Intent intent = new Intent().setAction(Intent.ACTION_INSERT)
				.setClass(this, ActivityMain_.class).setData(Task.URI)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
				.putExtra(TaskDetailFragment.ARG_ITEM_LIST_ID, listId);
		if (fragment2 != null) {
			// Set intent to preserve state when rotating
			setIntent(intent);
			// Replace editor fragment
			getSupportFragmentManager()
					.beginTransaction()
					.setCustomAnimations(R.anim.slide_in_top,
							R.anim.slide_out_bottom)
					.replace(R.id.fragment2,
							TaskDetailFragment_.getInstance(text, listId))
					.commitAllowingStateLoss();
			taskHint.setVisibility(View.GONE);
		}
		else {
			// Open an activity

			// if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			// Log.d("nononsenseapps animation", "Animating");
			// intent.putExtra(ANIMATEEXIT, true);
			// startActivity(
			// intent,
			// ActivityOptions.makeCustomAnimation(this,
			// R.anim.activity_slide_in_left,
			// R.anim.activity_slide_out_left).toBundle());
			// }
			// else {
			startActivity(intent);
			// }
		}
	}

	@Override
	public void closeFragment(final Fragment fragment) {
		if (fragment2 != null) {
			getSupportFragmentManager()
					.beginTransaction()
					.setCustomAnimations(R.anim.slide_in_top,
							R.anim.slide_out_bottom).remove(fragment)
					.commitAllowingStateLoss();
			taskHint.setAlpha(0f);
			taskHint.setVisibility(View.VISIBLE);
			taskHint.animate().alpha(1f).setStartDelay(500);
		}
		else {
			// Phone case, simulate back button
			// finish();
			simulateBack();
		}
	}

	/**
	 * Only call this when pressing the up-navigation. Makes sure the new
	 * activity comes in on top of this one.
	 */
	void finishSlideTop() {
		super.finish();
		overridePendingTransition(R.anim.activity_slide_in_right_full,
				R.anim.activity_slide_out_right);
	}

	@Override
	public void finish() {
		super.finish();
		// Only animate when specified. Should be when it was animated "in"
		if (mAnimateExit) {
			overridePendingTransition(R.anim.activity_slide_in_right,
					R.anim.activity_slide_out_right_full);
		}
	}

	@Override
	public boolean childItemsVisible() {
		return isDrawerClosed;
	}

	@Override
	public void onSharedPreferenceChanged(final SharedPreferences prefs,
			final String key) {
		if (key.equals(MainPrefs.KEY_THEME)
				|| key.equals(getString(R.string.pref_locale))) {
			shouldRestart = true;
		}
	}
}
