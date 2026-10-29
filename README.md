# GeoStamp Camera (com.mps.geostamp)

GPS timestamp camera — React Native 0.82 + Kotlin (CameraX). Fully offline except address lookup.

## Run (Windows PowerShell)
npm install; npx react-native run-android

## Release AAB
Add to android/gradle.properties:
GEOSTAMP_UPLOAD_STORE_FILE=your-upload.keystore
GEOSTAMP_UPLOAD_STORE_PASSWORD=****
GEOSTAMP_UPLOAD_KEY_ALIAS=upload
GEOSTAMP_UPLOAD_KEY_PASSWORD=****
Then: cd android; .\gradlew bundleRelease

## Features
- Tilt-compensated compass (gyro-fused rotation vector), correct when phone is upright (camera direction) or flat (top edge), portrait or landscape
- True / Magnetic north (auto declination from GPS), calibration screen with interference detection
- Stamp: date/time + time zone, lat/long (DMS / decimal / DDM), heading, compass rose, address, altitude, accuracy, speed, tilt, tag, multi-line note, photo number, watermark
- Classic text (4 corners, colours, fonts, sizes, background box) or Info-card layout; live WYSIWYG preview
- GPS + EXIF tags (lat/long, altitude, GPSImgDirection) written into every JPEG
- Flash / torch / timer / grid / level / pinch & tap-to-focus / zoom / front camera / 4:3 & 16:9 / quality
- Saves to Pictures/GeoStamp, in-app gallery with multi-select
- Export PDF report (1 or 2 photos per page), CSV, KML (Google Earth); share images or files; exports also saved to Downloads/GeoStamp

## Ads (AdMob)
- Banner: bottom of My Photos (gallery) and Photo details screens — never on the camera
- Interstitial: after a PDF / CSV / KML export, before the share sheet (max once per 90 s)
- Consent form (EEA/UK) shown automatically via Google UMP
- Test ads by default. For release: put real App ID in app.json → react-native-google-mobile-ads.android_app_id, and real unit IDs in src/ads.tsx (PROD_BANNER_ID, PROD_INTERSTITIAL_ID)
