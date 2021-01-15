package com.example.sensorcollector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.neovisionaries.bluetooth.ble.advertising.ADPayloadParser
import com.neovisionaries.bluetooth.ble.advertising.IBeacon
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

//import com.example.sensorcollector

@SuppressLint("SimpleDateFormat")
fun getNow(): String {
    return if (android.os.Build.VERSION.SDK_INT >= 24) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())
    } else {
        val tms = Calendar.getInstance()
        tms.get(Calendar.YEAR).toString() + "-" + tms.get(Calendar.MONTH)
            .toString() + "-" + tms.get(Calendar.DAY_OF_MONTH)
            .toString() + " " + tms.get(Calendar.HOUR_OF_DAY)
            .toString() + ":" + tms.get(Calendar.MINUTE).toString() + ":" + tms.get(Calendar.SECOND)
            .toString() + "." + tms.get(Calendar.MILLISECOND).toString()
    }
}

@SuppressLint("SimpleDateFormat")
fun timestamp2millis(timestamp: Long): String {
    val rxTimestampMillis: Long = System.currentTimeMillis() -
            SystemClock.elapsedRealtime() +
            timestamp / 1000000

    val rxDate = Date(rxTimestampMillis)

    return SimpleDateFormat("yyyy:MM:dd:HH:mm:ss.SSS").format(rxDate)
}


class CollectorActivity : Activity(), SensorEventListener {

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN
    )
    private val LOCATION_REQUEST_ID = 1

    private val BLUETOOTH_ENABLE_REQUEST_CODE = 2

    private var isStorageEnabled: Boolean = false
    private var isUiRefreshEnabled: Boolean = false

    private lateinit var sensorManager: SensorManager
    private lateinit var mLocationManager: LocationManager
    private lateinit var wifiManager: WifiManager
    private var mBluetoothAdapter: BluetoothAdapter? = null

    private var tx: TextView? = null
    private var str: String = ""

    private var pressureReading: Float = 0F
    private var lightReading: Float = 0F
    private var ambientTempReading: Float = 0F
    private var relHumidityReading: Float = 0F

    private var accelerometerReading = FloatArray(3)
    private var accelerometerUncalibratedReading = FloatArray(6)
    private var gravityReading = FloatArray(3)
    private var gyroscopeReading = FloatArray(3)
    private var gyroscopeUncalibratedReading = FloatArray(6)
    private var linearAccelerationReading = FloatArray(3)
    private var rotationVectorReading = FloatArray(4)
    private var gameRotationVectorReading = FloatArray(3)
    private var geomagneticRotationVectorReading = FloatArray(3)
    private var magneticFieldReading = FloatArray(3)
    private var magneticFieldUncalibratedReading = FloatArray(6)
    private var stepCounterReading = 0F
    private var proximityReading = 0F

    private val TAG = "CollectorActivity"
    private val TAG_ACTIVITY_STATE = "MainActivityState"

    private var timer = Timer()

    // private val writeFileTask = WriteFileTask()

    private val SCAN_PERIOD: Long = 6000
    private var mScanning: Boolean = false
    private val handler = Handler()

    private var uniqueID = ""
    private var carryID = -1
    private var environmentID = -1
    private var speedLevelID = -1


    private lateinit var mFile: File

    // files for sensors-motion
    private lateinit var mFileAccelerometer: File
    private lateinit var mFileAccelerometerUncalibrated: File
    private lateinit var mFileGravity: File
    private lateinit var mFileGyroscope: File
    private lateinit var mFileGyroscopeUncalibrated: File
    private lateinit var mFileLinearAcceleration: File
    private lateinit var mFileRotationVector: File
    private lateinit var mFileStepCounter: File
    private lateinit var mFileStepDetector: File

    // files for sensors-position
    private lateinit var mFileGameRotationVector: File
    private lateinit var mFileGeomagnetcRotationVector: File
    private lateinit var mFileMagneticField: File
    private lateinit var mFileMagneticFieldUncalibrated: File
    // orientation sensor is deprecated
    // However, you can calculate it. See https://developer.android.google.cn/guide/topics/sensors/sensors_position#sensors-pos-orient
    private lateinit var mFileProximity: File

    // files for sensors-environment
    private lateinit var mFileAmbientTemperature: File
    private lateinit var mFileLight: File
    private lateinit var mFilePressure: File
    private lateinit var mFileRelatveHumidity: File
    // device temperature sensor is deprecated

    // file for wifi scan results
    private lateinit var mFileWifiScanResults: File

    // file for bluetooth scan results
    private lateinit var mFileBluetoothScanResults: File
    private lateinit var mFileBle: File

    private lateinit var mFileMetaData: File

    private lateinit var mWakeLock: PowerManager.WakeLock


    private fun hasPermissions(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Permissions granted at install time.
            return true;
        }
        for(p in REQUIRED_PERMISSIONS) {
            if(ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED)
                return false
        }
        return true
    }

    private fun requestPermission(activity: Activity) {
        if(!hasPermissions(this)) {
            ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, LOCATION_REQUEST_ID)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        if(requestCode == LOCATION_REQUEST_ID) {
            if(grantResults.isNotEmpty() && grantResults[0]==PackageManager.PERMISSION_GRANTED) {
                // permissions granted
            }
            else if(grantResults.isNotEmpty() && grantResults[0]==PackageManager.PERMISSION_DENIED){
                // permissions denied
            }
            else
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun createSingleFile(firstLine: String, file: File) {
        if(!file.exists()) {
            if(!file.createNewFile()) {
                Log.e(TAG, "fail to create file: ${file.path}")
            }
            else {
                Log.d(TAG,"file created successfully: ${file.path}")
                file.appendText(firstLine)
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun openFiles() {
        val timeStart = getNow()
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        // if dataDir does not exist, create it
        val dataDir = File(dir, "data/$timeStart")
        if(!dataDir.exists()) {
            if(!dataDir.mkdirs()) {
                Log.e(TAG,"fail to create dirs: ${dataDir.path}")
            }
            else {
                Log.d(TAG,"dirs created successfully: ${dataDir.path}")
            }
        }

        mFileAccelerometer = File(dataDir, "accelerometer.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileAccelerometer)

        mFileAccelerometerUncalibrated = File(dataDir, "accelerometer_uncalibrated.csv")
        createSingleFile("x,y,z,xc,yc,zc,timestamp,date\n", mFileAccelerometerUncalibrated)

        mFileGravity = File(dataDir, "gravity.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileGravity)

        mFileGyroscope = File(dataDir, "gyroscope.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileGyroscope)

        mFileGyroscopeUncalibrated = File(dataDir, "gyroscope_uncalibrated.csv")
        createSingleFile("x,y,z,xc,yc,zc,timestamp,date\n", mFileGyroscopeUncalibrated)

        mFileLinearAcceleration = File(dataDir, "linear_acceleration.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileLinearAcceleration)

        mFileRotationVector = File(dataDir, "rotation_vector.csv")
        createSingleFile("x,y,z,s,timestamp,date\n", mFileRotationVector)

        mFileStepCounter = File(dataDir, "step_counter.csv")
        createSingleFile("count,timestamp,date\n", mFileStepCounter)

        mFileStepDetector = File(dataDir, "step_detector.csv")
        createSingleFile("timestamp,date\n", mFileStepDetector)

        mFileGameRotationVector = File(dataDir, "game_rotation_vector.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileGameRotationVector)

        mFileGeomagnetcRotationVector = File(dataDir, "geomagnetic_rotation_vector.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileGeomagnetcRotationVector)

        mFileMagneticField = File(dataDir, "magnetic_field.csv")
        createSingleFile("x,y,z,timestamp,date\n", mFileMagneticField)

        mFileMagneticFieldUncalibrated = File(dataDir, "magnetic_field_uncalibrated.csv")
        createSingleFile("x,y,z,xc,yc,zc,timestamp,date\n", mFileMagneticFieldUncalibrated)

        mFileProximity = File(dataDir, "proximity.csv")
        createSingleFile("d,timestamp,date\n", mFileProximity)

        mFileAmbientTemperature = File(dataDir, "ambient_temperature.csv")
        createSingleFile("temperature,timestamp,date\n", mFileAmbientTemperature)

        mFileLight = File(dataDir, "light.csv")
        createSingleFile("light,timestamp,date\n", mFileLight)

        mFilePressure = File(dataDir, "pressure.csv")
        createSingleFile("pressure,timestamp,date\n", mFilePressure)

        mFileRelatveHumidity = File(dataDir, "relative_humidity.csv")
        createSingleFile("humidity,timestamp,date\n", mFileRelatveHumidity)

        mFileWifiScanResults = File(dataDir, "wifi_scan.csv")
        createSingleFile("bssid,ssid,level,timastamp,date\n", mFileWifiScanResults)

        //mFileBluetoothScanResults = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "$timeStart/bluetooth_scan.csv")
        //createSingleFile("name,uuid,address,RSSI,date\n", mFileBluetoothScanResults)

        mFileBle = File(dataDir, "ble_scan.csv")
        createSingleFile("mac,major,minor,rssi,txPowerLevel,manufacturer,manufacturerSpecifiedData,scanRecordTrimmed,date\n", mFileBle)

        mFileMetaData = File(dataDir, "metadata.csv")
        createSingleFile("guid,carryID,carryDesc,environmentID,environmentDesc,speedLevelID,speedLevelDesc\n", mFileMetaData)
    }

    private fun saveMetaData() {
        mFileMetaData.appendText("${uniqueID}," +
                "${carryID},${resources.getStringArray(R.array.carry_list)[carryID]}," +
                "${environmentID},${resources.getStringArray(R.array.environment_list)[environmentID]}," +
                "${speedLevelID},${resources.getStringArray(R.array.speed_level_list)[speedLevelID]}")
    }


    private fun initBlueTooth() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if(mBluetoothAdapter == null)
            Log.d(TAG,"Bluetooth not supported")
        else {
            Log.d(TAG,"Bluetooth is supported")
            if(!mBluetoothAdapter!!.isEnabled)
                openBlueToothAsync()

            Log.d(TAG,"bluetooth init")
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun scanLeDevice(enable: Boolean) {
        when (enable) {
            true -> {
                // Stops scanning after a pre-defined scan period.
                Handler().postDelayed({
                    mScanning = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mBluetoothAdapter?.stopLeScan(leScanCallback)
                    }
                    scanLeDevice(true)
                }, SCAN_PERIOD)
                mScanning = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mBluetoothAdapter?.startLeScan(leScanCallback)
                }
            }
            else -> {
                mScanning = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mBluetoothAdapter?.stopLeScan(leScanCallback)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private val leScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
        val builder = java.lang.StringBuilder()
        builder.append(device.address)
        var st = ""
        for(b in scanRecord)
            st += String.format("%02X",b)
        val trimmed = st.dropLastWhile { c -> c=='0' }
        Log.d("ble","${device.address}, $rssi,${trimmed.length/2}.${st.dropLastWhile { c -> c=='0' }}")
        Log.d("ble",parseScanRecord(scanRecord).toString())

        val util = ScanRecordUtil.parseFromBytes(scanRecord)
            Log.d("ble","basics: ${util.advertiseFlags}, ${util.txPowerLevel}")

        val majorList = ArrayList<String>()
        val minorList = ArrayList<String>()

        val structures = ADPayloadParser.getInstance().parse(scanRecord)
        if(structures==null || structures.size==0) {
//            builder.append(",,")
        }
        else {
            for(structure in structures) {
                if(structure is IBeacon) {
                    val iBeacon = structure
                    Log.d("ble", "manu-ibeacon: mj:${iBeacon.major}, mn:${iBeacon.minor}")
                    majorList.add("${iBeacon.major}")
                    minorList.add("${iBeacon.minor}")
//                    builder.append(",${iBeacon.major},${iBeacon.minor}")
                }
                else {
//                    builder.append(",,")
                }
//                break
            }
        }

        builder.append(",${majorList.joinToString(separator = "-")},${minorList.joinToString(separator = "-")}")

        builder.append(",${rssi},${util.txPowerLevel}")




        if(util.manufacturerSpecificData!=null && util.manufacturerSpecificData.size()!=0) {
            var ss = ""
            for(b in util.manufacturerSpecificData.valueAt(0))
                ss += String.format("%02X",b)
            Log.d("ble","manu: (${ss.length}) ${util.manufacturerSpecificData.keyAt(0)}, " + ss)
            builder.append(",${util.manufacturerSpecificData.keyAt(0)},$ss")
        }
        else {
            builder.append(",,")
        }

        builder.append(",${st.dropLastWhile { c -> c=='0' }},${getNow()}\n")

        Log.d("ble","info: ${builder}")


//        if(util.manufacturerSpecificData!=null)
//            Log.d("ble","manu: " + util.manufacturerSpecificData.toString())
//        if(util.serviceUuids!=null && util.serviceUuids.size!=0) {
//            Log.d("ble","svcuuid:" + util.serviceUuids.toString())
//            builder.append(",${util.serviceUuids[0].toString()}")
//        }
//        else
//            builder.append(",")
//        if(util.serviceData!=null && util.serviceData.size!=0) {
//            Log.d("ble","svcdata"+util.serviceData.toString())
//        }


        if(isUiRefreshEnabled) {
            tx = findViewById(R.id.bleScanTextView)
            tx!!.text = "> ble scan results\n\t${device.address},\n\t$rssi,\n\t${getNow()}\n";
        }

        if(isStorageEnabled)
            mFileBle.appendText(builder.toString())
    }

    private fun parseScanRecord(scanRecord: ByteArray): WeakHashMap<Int, String> {
        val ret = WeakHashMap<Int, String>()
        var index = 0
        while (index < scanRecord.size) {
            val length = scanRecord[index].toInt()
            index++
            //Zero value indicates that we are done with the record now
            if (length == 0) break
            val type = scanRecord[index].toInt()
            //if the type is zero, then we are pass the significant section of the data,
            // and we are thud done
            if (type == 0) break
            val data = scanRecord.copyOfRange(index + 1, index + length)
            if (data.isNotEmpty()) {
                val hex = java.lang.StringBuilder(data.size * 2)
                // the data appears to be there backwards
                for (bb in data.indices.reversed()) {
                    hex.append(String.format("%02X", data[bb]))
                }
                ret[type] = hex.toString()
            }
            Log.d("bleParse","len:${length}, type:${type}, data:${ret[type]}")
            index += length
        }
        return ret;
    }

    private fun openBlueToothSync(activity: Activity) {
        intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        activity.startActivityForResult(intent, BLUETOOTH_ENABLE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == BLUETOOTH_ENABLE_REQUEST_CODE) {
            if(resultCode == RESULT_OK)
                Log.d(TAG,"Bluetooth opened")
            else
                Log.d(TAG,"fail to open bluetooth")
        }
    }

    private fun openBlueToothAsync() {
        if(mBluetoothAdapter != null) {
            if(mBluetoothAdapter!!.isEnabled) {
                return
            }
            if(mBluetoothAdapter!!.enable())
                Log.d(TAG,"bluetooth opened")
            else
                Log.d(TAG,"fail to open bluetooth")
        }
    }


    private val wifiScanReceiver = object: BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if(success)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wifiScanSuccess()
                }
            else
                wifiScanFailure()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun wifiScanSuccess() {
        Log.d(TAG,"wifi received")
        if(!isStorageEnabled && !isUiRefreshEnabled) return

        val results = wifiManager.scanResults
        val builder = StringBuilder("> wifi scan results (success)\n")

        for(result in results) {
            if(isStorageEnabled)
                mFileWifiScanResults.appendText("${result.BSSID} , ${result.SSID} , ${result.level}, ${result.timestamp}, ${timestamp2millis(result.timestamp)}\n")
            if(isUiRefreshEnabled)
                builder.append("\t${result.BSSID}, ${result.SSID}, ${result.level}, ${result.timestamp}\n")
        }

        if(isUiRefreshEnabled) {
            builder.deleteCharAt(builder.lastIndex)
            val wifiTx = findViewById<TextView>(R.id.wifiScanTextView)
            wifiTx.text = builder.toString()
        }
        // wifiManager.startScan()
        // wifiManager
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun wifiScanFailure() {
        Log.d(TAG,"wifi scan failed");
        // old results
        val results = wifiManager.scanResults
        //Log.d("wifiFailure",results.toString())
        val builder = StringBuilder("> wifi scan results (failure)\n")

        for(result in results) {
            if(isStorageEnabled)
                mFileWifiScanResults.appendText("${result.BSSID} , ${result.SSID} , ${result.level}, ${result.timestamp}, ${timestamp2millis(result.timestamp)}\n")
            if(isUiRefreshEnabled)
                builder.append("\t${result.BSSID}, ${result.SSID}, ${result.level}, ${result.timestamp}\n")
        }

        if(isUiRefreshEnabled) {
            builder.deleteCharAt(builder.lastIndex)
            val wifiTx = findViewById<TextView>(R.id.wifiScanTextView)
            wifiTx.text = builder.toString()
        }
        // do nothing
    }


    private val bluetoothReceiver = object: BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG,"bluetooth received")
            if(!isUiRefreshEnabled && !isStorageEnabled) return
            val action = intent.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val RSSI = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,-1)

                    val builder: StringBuilder = StringBuilder("> bluetooth scan results: ${getNow()}\n")
                    if (device != null) {
                        if(isStorageEnabled)
                            mFileBluetoothScanResults.appendText("${device.name} , ${device.uuids} , ${device.address} , $RSSI, ${getNow()}\n")
                        if(isUiRefreshEnabled)
                            builder.append("${device.name}, ${device.uuids}, ${device.address}, $RSSI")

                    }

                    if(isUiRefreshEnabled) {
                        val bluetoothTx = findViewById<TextView>(R.id.bluetoothScanTextView)
                        bluetoothTx.text = builder.toString()
                    }

                }
            }

        }
    }


    private var gnssMeasurementsEventListener = @RequiresApi(Build.VERSION_CODES.N)
    object : GnssMeasurementsEvent.Callback() {

        private fun gnssClockToString(gnssClock: GnssClock?): String {
            if(gnssClock == null) return ""
            val format = "   %-4s = %s\n"
            val builder = StringBuilder("GNSS CLOCK:\n")
//            var numberFormat = DecimalFormat("#0.000")

            // LeapSecond/s, 闰秒
            if(gnssClock.hasLeapSecond())
                builder.append(String.format(format, "LeapSecond(s)",gnssClock.leapSecond))
            // TimeNanos/ns, GNSS接收器内部硬件时钟值
            builder.append(String.format(format, "TimeNanos(ns)", gnssClock.timeNanos))
            // TimeUncertaintyNanos/ns, 时钟时间的不确定度
            if(gnssClock.hasTimeUncertaintyNanos())
                builder.append(String.format(format, "TimeUcertaintyNanos(ns)", gnssClock.timeUncertaintyNanos))
            // BiasNanos/ns, 亚纳秒偏差
            if(gnssClock.hasBiasNanos())
                builder.append(String.format(format, "BiasNanos(ns)", gnssClock.biasNanos));
            // FullBiasNanos/ns, 总偏差(GPS接收器的硬件时钟和真实GPS时间的偏差值)
            if(gnssClock.hasFullBiasNanos())
                builder.append(String.format(format, "FullBiasNanos(ns)", gnssClock.fullBiasNanos))

            //  local estimate of GPS time = TimeNanos - (FullBiasNanos + BiasNanos)
            //  UtcTimeNanos = TimeNanos - (FullBiasNanos + BiasNanos) - LeapSecond * 1,000,000,000

            // BiasUncertaintyNanos/ns, 时钟偏差的不确定度
            if(gnssClock.hasBiasUncertaintyNanos())
                builder.append(String.format(format, "BiasUncertaintyNanos(ns)", gnssClock.biasUncertaintyNanos))
            // DriftNanosPerSecond/(ns/s), 时钟漂移
            if(gnssClock.hasDriftNanosPerSecond())
                builder.append(String.format(format,"DriftNanosPerSecond(ns/s)", gnssClock.driftNanosPerSecond))
            // DriftUncertaintyNanosPerSecond/(ns/s), 时钟漂移的不确定度
            if(gnssClock.hasDriftUncertaintyNanosPerSecond())
                builder.append(String.format(format,"DriftUncertaintyNanosPerSecond(ns/s)",gnssClock.driftUncertaintyNanosPerSecond))
            // HardwareClockDiscontinuityCount, 硬件时钟中断计数
            builder.append(String.format(format,"HardwareClockDiscontinuityCount",gnssClock.hardwareClockDiscontinuityCount))

            return builder.toString()
        }

        private fun gnssMeasurementsToString(gnssMeasurement: GnssMeasurement?): String {
            if(gnssMeasurement == null) return ""
            val format = "   %-4s = %s\n"
            val builder = StringBuilder("GNSS MEASUREMENT:\n")
//            var numberFormat1 = DecimalFormat("#0.000")
//            var numberFormat2 = DecimalFormat("#0.000E00")

            // satellite ID, 卫星ID
            builder.append(String.format(format, "Svid", gnssMeasurement.svid))
            // ConstellationType, 卫星类型
            builder.append(String.format(format, "ConstellationType", gnssMeasurement.constellationType))
            // TimeOffsetNanos, 时间偏移/ns
            builder.append(String.format(format, "TimeOffsetNanos", gnssMeasurement.timeOffsetNanos))
            // State, 同步状态
            builder.append(String.format(format, "State", gnssMeasurement.state))
            // PseudorangeRateMetersPerSecond/(m/s), 伪距速率
            builder.append(String.format(format, "PseudorangeRateMetersPerSecond(m/s)", gnssMeasurement.pseudorangeRateMetersPerSecond))
            // PseudorangeRateUncertaintyMetersPerSecond(m/s), 伪距速率不确定度
            builder.append(String.format(format, "PseudorangeRateUncertaintyMetersPerSecond(m/s)", gnssMeasurement.pseudorangeRateUncertaintyMetersPerSecond))

            // AccumulatedDeltaRangeState, 累积增量范围状态
            if(gnssMeasurement.accumulatedDeltaRangeState != 0) {
                builder.append(String.format(format, "accumulatedDeltaRangeState", gnssMeasurement.accumulatedDeltaRangeState))
                // AccumulatedDeltaRangeMeters/m, 累积增量范围
                builder.append(String.format(format, "AccumulatedDeltaRangeMeters", gnssMeasurement.accumulatedDeltaRangeMeters))
                // AccumulatedDeltaRangeUncertaintyMeters/m, 累积增量范围不确定度
                builder.append(String.format(format, "AccumulatedDeltaRangeUncertaintyMeters", gnssMeasurement.accumulatedDeltaRangeUncertaintyMeters))
            }
            else {
                // do nothing
            }

            // CarrierFrequencyHz/Hz, 载波频率
            if(gnssMeasurement.hasCarrierFrequencyHz())
                builder.append(String.format(format, "CarrierFrequencyHz", gnssMeasurement.carrierFrequencyHz))

            // 载波周期数已经弃用
            // getCarrierCycles Deprecated: use getAccumulatedDeltaRangeMeters() instead.
            // Added in API level 24. Deprecated in API level 28

            // 载波相位已经弃用
            // getCarrierPhase Deprecated: use getAccumulatedDeltaRangeMeters() instead.
            // Added in API level 24. Deprecated in API level 28

            // 载波相位不确定度已经弃用
            // getCarrierPhaseUncertainty Deprecated: use getAccumulatedDeltaRangeMeters() instead.
            // Added in API level 24. Deprecated in API level 28

            // MultipathIndicator, 多径效应指示器
            // 0->未知, 1->有多径效应, 2->无多径效应
            builder.append(String.format(format, "MultipathIndicator", gnssMeasurement.multipathIndicator))

            // SnrInDb/Db, 信噪比
            if(gnssMeasurement.hasSnrInDb())
                builder.append(String.format(format, "SnrInDb", gnssMeasurement.snrInDb))

            // AutomaticGainControlLevelDb/Db, 自动效应控制级别
            if(gnssMeasurement.hasAutomaticGainControlLevelDb())
                builder.append(String.format(format, "AutomaticGainControlLevelDb", gnssMeasurement.automaticGainControlLevelDb))

            return builder.toString()
        }

        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent?) {
            //super.onGnssMeasurementsReceived(eventArgs)
            val builder = StringBuilder("> GNSS raw measurements:")
            if (eventArgs != null) {
                builder.append(gnssClockToString(eventArgs.clock))
                for(measurement in eventArgs.measurements) {
                    builder.append(gnssMeasurementsToString(measurement))
                    builder.append("\n")
                }
            }
            tx = findViewById(R.id.gnssMeasurementsTextView)
            tx!!.text = builder.toString()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun RegisterGNSSMeasurements() {
        var is_register_success: Boolean = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            mLocationManager.registerGnssMeasurementsCallback(gnssMeasurementsEventListener, null)

            return
        }
        else false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun unRegisterMeasurements() {
        mLocationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsEventListener)
    }

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mLocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        initBlueTooth()

        val saveBtn = findViewById<Button>(R.id.saveEnableButton)
        saveBtn.setOnClickListener {
            if(isStorageEnabled) {
                isStorageEnabled = false
                saveBtn.text = "click to enable data storage"
                mWakeLock.release()
            }
            else {
                mWakeLock.acquire(10*60*1000L /*10 minutes*/)
                openFiles()
                saveMetaData()
                isStorageEnabled = true
                saveBtn.text = "click to disable data storage"
            }
        }

        val uiBtn = findViewById<Button>(R.id.uiEnableButton)
        uiBtn.setOnClickListener {
            if(isUiRefreshEnabled) {
                isUiRefreshEnabled = false
                uiBtn.text = "click to enable ui refresh"
            }
            else {
                isUiRefreshEnabled = true
                uiBtn.text = "click to disable ui refresh"
            }
        }

        getMetaData()
        uuidTextView.text = uniqueID

        val carrySpinner : Spinner = findViewById(R.id.carrySpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.carry_list,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            carrySpinner.adapter = adapter
        }

        carrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                carryID = p2
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {
                // do nothing
            }
        }

        val environmentSpinner : Spinner = findViewById(R.id.environmentSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.environment_list,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            environmentSpinner.adapter = adapter
        }


        environmentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                environmentID = p2
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {
                // do nothing
            }
        }

        val speedLevelSpinner : Spinner = findViewById(R.id.speedLevelSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.speed_level_list,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            speedLevelSpinner.adapter = adapter
        }

        speedLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                speedLevelID = p2
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {
                // do nothing
            }
        }

        requestPermission(this)

        mWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorCollector:SensorTag")

        Log.d(TAG, "created")
        periodicJob()
        scanLeDevice(true);
    }

    private fun getMetaData() {
        val prefs = getSharedPreferences("data", Context.MODE_PRIVATE)
        uniqueID = prefs.getString("uuid", "").toString()
        if(uniqueID.isEmpty()) {
            uniqueID = UUID.randomUUID().toString()
            val editor = prefs.edit()
            editor.putString("uuid", uniqueID)
            editor.apply()
        }

        carryID = prefs.getInt("carryID", -1)
        environmentID = prefs.getInt("environmentID", -1)
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
//        TODO("Not yet implemented")
    }

    private fun getSensorsData(event: SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_PRESSURE -> pressureReading = event.values[0]
            Sensor.TYPE_LIGHT ->  lightReading = event.values[0]
            Sensor.TYPE_AMBIENT_TEMPERATURE -> ambientTempReading = event.values[0]
            Sensor.TYPE_RELATIVE_HUMIDITY -> relHumidityReading = event.values[0]

            Sensor.TYPE_ACCELEROMETER ->  System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ->  System.arraycopy(event.values, 0, accelerometerUncalibratedReading, 0, accelerometerUncalibratedReading.size)
            Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, gravityReading, 0, gravityReading.size)
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, gyroscopeReading, 0, gyroscopeReading.size)
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> System.arraycopy(event.values, 0, gyroscopeUncalibratedReading, 0, gyroscopeUncalibratedReading.size)
            Sensor.TYPE_LINEAR_ACCELERATION -> System.arraycopy(event.values, 0, linearAccelerationReading, 0, linearAccelerationReading.size)
            Sensor.TYPE_ROTATION_VECTOR -> System.arraycopy(event.values, 0, rotationVectorReading, 0, rotationVectorReading.size)
            Sensor.TYPE_STEP_COUNTER -> stepCounterReading = event.values[0]

            Sensor.TYPE_GAME_ROTATION_VECTOR -> System.arraycopy(event.values, 0, gameRotationVectorReading, 0, gameRotationVectorReading.size)
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> System.arraycopy(event.values, 0, geomagneticRotationVectorReading, 0, geomagneticRotationVectorReading.size)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magneticFieldReading, 0, magneticFieldReading.size)
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> System.arraycopy(event.values, 0, magneticFieldUncalibratedReading, 0, magneticFieldUncalibratedReading.size)
            Sensor.TYPE_PROXIMITY -> proximityReading = event.values[0]
        }
    }

    private fun saveSensorsData_deprecated(eventType: Int, timestamp: Long) {
        val timeSuffix = "${timestamp},${timestamp2millis(timestamp)}\n"
        when(eventType) {
            Sensor.TYPE_PRESSURE ->
                mFilePressure.appendText("$pressureReading,$timeSuffix")
            Sensor.TYPE_LIGHT ->
                mFileLight.appendText("$lightReading,$timeSuffix")
            Sensor.TYPE_AMBIENT_TEMPERATURE ->
                mFileAmbientTemperature.appendText("$ambientTempReading,$timeSuffix")
            Sensor.TYPE_RELATIVE_HUMIDITY ->
                mFileRelatveHumidity.appendText("$relHumidityReading,$timeSuffix")

            Sensor.TYPE_ACCELEROMETER ->
                mFileAccelerometer.appendText(accelerometerReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ->
                mFileAccelerometerUncalibrated.appendText(accelerometerUncalibratedReading.joinToString()+",$timeSuffix")
            Sensor.TYPE_GRAVITY ->
                mFileGravity.appendText( gravityReading.joinToString() +",$timeSuffix")
            Sensor.TYPE_GYROSCOPE ->
                mFileGyroscope.appendText(gyroscopeReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
                mFileGyroscopeUncalibrated.appendText(gyroscopeUncalibratedReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_LINEAR_ACCELERATION ->
                mFileLinearAcceleration.appendText(linearAccelerationReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_ROTATION_VECTOR ->
                mFileRotationVector.appendText(rotationVectorReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_STEP_COUNTER ->
                mFileStepCounter.appendText("${stepCounterReading.toInt()},$timeSuffix")
            Sensor.TYPE_STEP_DETECTOR ->
                mFileStepDetector.appendText(timeSuffix)

            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                mFileGameRotationVector.appendText(gameRotationVectorReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
                mFileGeomagnetcRotationVector.appendText(geomagneticRotationVectorReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_MAGNETIC_FIELD ->
                mFileMagneticField.appendText(magneticFieldReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                mFileMagneticFieldUncalibrated.appendText(magneticFieldUncalibratedReading.joinToString() + ",$timeSuffix")
            Sensor.TYPE_PROXIMITY ->
                mFileProximity.appendText("$proximityReading,$timeSuffix")
        }
    }
    private fun saveSensorsData(event: SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_PRESSURE ->
                WriteFileTask().execute(SensorData(event, mFilePressure.path, pressureReading, FloatArray(0)))
            Sensor.TYPE_LIGHT ->
                WriteFileTask().execute(SensorData(event, mFileLight.path, lightReading, FloatArray((0))))
            Sensor.TYPE_AMBIENT_TEMPERATURE ->
                WriteFileTask().execute(SensorData(event, mFileAmbientTemperature.path, ambientTempReading, FloatArray(0)))
            Sensor.TYPE_RELATIVE_HUMIDITY ->
                WriteFileTask().execute(SensorData(event, mFileRelatveHumidity.path, relHumidityReading, FloatArray(0)))

            Sensor.TYPE_ACCELEROMETER ->
                WriteFileTask().execute(SensorData(event, mFileAccelerometer.path, 0F, accelerometerReading))
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ->
                WriteFileTask().execute(SensorData(event, mFileAccelerometerUncalibrated.path, 0F, accelerometerUncalibratedReading))
            Sensor.TYPE_GRAVITY ->
                WriteFileTask().execute(SensorData(event, mFileGravity.path, 0F, gravityReading))
            Sensor.TYPE_GYROSCOPE ->
                WriteFileTask().execute(SensorData(event, mFileGyroscope.path, 0F, gyroscopeReading))
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
                WriteFileTask().execute(SensorData(event, mFileGyroscopeUncalibrated.path, 0F, gyroscopeUncalibratedReading))
            Sensor.TYPE_LINEAR_ACCELERATION ->
                WriteFileTask().execute(SensorData(event, mFileLinearAcceleration.path, 0F, linearAccelerationReading))

            Sensor.TYPE_ROTATION_VECTOR ->
                WriteFileTask().execute(SensorData(event, mFileRotationVector.path, 0F, rotationVectorReading))

            Sensor.TYPE_STEP_COUNTER ->
                WriteFileTask().execute(SensorData(event, mFileStepCounter.path, stepCounterReading, FloatArray(0)))
            Sensor.TYPE_STEP_DETECTOR ->
                WriteFileTask().execute(SensorData(event, mFileStepDetector.path, 0F, FloatArray(0)))

            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                WriteFileTask().execute(SensorData(event, mFileGameRotationVector.path, 0F, gameRotationVectorReading))
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
                WriteFileTask().execute(SensorData(event, mFileGeomagnetcRotationVector.path, 0F, geomagneticRotationVectorReading))
            Sensor.TYPE_MAGNETIC_FIELD ->
                WriteFileTask().execute(SensorData(event, mFileMagneticField.path, 0F, magneticFieldReading))
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                WriteFileTask().execute(SensorData(event, mFileMagneticFieldUncalibrated.path, 0F, magneticFieldUncalibratedReading))
            Sensor.TYPE_PROXIMITY ->
                WriteFileTask().execute(SensorData(event, mFileProximity.path, proximityReading, FloatArray(0)))
        }
    }

    data class SensorData(val event: SensorEvent, val fileName: String, val rd: Float, val rds: FloatArray?)

    private class WriteFileTask : AsyncTask<SensorData, Int, Long>() {

        // Do the long-running work in here
        override fun doInBackground(vararg sensorData: SensorData): Long? {
            val (event, fileName, rd, rds) = sensorData.first()
            val wFile = File(fileName)

            val timeSuffix = "${event.timestamp},${timestamp2millis(event.timestamp)}\n"
            when(event.sensor.type) {
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                Sensor.TYPE_RELATIVE_HUMIDITY,
                Sensor.TYPE_PROXIMITY
                -> wFile.appendText("$rd,$timeSuffix")

                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                Sensor.TYPE_GRAVITY,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
                -> if (rds != null) {
                    wFile.appendText(rds.joinToString() + ",$timeSuffix")
                }

                Sensor.TYPE_STEP_COUNTER ->
                    wFile.appendText("${rd.toInt()},$timeSuffix")
                Sensor.TYPE_STEP_DETECTOR ->
                    wFile.appendText(timeSuffix)
            }

            return 0
        }

        // This is called each time you call publishProgress()
        override fun onProgressUpdate(vararg progress: Int?) {
            //setProgressPercent(progress.firstOrNull() ?: 0)
            // do nothing
        }

        // This is called when doInBackground() is finished
        override fun onPostExecute(result: Long?) {
            //showNotification("Downloaded $result bytes")
            // do nothing
        }
    }


    private fun refreshSensorsUI(eventType: Int) {
        when(eventType) {
            Sensor.TYPE_PRESSURE -> {
                tx = findViewById(R.id.pressureTextView)
                str = "> pressure: $pressureReading mbar "
            }
            Sensor.TYPE_LIGHT -> {
                tx = findViewById(R.id.lightTextView)
                str = "> light: $lightReading lx "
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                tx = findViewById(R.id.ambientTempTextView)
                str = "> ambient Temp: $ambientTempReading °C "
            }
            Sensor.TYPE_RELATIVE_HUMIDITY -> {
                tx = findViewById(R.id.relHumidityTextView)
                str = "> relative humidity: $relHumidityReading % "
            }

            Sensor.TYPE_ACCELEROMETER -> {
                tx = findViewById(R.id.accelerometerTextView)
                str = "> acceleration: (m/s^2)\n\tx: ${accelerometerReading[0]}\n\ty: ${accelerometerReading[1]}\n\tz: ${accelerometerReading[2]} "
            }
            Sensor.TYPE_GRAVITY -> {
                tx = findViewById(R.id.gravityTextView)
                str = "> gravity: (m/s^2)\n\tx: ${gravityReading[0]}\n\ty: ${gravityReading[1]}\n\tz: ${gravityReading[2]} "
            }
            Sensor.TYPE_GYROSCOPE -> {
                tx = findViewById(R.id.gyroscopeTextView)
                str = "> gyroscope: (rad/s)\n\tx: ${gyroscopeReading[0]}\n\ty: ${gyroscopeReading[1]}\n\tz: ${gyroscopeReading[2]} "
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                tx = findViewById(R.id.rotationVectorTextView)
                str = "> rotation vector:\n\tx: ${rotationVectorReading[0]}\n\ty: ${rotationVectorReading[1]}\n\tz: ${rotationVectorReading[2]} "
            }
            Sensor.TYPE_STEP_COUNTER -> {
                tx = findViewById(R.id.stepCounterTextView)
                str = "> step counter: ${stepCounterReading.toInt()} "
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                tx = findViewById(R.id.stepDetectorTextView)
                str = "> step detector: triggered at " + getNow()
            }
        }

        if(tx != null)
            tx!!.text = str
    }

    @SuppressLint("SetTextI18n")
    override fun onSensorChanged(event: SensorEvent) {

        if(isStorageEnabled || isUiRefreshEnabled)
            getSensorsData(event)

        if(isStorageEnabled)
            saveSensorsData(event)

        if(isUiRefreshEnabled)
            refreshSensorsUI(event.sensor.type)
    }



    class timerTask(w: WifiManager, b: BluetoothAdapter?) : TimerTask() {
        public val wifiManager = w
        public val bluetoothAdapter = b
        override fun run() {
            Log.d("timer","tick")
            wifiManager.startScan()
        }
    }

    private fun periodicJob() {
        timer.schedule(timerTask(wifiManager, mBluetoothAdapter),1000,1000)
    }


    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun onResume() {
        super.onResume()

        openBlueToothAsync()
        // openFiles()
        //if(isStorageEnabled)
        //    openFiles()

        // get all sensors
        val deviceSensors: List<Sensor> = sensorManager.getSensorList(Sensor.TYPE_ALL)
        // register every sensor detected
        for(sensor in deviceSensors) {

            // this is a trigger sensor, which is not handled here
            if(sensor.type == Sensor.TYPE_SIGNIFICANT_MOTION) {
                continue
            }

            sensorManager.getDefaultSensor(sensor.type)?.also { defaultSensor ->
                sensorManager.registerListener(this, defaultSensor, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            RegisterGNSSMeasurements()
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        //registerReceiver(bluetoothReceiver, filter)
        //mBluetoothAdapter?.startDiscovery()

        // startScan在高版本中被弃用
        // This method was deprecated in API level 28.
        // The ability for apps to trigger scan requests will be removed in a future release.
        // https://developer.android.google.cn/reference/android/net/wifi/WifiManager#startScan()
        @Suppress("DEPRECATION") val success = wifiManager.startScan()
        if(!success)
            wifiScanFailure()

    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onPause() {
        super.onPause()

        Log.d(TAG_ACTIVITY_STATE, "onPause")

        sensorManager.unregisterListener(this)

        unRegisterMeasurements()
        unregisterReceiver(wifiScanReceiver)
        //unregisterReceiver(bluetoothReceiver)

    }
}