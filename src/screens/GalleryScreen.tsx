import React, { useMemo, useState } from 'react';
import { Alert, FlatList, Image, Pressable, StyleSheet, Text, ToastAndroid, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ExportResult, GeoStore, PhotoRecord } from '../native';
import type { Settings } from '../settings';
import { Button, IconButton } from '../components';
import { C } from '../theme';
import { shortDate } from '../format';
import { AdBanner, showInterstitial } from '../ads';

type Props = {
  photos: PhotoRecord[];
  settings: Settings;
  loading: boolean;
  reload: () => void;
  back: () => void;
  open: (p: PhotoRecord) => void;
};

export async function shareExport(r: ExportResult, mime: string, label: string) {
  if (r.savedTo) ToastAndroid.show(`Saved to ${r.savedTo}`, ToastAndroid.LONG);
  await showInterstitial();
  await GeoStore.shareFiles([r.path], mime, label);
}

export async function runExport(kind: 'pdf' | 'csv' | 'kml' | 'share', ids: string[], photos: PhotoRecord[], s: Settings) {
  try {
    if (kind === 'share') {
      const paths = photos.filter(p => ids.includes(p.id)).map(p => p.path);
      await GeoStore.shareFiles(paths, 'image/jpeg', 'Share photos');
    } else if (kind === 'pdf') {
      ToastAndroid.show('Creating PDF…', ToastAndroid.SHORT);
      const r = await GeoStore.exportPdf(ids, {
        title: s.reportTitle,
        author: s.reportAuthor,
        layout: s.pdfLayout,
        saveToDownloads: s.saveExportsToDownloads,
      });
      await shareExport(r, 'application/pdf', 'Share PDF report');
    } else if (kind === 'csv') {
      const r = await GeoStore.exportCsv(ids, s.saveExportsToDownloads);
      await shareExport(r, 'text/csv', 'Share CSV');
    } else {
      const r = await GeoStore.exportKml(ids, s.saveExportsToDownloads);
      await shareExport(r, 'application/vnd.google-earth.kml+xml', 'Share KML');
    }
  } catch (e: any) {
    ToastAndroid.show(e?.message || 'Export failed', ToastAndroid.LONG);
  }
}

export function GalleryScreen({ photos, settings, loading, reload, back, open }: Props) {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [selected, setSelected] = useState<string[]>([]);
  const selecting = selected.length > 0;
  const cols = 3;
  const size = (width - 4 * (cols + 1)) / cols;

  const toggle = (id: string) => setSelected(s => (s.includes(id) ? s.filter(x => x !== id) : [...s, id]));

  const sections = useMemo(() => photos, [photos]);

  const remove = () => {
    Alert.alert('Delete photos', `Delete ${selected.length} photo(s)?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'App only',
        onPress: async () => {
          await GeoStore.remove(selected, false);
          setSelected([]);
          reload();
        },
      },
      {
        text: 'Also from Gallery',
        style: 'destructive',
        onPress: async () => {
          await GeoStore.remove(selected, true);
          setSelected([]);
          reload();
        },
      },
    ]);
  };

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <IconButton small label="‹ Back" onPress={selecting ? () => setSelected([]) : back} />
        <Text style={styles.title}>{selecting ? `${selected.length} selected` : 'My Photos'}</Text>
        {photos.length > 0 ? (
          <IconButton
            small
            label={selecting && selected.length === photos.length ? 'None' : 'All'}
            onPress={() => setSelected(selecting && selected.length === photos.length ? [] : photos.map(p => p.id))}
          />
        ) : (
          <View style={{ width: 50 }} />
        )}
      </View>

      {photos.length === 0 && !loading ? (
        <View style={styles.empty}>
          <Text style={styles.emptyTitle}>No photos yet</Text>
          <Text style={styles.emptySub}>Photos you capture with GPS stamps will appear here.</Text>
          <Button title="Open camera" onPress={back} style={{ marginTop: 18 }} />
        </View>
      ) : (
        <FlatList
          data={sections}
          keyExtractor={p => p.id}
          numColumns={cols}
          refreshing={loading}
          onRefresh={reload}
          style={{ flex: 1 }}
          contentContainerStyle={{ padding: 2, paddingBottom: 12 }}
          renderItem={({ item }) => {
            const sel = selected.includes(item.id);
            return (
              <Pressable
                onPress={() => (selecting ? toggle(item.id) : open(item))}
                onLongPress={() => toggle(item.id)}
                style={{ width: size, height: size, margin: 2 }}>
                <Image source={{ uri: 'file://' + item.thumb }} style={styles.img} resizeMethod="resize" />
                <View style={styles.cap}>
                  <Text style={styles.capText} numberOfLines={1}>
                    {item.meta.tag || shortDate(item.createdAt)}
                  </Text>
                </View>
                {selecting && <View style={[styles.check, sel && { backgroundColor: C.accent, borderColor: C.accent }]} />}
              </Pressable>
            );
          }}
        />
      )}

      {selecting && (
        <View style={styles.actions}>
          <Button title="Share" variant="ghost" style={styles.act} onPress={() => runExport('share', selected, photos, settings)} />
          <Button title="PDF" style={styles.act} onPress={() => runExport('pdf', selected, photos, settings)} />
          <Button title="CSV" variant="ghost" style={styles.act} onPress={() => runExport('csv', selected, photos, settings)} />
          <Button title="KML" variant="ghost" style={styles.act} onPress={() => runExport('kml', selected, photos, settings)} />
          <Button title="Delete" variant="danger" style={styles.act} onPress={remove} />
        </View>
      )}
      {!selecting && photos.length > 0 && (
        <Text style={styles.hint}>Long-press to select · export PDF, CSV, KML</Text>
      )}
      <View style={{ paddingBottom: insets.bottom }}>
        <AdBanner />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 12, height: 56 },
  title: { color: C.text, fontSize: 18, fontWeight: '800' },
  img: { width: '100%', height: '100%', borderRadius: 6, backgroundColor: C.surface },
  cap: { position: 'absolute', left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.45)', paddingHorizontal: 6, paddingVertical: 3, borderBottomLeftRadius: 6, borderBottomRightRadius: 6 },
  capText: { color: '#fff', fontSize: 10, fontWeight: '600' },
  check: { position: 'absolute', top: 6, right: 6, width: 22, height: 22, borderRadius: 11, borderWidth: 2, borderColor: '#fff', backgroundColor: 'rgba(0,0,0,0.3)' },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 },
  emptyTitle: { color: C.text, fontSize: 20, fontWeight: '800' },
  emptySub: { color: C.sub, textAlign: 'center', marginTop: 8 },
  actions: { flexDirection: 'row', gap: 6, paddingHorizontal: 8, paddingVertical: 10, backgroundColor: C.surface, borderTopWidth: 1, borderTopColor: C.border },
  act: { flex: 1, paddingHorizontal: 4, paddingVertical: 11 },
  hint: { alignSelf: 'center', color: C.muted, fontSize: 12, paddingVertical: 8 },
});
