import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Animated, FlatList, Image, Pressable, StyleSheet, Text, ToastAndroid, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ExportResult, GeoStore, PageLayout, PhotoRecord } from '../native';
import type { Settings } from '../settings';
import { Button, IconButton } from '../components';
import { C } from '../theme';
import { shortDate } from '../format';
import { AdBanner, showInterstitial } from '../ads';
import { appAlert } from '../dialog';

type Props = {
  photos: PhotoRecord[];
  settings: Settings;
  loading: boolean;
  reload: () => void;
  back: () => void;
  open: (p: PhotoRecord) => void;
};

const MIME = {
  pdf: 'application/pdf',
  csv: 'text/csv',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
};

type ExportLayout = 'single' | 'double' | 'grid';

/** Settings › PAGE LAYOUT as the object the native PDF / Word exporters expect. */
export function pageLayoutFrom(s: Settings): PageLayout {
  return {
    border: s.gridBorder,
    borderColor: s.gridBorderColor,
    gap: s.gridGap,
    caption: s.gridCaption,
    pageSize: s.pageSize,
    orientation: s.pageOrientation,
    header: s.pageHeader,
    rounded: s.gridRounded,
    details: s.pageDetails,
  };
}

/** With 2+ photos, ask whether to export multiple pages or one single-page grid. null = cancelled. */
function askLayout(kind: 'PDF' | 'Word', n: number, s: Settings): Promise<ExportLayout | null> {
  if (n < 2) return Promise.resolve(s.pdfLayout);
  return new Promise(resolve => {
    appAlert(
      `${kind} layout`,
      `${n} photos selected`,
      [
        { text: 'Single page', style: 'primary', onPress: () => resolve('grid') },
        { text: 'Multiple pages', style: 'default', onPress: () => resolve(s.pdfLayout) },
        { text: 'Cancel', style: 'cancel', onPress: () => resolve(null) },
      ],
      { cancelable: true, onDismiss: () => resolve(null) },
    );
  });
}

/** Interstitial first, then a popup so the user can Share / Open / dismiss the saved file. */
async function finishExport(r: ExportResult, mime: string) {
  await showInterstitial();
  appAlert('Saved', `Saved to ${r.savedTo ?? 'app storage'}`, [
    {
      text: 'Open',
      style: 'primary',
      onPress: () => GeoStore.openFile(r.path, mime).catch(() => ToastAndroid.show('No app found to open this file', ToastAndroid.SHORT)),
    },
    { text: 'Share', style: 'default', onPress: () => GeoStore.shareFiles([r.path], mime, 'Share').catch(() => {}) },
    { text: 'Done', style: 'cancel' },
  ]);
}

export async function runExport(kind: 'pdf' | 'csv' | 'docx' | 'share', ids: string[], photos: PhotoRecord[], s: Settings) {
  try {
    if (kind === 'share') {
      const paths = photos.filter(p => ids.includes(p.id)).map(p => p.path);
      await GeoStore.shareFiles(paths, 'image/jpeg', 'Share photos');
    } else if (kind === 'pdf') {
      const layout = await askLayout('PDF', ids.length, s);
      if (!layout) return;
      ToastAndroid.show('Creating PDF…', ToastAndroid.SHORT);
      const r = await GeoStore.exportPdf(ids, {
        title: s.reportTitle,
        author: s.reportAuthor,
        layout,
        saveToDownloads: true,
        page: pageLayoutFrom(s),
      });
      await finishExport(r, MIME.pdf);
    } else if (kind === 'csv') {
      const r = await GeoStore.exportCsv(ids, true);
      await finishExport(r, MIME.csv);
    } else {
      const layout = await askLayout('Word', ids.length, s);
      if (!layout) return;
      ToastAndroid.show('Creating Word file…', ToastAndroid.SHORT);
      const r = await GeoStore.exportDocx(ids, { title: s.reportTitle, author: s.reportAuthor, layout, page: pageLayoutFrom(s) });
      await finishExport(r, MIME.docx);
    }
  } catch (e: any) {
    ToastAndroid.show(e?.message || 'Export failed', ToastAndroid.LONG);
  }
}

/** Thumbnail that fades/scales in once, staggered by position; never replays when scrolled back. */
function Thumb({ index, seen, id, children, style, onPress, onLongPress }: {
  index: number;
  seen: React.MutableRefObject<Set<string>>;
  id: string;
  children: React.ReactNode;
  style: any;
  onPress: () => void;
  onLongPress: () => void;
}) {
  const animate = useRef(!seen.current.has(id)).current;
  const v = useRef(new Animated.Value(animate ? 0 : 1)).current;
  useEffect(() => {
    seen.current.add(id);
    if (!animate) return;
    Animated.timing(v, { toValue: 1, duration: 220, delay: Math.min(index * 30, 300), useNativeDriver: true }).start();
  }, [animate, id, index, seen, v]);
  return (
    <Animated.View style={[style, { opacity: v, transform: [{ scale: v.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1] }) }] }]}>
      <Pressable onPress={onPress} onLongPress={onLongPress} style={StyleSheet.absoluteFill}>
        {children}
      </Pressable>
    </Animated.View>
  );
}

/** Selection tick: small spring bounce whenever it is toggled. */
function Check({ selected }: { selected: boolean }) {
  const scale = useRef(new Animated.Value(1)).current;
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    scale.setValue(selected ? 0.6 : 1.2);
    Animated.spring(scale, { toValue: 1, speed: 30, bounciness: 14, useNativeDriver: true }).start();
  }, [selected, scale]);
  return <Animated.View style={[styles.check, selected && { backgroundColor: C.accent, borderColor: C.accent }, { transform: [{ scale }] }]} />;
}

/** Bottom action bar slides up when selection starts. */
function SlideUp({ children }: { children: React.ReactNode }) {
  const y = useRef(new Animated.Value(60)).current;
  useEffect(() => {
    Animated.timing(y, { toValue: 0, duration: 200, useNativeDriver: true }).start();
  }, [y]);
  return <Animated.View style={{ transform: [{ translateY: y }] }}>{children}</Animated.View>;
}

export function GalleryScreen({ photos, settings, loading, reload, back, open }: Props) {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [selected, setSelected] = useState<string[]>([]);
  const seenThumbs = useRef(new Set<string>());
  const [lastAction, setLastAction] = useState<'share' | 'pdf' | 'csv' | 'docx'>('pdf');
  const selecting = selected.length > 0;
  const cols = 3;
  const size = (width - 4 * (cols + 1)) / cols;

  const toggle = (id: string) => setSelected(s => (s.includes(id) ? s.filter(x => x !== id) : [...s, id]));

  const sections = useMemo(() => photos, [photos]);

  const remove = () => {
    appAlert('Delete photos', `Delete ${selected.length} photo(s)?`, [
      {
        text: 'App only',
        style: 'default',
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
      { text: 'Cancel', style: 'cancel' },
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
          renderItem={({ item, index }) => {
            const sel = selected.includes(item.id);
            return (
              <Thumb
                id={item.id}
                index={index}
                seen={seenThumbs}
                style={{ width: size, height: size, margin: 2 }}
                onPress={() => (selecting ? toggle(item.id) : open(item))}
                onLongPress={() => toggle(item.id)}>
                <Image source={{ uri: 'file://' + item.thumb }} style={styles.img} resizeMethod="resize" />
                <View style={styles.cap}>
                  <Text style={styles.capText} numberOfLines={1}>
                    {item.meta.tag || shortDate(item.createdAt)}
                  </Text>
                </View>
                {selecting && <Check selected={sel} />}
              </Thumb>
            );
          }}
        />
      )}

      {selecting && (
        <SlideUp>
          <View style={styles.actions}>
            {(
              [
                ['share', 'Share'],
                ['pdf', 'PDF'],
                ['csv', 'CSV'],
                ['docx', 'Word'],
              ] as const
            ).map(([kind, title]) => (
              <Button
                key={kind}
                title={title}
                // the highlighted button is the one used last
                variant={lastAction === kind ? 'primary' : 'ghost'}
                style={styles.act}
                onPress={() => {
                  setLastAction(kind);
                  runExport(kind, selected, photos, settings);
                }}
              />
            ))}
            <Button title="Delete" variant="danger" style={styles.act} onPress={remove} />
          </View>
        </SlideUp>
      )}
      {!selecting && photos.length > 0 && (
        <Text style={styles.hint}>Long-press to select · export PDF, CSV, Word</Text>
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
