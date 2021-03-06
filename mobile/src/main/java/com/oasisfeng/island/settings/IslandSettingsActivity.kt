package com.oasisfeng.island.settings

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_IGNORED
import android.app.admin.DevicePolicyManager.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.*
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.P
import android.os.Bundle
import android.preference.Preference
import android.preference.SwitchPreference
import android.preference.TwoStatePreference
import android.util.Log
import android.view.MenuItem
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.oasisfeng.android.ui.Dialogs
import com.oasisfeng.android.util.Apps
import com.oasisfeng.island.appops.AppOpsCompat
import com.oasisfeng.island.appops.AppOpsHelper
import com.oasisfeng.island.data.IslandAppInfo
import com.oasisfeng.island.mobile.R
import com.oasisfeng.island.notification.NotificationIds
import com.oasisfeng.island.setup.IslandSetup
import com.oasisfeng.island.util.DevicePolicies
import com.oasisfeng.island.util.Modules
import com.oasisfeng.island.util.Users
import java.lang.Exception

/**
 * Settings for each managed profile, also as launcher activity in managed profile.
 *
 * Created by Oasis on 2019-10-12.
 */
class IslandSettingsFragment: @Suppress("DEPRECATION") android.preference.PreferenceFragment() {

    override fun onResume() {
        super.onResume()
        val isDeviceOwner = DevicePolicies(activity).isActiveDeviceOwner
        if (Users.isOwner() && ! isDeviceOwner) {
            setup<Preference>(R.string.key_device_owner_setup) {
                summary = getString(R.string.pref_device_owner_summary) + getString(R.string.pref_device_owner_featurs)
                setOnPreferenceClickListener { true.also {
                    IslandSetup.requestDeviceOwnerActivation(this@IslandSettingsFragment, REQUEST_DEVICE_OWNER_ACTIVATION) }}}
            setup<Preference>(R.string.key_privacy) { isEnabled = false }   // Show but disabled, as a feature preview.
            setup<Preference>(R.string.key_watcher) { isEnabled = false }
            setup<Preference>(R.string.key_island_watcher) { remove(this) }
            setup<Preference>(R.string.key_setup) { remove(this) }
            return
        }
        setup<Preference>(R.string.key_device_owner_setup) { remove(this) }
        setupPreferenceForManagingAppOps(R.string.key_manage_read_phone_state, READ_PHONE_STATE, AppOpsCompat.OP_READ_PHONE_STATE,
                R.string.pref_privacy_read_phone_state_title, SDK_INT <= P)
        setupPreferenceForManagingAppOps(R.string.key_manage_read_sms, READ_SMS, AppOpsCompat.OP_READ_SMS,
                R.string.pref_privacy_read_sms_title)
        setupPreferenceForManagingAppOps(R.string.key_manage_location, ACCESS_COARSE_LOCATION, AppOpsCompat.OP_COARSE_LOCATION,
                R.string.pref_privacy_location_title)
        setupNotificationChannelTwoStatePreference(R.string.key_island_watcher, SDK_INT >= P && ! Users.isOwner(), NotificationIds.IslandWatcher)
        setupNotificationChannelTwoStatePreference(R.string.key_app_watcher, SDK_INT >= O, NotificationIds.IslandAppWatcher)
        setup<Preference>(R.string.key_reprovision) {
            if (Users.isOwner() && ! isDeviceOwner) return@setup remove(this)
            setOnPreferenceClickListener { true.also {
                @SuppressLint("InlinedApi") val action = if (Users.isOwner()) ACTION_PROVISION_MANAGED_DEVICE else ACTION_PROVISION_MANAGED_PROFILE
                ContextCompat.startForegroundService(activity, Intent(action).setPackage(Modules.MODULE_ENGINE)) }}}
        setup<Preference>(R.string.key_destroy) {
            if (Users.isOwner()) {
                if (! isDeviceOwner) return@setup remove(this)
                setTitle(R.string.pref_rescind_title)
                summary = getString(R.string.pref_rescind_summary) + getString(R.string.pref_device_owner_featurs) + "\n" }
            setOnPreferenceClickListener { true.also {
                if (Users.isOwner()) IslandSetup.requestDeviceOwnerDeactivation(activity)
                else IslandSetup.requestProfileRemoval(activity) }}}

        setup<Preference>(R.string.key_password) {
            if (Users.isOwner() && ! isDeviceOwner) return@setup remove(this)
            setOnPreferenceClickListener { true.also {
                @SuppressLint("InlinedApi") val action = if (Users.isOwner()) ACTION_SET_NEW_PASSWORD else ACTION_SET_NEW_PASSWORD
                ContextCompat.startActivity(this.context, Intent(ACTION_SET_NEW_PASSWORD),null)
                }}}

        setup<Preference>(R.string.key_password_failures) {
            if (Users.isOwner() && ! isDeviceOwner) return@setup remove(this)
            setOnPreferenceClickListener { true.also {
                    IslandSetup.setFailedPasswordAttempts(activity)
            }}}

        setup<Preference>(R.string.key_parent_password_failures) {
            if (Users.isOwner() && ! isDeviceOwner) return@setup remove(this)
            setOnPreferenceClickListener { true.also {
                IslandSetup.setFailedParentPasswordAttempts(activity)
            }}}

        setup<SwitchPreference>(R.string.key_disallow_copy_paste){
            if (Users.isOwner() && ! isDeviceOwner) return@setup remove(this)
            setOnPreferenceChangeListener { _, enabled -> true.also {
                Log.d("DG-Test", "toggle set to: " + enabled)
                IslandSetup.setClipboardSafety(activity, enabled.toString().toBoolean())
                }
            }
        }

    }

    private fun setupPreferenceForManagingAppOps(key: Int, permission: String, op: Int, @StringRes prompt: Int, precondition: Boolean = true) {
        setup<Preference>(key) {
            if (SDK_INT < P || ! precondition) return@setup remove(this)
            setOnPreferenceClickListener { true.also {      // TODO: Use worker thread to avoid ANR
                val entriesUnsorted = LinkedHashMap<String, CharSequence>(); val pm = context.packageManager; val apps = Apps.of(context)
                val pkgs = context.packageManager.getInstalledPackages(GET_PERMISSIONS or MATCH_UNINSTALLED_PACKAGES)
                        .filter { Apps.isInstalledInCurrentUser(it.applicationInfo) && it.requestedPermissions?.contains(permission) == true }
                // Apps with permission granted
                val systemPrefix = getString(R.string.label_prefix_for_system_app)
                val buildBaseLabel = { info: ApplicationInfo -> (if (Apps.isSystem(info)) systemPrefix else "") + " " + apps.getAppName(info).trim().toString() }
                pm.getPackagesHoldingPermissions(arrayOf(permission), GET_PERMISSIONS).forEach {
                    if (! Apps.isPrivileged(it.applicationInfo)) entriesUnsorted[it.packageName] = buildBaseLabel(it.applicationInfo) }
                // Apps with app-op revoked
                val appops = AppOpsHelper(context)
                val pkgOpsMap = appops.getPackageOps(op)
                val hiddenSuffix = getString(R.string.default_launch_shortcut_prefix).trimEnd(); val notGrantedSuffix = getString(R.string.label_suffix_permission_not_granted)
                pkgs.forEach { info ->
                    val pkg = info.packageName
                    if (entriesUnsorted.contains(pkg)) return@forEach
                    val app = info.applicationInfo
                    if (Apps.isInstalledInCurrentUser(app) && ! Apps.isPrivileged(app)) {   // Result of getPackageOps() may contains uninstalled packages.
                        entriesUnsorted[pkg] = buildBaseLabel(app) + " " + if (IslandAppInfo.isHidden(app) == true) hiddenSuffix else notGrantedSuffix }}

                val sortedPair = entriesUnsorted.toList().asSequence().sortedBy { (_, value) -> value.toString()/* App label TODO: User apps first */}.partition {
                    when (pkgOpsMap[it.first]?.ops?.getOrNull(0)?.mode) { null, MODE_ALLOWED -> true; else -> false }}
                val entriesSorted = sortedPair.first.asSequence().plus(sortedPair.second).toMap()   // Allowed first
                val numAllowed = sortedPair.first.size
                val checkedItems = BooleanArray(entriesSorted.size) { i -> i < numAllowed }
                val pkgList by lazy { entriesSorted.keys.toList() }
                Dialogs.buildCheckList(activity, getString(prompt), entriesSorted.values.toTypedArray(), checkedItems) { _, which, checked ->
                    appops.setMode(pkgList[which], op, if (checked) MODE_ALLOWED else MODE_IGNORED)
                }.setNeutralButton(R.string.action_revoke_all) { _,_ ->
                    sortedPair.first.forEach { appops.setMode(it.first/* pkg */, op, MODE_IGNORED) }
                }.setPositiveButton(R.string.action_done, null).show()
            }}
        }
    }

    private fun setupNotificationChannelTwoStatePreference(@StringRes key: Int, visible: Boolean, notificationId: NotificationIds) {
        setup<TwoStatePreference>(key) {
            if (visible && SDK_INT >= O) {
                isChecked = ! notificationId.isBlocked(context)
                setOnPreferenceChangeListener { _,_ -> true.also { context.startActivity(notificationId.buildChannelSettingsIntent(context)) }}
            } else remove(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        activity.actionBar?.setDisplayHomeAsUpEnabled(true)
        addPreferencesFromResource(R.xml.pref_island)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId != android.R.id.home) super.onOptionsItemSelected(item) else true.also { activity.finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_OWNER_ACTIVATION) IslandSetup.onAddAdminResult(activity)
    }
}

class IslandSettingsActivity: Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = intent?.getStringExtra(Intent.EXTRA_TITLE)
                ?: getString(R.string.tab_island).let { if (Users.current() == Users.profile) it else "it (${Users.current()})"}
        @Suppress("DEPRECATION") fragmentManager.beginTransaction().replace(android.R.id.content, IslandSettingsFragment()).commit()
    }

    class Enabler: BroadcastReceiver() {    // One-time enabler for

        override fun onReceive(context: Context, intent: Intent) {      // ACTION_LOCKED_BOOT_COMPLETED is unnecessary for activity
            if (Intent.ACTION_BOOT_COMPLETED == intent.action || Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) context.packageManager.apply {
                if (Users.isOwner()) return         // Not needed in mainland
                setComponentEnabledSetting(ComponentName(context, IslandSettingsActivity::class.java), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)
                setComponentEnabledSetting(ComponentName(context, Enabler::class.java), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
            }
        }
    }
}

private const val REQUEST_DEVICE_OWNER_ACTIVATION = 1
