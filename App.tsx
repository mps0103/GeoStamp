import React, { useCallback, useEffect, useState } from 'react';
import { AppState, BackHandler, PermissionsAndroid, Platform, StatusBar, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GeoLocation, GeoStore, PhotoRecord } from './src/native';
import { DEFAULTS, loadSettings, saveSettings, Settings } from './src/settings';
import { useSensors } from './src/useSensors';
import { CameraScreen } from './src/screens/CameraScreen';
import { GalleryScreen } from './src/screens/GalleryScreen';
import { ViewerScreen } from './src/screens/ViewerScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { CalibrationModal } from './src/screens/CalibrationModal';
import { Button } from './src/components';
import { C } from './src/theme';
import { initAds } from './src/ads';

type Screen = { name: 'camera' | 'gallery' | 'settings' } | { name: 'viewer'; photo: PhotoRecord; from: 'camera' | 'gallery' };
type Perm = 'checking' | 'granted' | 'denied';

async function requestPermissions(): Promise<{ camera: boolean; location: boolean }> {
  const P = PermissionsAndroid.PERMISSIONS;
  const list = [P.CAMERA, P.ACCESS_FINE_LOCATION, P.ACCESS_COARSE_LOCATION];
  if (Platform.OS === 'android' && Number(Platform.Version) < 29) list.push(P.WRITE_EXTERNAL_STORAGE);
  const res = await PermissionsAndroid.requestMultiple(list);
  const ok = (k: string) => res[k as keyof typeof res] === PermissionsAndroid.RESULTS.GRANTED;
  return { camera: ok(P.CAMERA), location: ok(P.ACCESS_FINE_LOCATION) || ok(P.ACCESS_COARSE_LOCATION) };
}

export default function App() {
  const [settings, setSettings] = useState<Settings>(DEFAULTS);
  const [ready, setReady] = useState(false);
  const [perm, setPerm] = useState<Perm>('checking');
  const [screen, setScreen] = useState<Screen>({ name: 'camera' });
  const [photos, setPhotos] = useState<PhotoRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [calib, setCalib] = useState(false);
  const [tzShort, setTzShort] = useState('');
  const [appActive, setAppActive] = useState(true);

  const cameraVisible = screen.name === 'camera' && perm === 'granted' && appActive;
  const sensors = useSensors(perm === 'granted' && (screen.name === 'camera' || calib) && appActive);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setPhotos(await GeoStore.list());
    } catch {}
    setLoading(false);
  }, []);

  const askPerms = useCallback(async () => {
    const r = await requestPermissions();
    setPerm(r.camera ? 'granted' : 'denied');
    if (r.location) {
      const st = await GeoLocation.isEnabled().catch(() => null);
      if (st && !st.enabled) GeoLocation.openLocationSettings();
    }
  }, []);

  useEffect(() => {
    (async () => {
      const s = await loadSettings();
      setSettings(s);
      const info = await GeoStore.getDeviceInfo().catch(() => null);
      if (info) setTzShort(info.tzShort);
      setReady(true);
      reload();
      askPerms().finally(() => initAds());
    })();
  }, [reload, askPerms]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', st => setAppActive(st === 'active'));
    return () => sub.remove();
  }, []);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (calib) {
        setCalib(false);
        return true;
      }
      if (screen.name === 'viewer') {
        setScreen({ name: screen.from });
        return true;
      }
      if (screen.name !== 'camera') {
        setScreen({ name: 'camera' });
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [screen, calib]);

  const update = useCallback((p: Partial<Settings>) => {
    setSettings(prev => {
      const next = { ...prev, ...p };
      saveSettings(next);
      return next;
    });
  }, []);

  if (!ready || perm === 'checking') {
    return <View style={{ flex: 1, backgroundColor: C.bg }} />;
  }

  let body: React.ReactNode;
  if (perm === 'denied') {
    body = (
      <View style={styles.permRoot}>
        <Text style={styles.permTitle}>Camera & location needed</Text>
        <Text style={styles.permSub}>GeoStamp Camera stamps each photo with GPS location, address, date and compass direction. Please allow camera and location access.</Text>
        <Button title="Allow access" onPress={askPerms} style={{ marginTop: 22, alignSelf: 'stretch' }} />
        <Button title="Open app settings" variant="ghost" onPress={() => GeoStore.openAppSettings()} style={{ marginTop: 10, alignSelf: 'stretch' }} />
      </View>
    );
  } else if (screen.name === 'gallery') {
    body = <GalleryScreen photos={photos} settings={settings} loading={loading} reload={reload} back={() => setScreen({ name: 'camera' })} open={p => setScreen({ name: 'viewer', photo: p, from: 'gallery' })} />;
  } else if (screen.name === 'viewer') {
    const from = screen.from;
    body = (
      <ViewerScreen
        photo={screen.photo}
        photos={photos}
        settings={settings}
        back={() => setScreen({ name: from })}
        deleted={() => {
          reload();
          setScreen({ name: from });
        }}
      />
    );
  } else if (screen.name === 'settings') {
    body = <SettingsScreen settings={settings} update={update} back={() => setScreen({ name: 'camera' })} openCalibration={() => setCalib(true)} />;
  } else {
    body = null;
  }

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />
      <View style={{ flex: 1, backgroundColor: C.bg }}>
        {/* Camera stays mounted (hidden) to keep it warm; it is released when not visible */}
        {perm === 'granted' && (
          <View style={[StyleSheet.absoluteFill, screen.name !== 'camera' && styles.hidden]} pointerEvents={screen.name === 'camera' ? 'auto' : 'none'}>
            <CameraScreen
              settings={settings}
              update={update}
              heading={sensors.heading}
              location={sensors.location}
              latest={sensors.latest}
              tzShort={tzShort}
              active={cameraVisible}
              lastPhoto={photos[0] || null}
              onCaptured={p => setPhotos(prev => [p, ...prev])}
              openGallery={() => {
                reload();
                setScreen({ name: 'gallery' });
              }}
              openSettings={() => setScreen({ name: 'settings' })}
              openCalibration={() => setCalib(true)}
              openViewer={p => setScreen({ name: 'viewer', photo: p, from: 'camera' })}
            />
          </View>
        )}
        {body && <View style={StyleSheet.absoluteFill}>{body}</View>}
      </View>
      <CalibrationModal visible={calib} heading={sensors.heading} close={() => setCalib(false)} />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  hidden: { opacity: 0 },
  permRoot: { flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center', padding: 28 },
  permTitle: { color: C.text, fontSize: 22, fontWeight: '800', textAlign: 'center' },
  permSub: { color: C.sub, textAlign: 'center', marginTop: 10, lineHeight: 20 },
});
