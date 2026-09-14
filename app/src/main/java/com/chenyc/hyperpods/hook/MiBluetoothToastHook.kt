package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import com.chenyc.hyperpods.utils.FocusIslandUtil
import com.chenyc.hyperpods.utils.PodImageLoader
import com.chenyc.hyperpods.utils.SystemApisUtils
import com.chenyc.hyperpods.utils.SystemApisUtils.cancelAsUser
import com.chenyc.hyperpods.utils.SystemApisUtils.notifyAsUser
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.batteryStatusCompat
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.RfcommController
import com.chenyc.hyperpods.pods.detectDeviceCapabilities
import com.chenyc.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与 RfcommController 保持同步
    private var localAncMode = 1
    private var notificationReceiverRegistered = false
    private var lastOfficialIslandShape: Triple<Boolean, Boolean, Boolean>? = null

    override fun onHook() {

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        @SuppressLint("WrongConstant")
        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("HyperPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}${batteryParams.case!!.battery}%" +
                            "${if (batteryParams.case!!.isCharging) "⚡ " else " "}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}${batteryParams.left!!.battery}%" +
                        (if (batteryParams.left!!.isCharging) "⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}${batteryParams.right!!.battery}%" +
                        (if (batteryParams.right!!.isCharging) "⚡ " else " ")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "BTHeadset$address",
                        alias,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592)
                )
                // 循环切换降噪模式，指定 package 确保广播路由到 com.android.bluetooth 进程
                val ancCycleIntent = Intent(HyperPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage("com.android.bluetooth")
                ancCycleIntent.setIdentifier("BTHeadset$address")
                ancCycleIntent.putExtra("device_name", alias ?: bluetoothDevice.name ?: "")
                val moduleContext = context.createPackageContext(
                    "com.chenyc.hyperpods", Context.CONTEXT_IGNORE_SECURITY
                )
                val headsetBitmap = PodImageLoader.loadBoxBitmap(context, prefs, address)
                    ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
                if (headsetBitmap == null) {
                    Log.e("HyperPods", "createPodsNotification: headset bitmap null")
                    return
                }
                val headsetIcon = Icon.createWithBitmap(headsetBitmap)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.hyperpods.show_pods_ui").apply {
                        setClassName("com.chenyc.hyperpods", "com.chenyc.hyperpods.PopupActivity")
                        putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
                        putExtra("bluetoothaddress", bluetoothDevice.address)
                        putExtra("device_name", alias)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = alias ?: ""
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = alias ?: ""
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = alias ?: ""
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, 201326592)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                        addActionInfo {
                            val disconnectLabel = moduleContext.getString(R.string.notification_btn_disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, 201326592)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                }
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(alias)
                        .setDefaults(-1)
                        .setContentTitle(alias)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
            } catch (e: Exception) {
                Log.e("HyperPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    notificationManager.cancelAsUser("BTHeadset$address", 10003, SystemApisUtils.getUserAllUserHandle())
                }
            } catch (e: Exception) {
                Log.e("HyperPods", "Failed to cancel Pod Notification!", e)
            }
        }


        fun registerNotificationReceiver(context: Context) {
            if (notificationReceiverRegistered) return
            val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(p0: Context?, p1: Intent?) {
                            if (p1?.action == "chen.action.hyperpods.sendstrongtoast") {
                                val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)!!
                                when (ConfigManager.islandMode()) {
                                    ConfigManager.ISLAND_MODE_MODULE -> {
                                        val address = p1.getStringExtra("address").orEmpty()
                                        FocusIslandUtil.showBatteryIsland(context, prefs, batteryParams, address)
                                    }
                                    ConfigManager.ISLAND_MODE_OFFICIAL -> {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                        lastOfficialIslandShape = batteryShape(batteryParams)
                                    }
                                }
                            } else if (p1?.action == "chen.action.hyperpods.updatepodsnotification") {
                                val batteryParams = p1.getParcelableExtra<BatteryParams>("batteryParams", BatteryParams::class.java)
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                                if (batteryParams != null && ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_OFFICIAL) {
                                    val shape = batteryShape(batteryParams)
                                    if (lastOfficialIslandShape == null || shape != lastOfficialIslandShape) {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                    }
                                    lastOfficialIslandShape = shape
                                }
                                createPodsNotification(device, context, batteryParams!!)
                            } else if (p1?.action == "chen.action.hyperpods.cancelpodsnotification") {
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) as BluetoothDevice
                                cancelNotification(device, context)
                                MiuiStrongToastUtil.hideOfficialToast(context)
                                lastOfficialIslandShape = null
                            } else if (p1?.action == HyperPodsAction.UPDATE_PODS_NOTIFICATION) {
                                // 水月雨线：电量由蓝牙进程里的控制器发来，extra 走同一个
                                // BatteryStatusIntent helper，所以渲染路径与 OPPO 完全共用
                                val batteryParams = p1.batteryStatusCompat() ?: return
                                val device = p1.getParcelableExtra(
                                    HyperPodsAction.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                                createPodsNotification(device, context, batteryParams)
                            } else if (p1?.action == HyperPodsAction.SEND_STRONG_TOAST) {
                                val batteryParams = p1.batteryStatusCompat() ?: return
                                val address = p1.getStringExtra(HyperPodsAction.EXTRA_MAC).orEmpty()
                                when (ConfigManager.islandMode()) {
                                    ConfigManager.ISLAND_MODE_MODULE ->
                                        FocusIslandUtil.showBatteryIsland(context, prefs, batteryParams, address)

                                    ConfigManager.ISLAND_MODE_OFFICIAL -> {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                        lastOfficialIslandShape = batteryShape(batteryParams)
                                    }
                                }
                            } else if (p1?.action == HyperPodsAction.CANCEL_PODS_NOTIFICATION) {
                                val device = p1.getParcelableExtra(
                                    HyperPodsAction.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                                if (device != null) cancelNotification(device, context)
                                MiuiStrongToastUtil.hideOfficialToast(context)
                                lastOfficialIslandShape = null
                            } else if (p1?.action == HyperPodsAction.ACTION_PODS_ANC_CHANGED) {
                                // 同步耳机实际 ANC 状态到本地缓存，确保下次循环切换时状态准确
                                localAncMode = p1.getIntExtra("status", 1)
                            } else if (p1?.action == HyperPodsAction.ACTION_CYCLE_ANC) {
                                val snapshot = runCatching { RfcommController.currentStatusSnapshot() }.getOrNull()
                                val capabilities = detectDeviceCapabilities(
                                    context = p0,
                                    deviceName = snapshot?.deviceName
                                        ?: p1.getStringExtra("device_name").orEmpty(),
                                    productId = snapshot?.productId,
                                )
                                val cycle = if (capabilities.adaptiveSupported) {
                                    listOf(2, 4, 3, 1)
                                } else {
                                    listOf(2, 3, 1)
                                }
                                val currentIndex = cycle.indexOf(if (localAncMode in 5..8) 2 else localAncMode)
                                localAncMode = cycle[(currentIndex + 1).floorMod(cycle.size)]
                                Intent(HyperPodsAction.ACTION_ANC_SELECT).apply {
                                    putExtra("status", localAncMode)
                                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                    p0?.sendBroadcast(this)
                                }
                            }
                        }
                    }

            val intentFilter = IntentFilter("chen.action.hyperpods.sendstrongtoast")
            intentFilter.addAction("chen.action.hyperpods.updatepodsnotification")
            intentFilter.addAction("chen.action.hyperpods.cancelpodsnotification")
            intentFilter.addAction(HyperPodsAction.ACTION_CYCLE_ANC)
            intentFilter.addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            intentFilter.addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            intentFilter.addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            // 水月雨线（协议栈在蓝牙进程，通知侧在这里渲染）
            intentFilter.addAction(HyperPodsAction.UPDATE_PODS_NOTIFICATION)
            intentFilter.addAction(HyperPodsAction.SEND_STRONG_TOAST)
            intentFilter.addAction(HyperPodsAction.CANCEL_PODS_NOTIFICATION)
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            notificationReceiverRegistered = true
            context.sendBroadcast(Intent(HyperPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }

        fun hookNotificationConstructor(vararg parameterTypes: Class<*>) {
            runCatching {
                hookConstructorAfter(
                    findConstructor(
                        "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
                        *parameterTypes,
                    )
                ) {
                    val context = args.filterIsInstance<Context>().firstOrNull()
                        ?: runCatching { getObjectField(instance, "mContext") as? Context }.getOrNull()
                        ?: return@hookConstructorAfter
                    registerNotificationReceiver(context)
                }
            }.onFailure {
                Log.d("HyperPods", "MiuiBluetoothNotification constructor unavailable: ${parameterTypes.joinToString { type -> type.name }}")
            }
        }

        hookNotificationConstructor(Context::class.java, Looper::class.java)
        runCatching {
            hookNotificationConstructor(
                Looper::class.java,
                findClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"),
            )
        }
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    private fun batteryShape(battery: BatteryParams): Triple<Boolean, Boolean, Boolean> = Triple(
        battery.left?.isConnected == true,
        battery.right?.isConnected == true,
        battery.case?.isConnected == true,
    )
}
