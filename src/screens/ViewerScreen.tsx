import React from 'react';
import { Alert, Image, Linking, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GeoStore, PhotoRecord } from '../native';
import type { Settings } from '../settings';
import { Button, IconButton } from '../components';
import { C } from '../theme';
import { runExport } from './GalleryScreen';
import { AdBanner } from '../ads';

type Props = { photo: PhotoRecord; photos: PhotoRecord[]; settings: Settings; back: () => void; deleted: () => void };

export function ViewerScreen({ photo, photos, settings, back, deleted }: Props) {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const h = (width * photo.height) / Math.max(1, photo.width);
  const m = photo.meta;
  const hasGps = m.latitude != null && m.longitude != null;

  const openMap = () => {
    if (!hasGps) return;
    const q = `${m.latitude},${m.longitude}`;
    Linking.openURL(`geo:${q}?q=${q}`).catch(() => Linking.openURL(`https://www.google.com/maps/search/?api=1&query=${q}`));
  };

  const del = () =>
    Alert.alert('Delete photo', 'Delete this photo?', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'App only', onPress: async () => { await GeoStore.remove([photo.id], false); deleted(); } },
      { text: 'Also from Gallery', style: 'destructive', onPress: async () => { await GeoStore.remove([photo.id], true); deleted(); } },
    ]);

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <IconButton small label="‹ Back" onPress={back} />
        <Text style={styles.title}>{m.index ? `Photo #${String(m.index).padStart(4, '0')}` : 'Photo'}</Text>
        <IconButton small label="Open" onPress={() => GeoStore.openFile(photo.path, 'image/jpeg').catch(() => {})} />
      </View>
      <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: 12 }}>
        <Image source={{ uri: 'file://' + photo.path }} style={{ width, height: h, backgroundColor: '#000' }} resizeMode="contain" />
        <View style={styles.card}>
          {(m.details || []).map((d, i) => (
            <View key={i} style={styles.row}>
              <Text style={styles.label}>{d.label}</Text>
              <Text style={styles.value} selectable>
                {d.value}
              </Text>
            </View>
          ))}
          <View style={styles.row}>
            <Text style={styles.label}>Resolution</Text>
            <Text style={styles.value}>
              {photo.width} × {photo.height}
            </Text>
          </View>
        </View>
      </ScrollView>
      <View style={styles.actions}>
        <Button title="Share" style={styles.act} onPress={() => runExport('share', [photo.id], photos.length ? photos : [photo], settings)} />
        <Button title="PDF" variant="ghost" style={styles.act} onPress={() => runExport('pdf', [photo.id], [photo], settings)} />
        <Button title="Map" variant="ghost" style={styles.act} onPress={openMap} disabled={!hasGps} />
        <Button title="Delete" variant="danger" style={styles.act} onPress={del} />
      </View>
      <View style={{ paddingBottom: insets.bottom }}>
        <AdBanner />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 12, height: 56 },
  title: { color: C.text, fontSize: 17, fontWeight: '800' },
  card: { margin: 14, backgroundColor: C.surface, borderRadius: 16, borderWidth: 1, borderColor: C.border, paddingVertical: 4 },
  row: { flexDirection: 'row', paddingHorizontal: 14, paddingVertical: 9, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.border },
  label: { color: C.sub, width: 110, fontSize: 13, fontWeight: '600' },
  value: { color: C.text, flex: 1, fontSize: 13 },
  actions: { flexDirection: 'row', gap: 8, paddingHorizontal: 10, paddingVertical: 10, backgroundColor: C.surface, borderTopWidth: 1, borderTopColor: C.border },
  act: { flex: 1, paddingHorizontal: 4 },
});
