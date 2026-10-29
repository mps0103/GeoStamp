import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  DeviceEventEmitter,
  Image,
  Pressable,
  StyleSheet,
  Text,
  ToastAndroid,
  useWindowDimensions,
  Vibration,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GeoCamera, GeoCameraView, GeoLocation, GeoStore, HeadingEvent, LocationEvent, PhotoRecord } from '../native';
import type { Settings } from '../settings';
import { buildStamp, currentHeading } from '../stamp';
import { StampOverlay } from '../StampOverlay';
import { Badge, IconButton } from '../components';
import { C } from '../theme';
import { cardinal, distanceText } from '../format';

type Props = {
  settings: Settings;
  update: (p: Partial<Settings>) => void;
  heading: HeadingEvent | null;
  location: LocationEvent | null;
  latest: React.MutableRefObject<{ heading: HeadingEvent | null; location: LocationEvent | null }>;
  tzShort: string;
  active: boolean;
  lastPhoto: PhotoRecord | null;
  onCaptured: (p: PhotoRecord) => void;
  openGallery: () => void;
  openSettings: () => void;
  openCalibration: () => void;
  openViewer: (p: PhotoRecord) => void;
};

export function CameraScreen(p: Props) {
  const { settings: s, heading, location } = p;
  const insets = useSafeAreaInsets();
  const { width: winW, height: winH } = useWindowDimensions();
  const [lens, setLens] = useState<'back' | 'front'>('back');
  const [flash, setFlash] = useState<'off' | 'on' | 'auto'>('off');
  const [torch, setTorch] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [maxZoom, setMaxZoom] = useState(1);
  const [busy, setBusy] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [nextIdx, setNextIdx] = useState(1);
  const [now, setNow] = useState(Date.now());
  const [cameraError, setCameraError] = useState<string | null>(null);
  const flashAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    GeoStore.peekIndex().then(setNextIdx).catch(() => {});
    const a = DeviceEventEmitter.addListener('GeoCameraReady', e => {
      setCameraError(null);
      setMaxZoom(e.maxZoom || 1);
    });
    const b = DeviceEventEmitter.addListener('GeoCameraError', e => setCameraError(e.message));
    const c = DeviceEventEmitter.addListener('GeoZoom', e => setZoom(Math.round(e.zoom * 10) / 10));
    return () => {
      a.remove();
      b.remove();
      c.remove();
    };
  }, []);

  // Preview box matches the photo's aspect ratio so the overlay is WYSIWYG
  const ratio = s.aspect === '16:9' ? 16 / 9 : 4 / 3;
  const maxH = winH - insets.top - insets.bottom - 56 - 96;
  let pw = winW;
  let ph = pw * ratio;
  if (ph > maxH) {
    ph = maxH;
    pw = ph / ratio;
  }

  const spec = useMemo(
    () => buildStamp(s, { heading, location, tzShort: p.tzShort }, nextIdx, now).spec,
    [s, heading, location, p.tzShort, nextIdx, now],
  );

  const hdg = currentHeading(heading, s);
  const compassBad = heading ? heading.accuracy < 2 || heading.interference : false;
  const gpsAcc = location?.accuracy;
  const gpsColor = gpsAcc == null ? C.danger : gpsAcc <= 10 ? C.ok : gpsAcc <= 30 ? C.warn : C.danger;

  const capture = useCallback(async () => {
    if (busy || countdown > 0) return;
    if (s.timer > 0) {
      for (let i = s.timer; i > 0; i--) {
        setCountdown(i);
        await new Promise<void>(r => setTimeout(r, 1000));
      }
      setCountdown(0);
    }
    setBusy(true);
    try {
      const index = await GeoStore.nextIndex();
      const { heading: lh, location: ll } = p.latest.current;
      const { spec: sp, meta } = buildStamp(s, { heading: lh, location: ll, tzShort: p.tzShort }, index, Date.now());
      Animated.sequence([
        Animated.timing(flashAnim, { toValue: 1, duration: 60, useNativeDriver: true }),
        Animated.timing(flashAnim, { toValue: 0, duration: 220, useNativeDriver: true }),
      ]).start();
      Vibration.vibrate(15);
      const rec = await GeoCamera.takePhoto({
        stamp: sp,
        meta,
        quality: s.quality,
        jpegQuality: 92,
        saveToGallery: s.saveToGallery,
        shutterSound: s.shutterSound,
        mirrorFront: s.mirrorFront,
      });
      setNextIdx(index + 1);
      p.onCaptured(rec);
      if (!ll) ToastAndroid.show('Saved without GPS — location not fixed yet', ToastAndroid.SHORT);
    } catch (e: any) {
      ToastAndroid.show(e?.message || 'Capture failed', ToastAndroid.LONG);
    } finally {
      setBusy(false);
    }
  }, [busy, countdown, s, p, flashAnim]);

  const cycleFlash = () => setFlash(f => (f === 'off' ? 'auto' : f === 'auto' ? 'on' : 'off'));
  const cycleTimer = () => p.update({ timer: s.timer === 0 ? 3 : s.timer === 3 ? 10 : 0 });
  const zoomSteps = [1, 2, 5].filter(z => z <= Math.max(1, maxZoom));

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      {/* Top bar */}
      <View style={styles.topBar}>
        <IconButton small label={flash === 'off' ? 'Flash Off' : flash === 'auto' ? 'Flash Auto' : 'Flash On'} onPress={cycleFlash} active={flash !== 'off'} />
        <IconButton small label="Torch" onPress={() => setTorch(t => !t)} active={torch} />
        <IconButton small label={s.timer ? `${s.timer}s` : 'Timer'} onPress={cycleTimer} active={s.timer > 0} />
        <IconButton small label={s.aspect} onPress={() => p.update({ aspect: s.aspect === '4:3' ? '16:9' : '4:3' })} />
        <IconButton small label="⚙" onPress={p.openSettings} />
      </View>

      {/* Preview */}
      <View style={{ width: pw, height: ph, alignSelf: 'center', backgroundColor: '#000' }}>
        <GeoCameraView
          style={StyleSheet.absoluteFill}
          lens={lens}
          flash={flash}
          torch={torch}
          aspect={s.aspect}
          zoom={zoom}
          active={p.active}
        />
        {s.grid && (
          <View pointerEvents="none" style={StyleSheet.absoluteFill}>
            {[1, 2].map(i => (
              <View key={'v' + i} style={[styles.gridV, { left: (pw / 3) * i }]} />
            ))}
            {[1, 2].map(i => (
              <View key={'h' + i} style={[styles.gridH, { top: (ph / 3) * i }]} />
            ))}
          </View>
        )}
        {s.level && heading && !heading.flat && (
          <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.center]}>
            <View
              style={[
                styles.levelLine,
                {
                  width: pw * 0.35,
                  transform: [{ rotate: `${-heading.roll}deg` }],
                  backgroundColor: Math.abs(heading.roll) < 1 ? C.ok : 'rgba(255,255,255,0.8)',
                },
              ]}
            />
          </View>
        )}
        <StampOverlay spec={spec} width={pw} height={ph} />
        <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: '#fff', opacity: flashAnim }]} />
        {countdown > 0 && (
          <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.center]}>
            <Text style={styles.countdown}>{countdown}</Text>
          </View>
        )}
        {cameraError && (
          <View style={[StyleSheet.absoluteFill, styles.center, { backgroundColor: 'rgba(0,0,0,0.7)', padding: 24 }]}>
            <Text style={{ color: '#fff', textAlign: 'center' }}>{cameraError}</Text>
          </View>
        )}
      </View>

      {/* Bottom panel (overlays preview on tall aspect ratios) */}
      <View style={[styles.bottomPanel, { paddingBottom: insets.bottom + 10 }]} pointerEvents="box-none">
        <View style={styles.statusRow}>
          <Pressable onPress={() => (location ? GeoLocation.refreshAddress() : GeoLocation.openLocationSettings())}>
            <Badge text={gpsAcc == null ? 'GPS searching…' : `GPS ±${distanceText(gpsAcc, s.units)}`} color={gpsColor} />
          </Pressable>
          <Text style={styles.readoutBig}>{hdg == null ? '—' : `${Math.round(hdg) % 360}° ${cardinal(hdg)}`}</Text>
          <Pressable onPress={p.openCalibration}>
            <Badge
              text={!heading ? 'No compass' : compassBad ? 'Calibrate' : heading.flat ? 'Flat mode' : 'Camera mode'}
              color={!heading || compassBad ? C.warn : 'rgba(34,197,94,0.85)'}
            />
          </Pressable>
        </View>
        <Text style={styles.readoutSmall}>
          {s.northRef === 'true' ? 'True North' : 'Magnetic North'}
          {heading ? `  ·  Tilt ${Math.round(heading.pitch)}°` : ''}
          {heading && heading.accuracyDeg > 0 ? `  ·  ±${Math.round(heading.accuracyDeg)}°` : ''}
        </Text>

        {zoomSteps.length > 1 && (
          <View style={styles.zoomRow}>
            {zoomSteps.map(z => (
              <IconButton key={z} small label={`${z}x`} active={Math.abs(zoom - z) < 0.05} onPress={() => setZoom(z)} />
            ))}
            {!zoomSteps.some(z => Math.abs(zoom - z) < 0.05) && (
              <IconButton small label={`${zoom.toFixed(1)}x`} active onPress={() => setZoom(1)} />
            )}
          </View>
        )}

        <View style={styles.controls}>
          <Pressable style={styles.thumbWrap} onPress={() => (p.lastPhoto ? p.openViewer(p.lastPhoto) : p.openGallery())}>
            {p.lastPhoto ? (
              <Image source={{ uri: 'file://' + p.lastPhoto.thumb }} style={styles.thumb} />
            ) : (
              <Text style={{ color: C.sub, fontSize: 11, fontWeight: '700' }}>Gallery</Text>
            )}
          </Pressable>
          <Pressable onPress={capture} style={({ pressed }) => [styles.shutterOuter, pressed && { transform: [{ scale: 0.94 }] }]}>
            <View style={styles.shutterInner}>{busy && <ActivityIndicator color={C.bg} />}</View>
          </Pressable>
          <View style={{ alignItems: 'center', gap: 6 }}>
            <Pressable style={styles.flipBtn} onPress={() => setLens(l => (l === 'back' ? 'front' : 'back'))}>
              <Text style={{ color: '#fff', fontSize: 22 }}>⟲</Text>
            </Pressable>
          </View>
        </View>
        <Pressable onPress={p.openGallery} style={{ alignSelf: 'center', paddingTop: 6 }}>
          <Text style={{ color: C.sub, fontWeight: '700', fontSize: 12 }}>All photos ›</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  topBar: { height: 56, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 10 },
  center: { alignItems: 'center', justifyContent: 'center' },
  gridV: { position: 'absolute', top: 0, bottom: 0, width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.5)' },
  gridH: { position: 'absolute', left: 0, right: 0, height: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.5)' },
  levelLine: { height: 2, borderRadius: 1 },
  countdown: { color: '#fff', fontSize: 96, fontWeight: '900', textShadowColor: '#000', textShadowRadius: 8 },
  bottomPanel: { position: 'absolute', left: 0, right: 0, bottom: 0, paddingTop: 10, backgroundColor: 'rgba(11,18,32,0.55)' },
  statusRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 12 },
  readoutBig: { color: '#fff', fontSize: 26, fontWeight: '900', letterSpacing: 0.5 },
  readoutSmall: { color: C.sub, fontSize: 12, marginTop: 2, textAlign: 'center' },
  zoomRow: { flexDirection: 'row', justifyContent: 'center', gap: 8, marginTop: 8 },
  controls: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-around', paddingTop: 12 },
  thumbWrap: {
    width: 56,
    height: 56,
    borderRadius: 14,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.6)',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: C.surface,
  },
  thumb: { width: '100%', height: '100%' },
  shutterOuter: { width: 80, height: 80, borderRadius: 40, borderWidth: 5, borderColor: '#fff', alignItems: 'center', justifyContent: 'center' },
  shutterInner: { width: 62, height: 62, borderRadius: 31, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center' },
  flipBtn: { width: 56, height: 56, borderRadius: 28, backgroundColor: C.surface2, alignItems: 'center', justifyContent: 'center' },
});
