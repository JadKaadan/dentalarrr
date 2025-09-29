<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.dentalapp.artraining">
    <!-- ARCore requires camera permission -->
    <uses-permission android:name="android.permission.CAMERA" /> <!-- ARCore requires OpenGL ES 3.0 -->
    <uses-feature
        android:glEsVersion="0x00030000"
        android:required="true" /> <!-- ARCore requires rear-facing camera -->
    <uses-feature
        android:name="android.hardware.camera"
        android:required="true" /> <!-- ARCore requires depth sensor (optional for basic functionality) -->
    <uses-feature
        android:name="android.hardware.camera.ar"
        android:required="false" /> <!-- Internet permission for API calls and model downloads -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /> <!-- Storage permissions for caching models and exporting reports -->
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" /> <!-- Vibration for haptic feedback -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.DentalAR"
        tools:targetApi="31">
        <activity
            android:name=".AdvancedARCameraActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.DentalAR.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!-- ARCore metadata -->
        <meta-data
            android:name="com.google.ar.core"
            android:value="required" /> <!-- Main AR Activity -->

        <activity
            android:name=".QRScannerActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.DentalAR.NoActionBar" /> <!-- Project Selection Activity -->
        <!-- File Provider for sharing reports -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>

</manifest>
