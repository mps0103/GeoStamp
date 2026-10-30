import React, { useEffect, useRef } from 'react';
import { Animated, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { DEFAULTS, Settings } from '../settings';
import { ChoiceRow, FadeIn, IconButton, Section as PlainSection, styles as cs, ToggleRow } from '../components';
import { C, STAMP_COLORS } from '../theme';
import { GeoStore } from '../native';
import { appAlert } from '../dialog';

type Props = { settings: Settings; update: (p: Partial<Settings>) => void; back: () => void; openCalibration: () => void };

const SECTION_ORDER = ['STAMP STYLE', 'WHAT TO SHOW ON PHOTO', 'COMPASS', 'CAMERA', 'PDF & EXPORTS', 'PAGE LAYOUT (PDF & WORD)', 'OTHER'];

const PAGE_LINE_COLORS: Settings['gridBorderColor'][] = ['#0F172A', '#64748B', '#0EA5E9', '#DC2626'];

/** Small page thumbnail with a 2x3 grid of grey boxes that follows the PAGE LAYOUT settings. */
function LayoutPreview({ s }: { s: Settings }) {
  const ratio = s.pageSize === 'letter' ? 11 / 8.5 : 297 / 210;
  const long = 160;
  const landscape = s.pageOrientation === 'landscape';
  const target = {
    w: landscape ? long : long / ratio,
    h: landscape ? long / ratio : long,
    gap: s.gridGap === 'none' ? 0 : s.gridGap === 'small' ? 3 : 6,
    bw: s.gridBorder === 'none' ? 0 : s.gridBorder === 'thick' ? 3 : 1,
    radius: s.gridRounded ? 4 : 0,
    color: s.gridBorderColor as string,
  };
  const key = JSON.stringify(target);
  const from = useRef(target);
  const to = useRef(target);
  const t = useRef(new Animated.Value(1)).current;
  const lastKey = useRef(key);
  if (lastKey.current !== key) {
    // New settings: animate from what was shown to the new look
    lastKey.current = key;
    from.current = to.current;
    to.current = target;
  }
  useEffect(() => {
    t.setValue(0);
    Animated.timing(t, { toValue: 1, duration: 200, useNativeDriver: false }).start();
  }, [key, t]);

  const lerp = <K extends 'w' | 'h' | 'gap' | 'bw' | 'radius'>(k: K) => t.interpolate({ inputRange: [0, 1], outputRange: [from.current[k], to.current[k]] });
  const color = t.interpolate({ inputRange: [0, 1], outputRange: [from.current.color, to.current.color] });
  const gapHalf = Animated.divide(lerp('gap'), 2);
  const borderStyle = s.gridBorder === 'dashed' ? 'dashed' : s.gridBorder === 'dotted' ? 'dotted' : 'solid';

  return (
    <View style={styles.pvWrap}>
      <Animated.View style={[styles.pvPage, { width: lerp('w'), height: lerp('h') }]}>
        {s.pageHeader && <View style={styles.pvHeader} />}
        <View style={{ flex: 1 }}>
          {[0, 1, 2].map(r => (
            <View key={r} style={{ flex: 1, flexDirection: 'row' }}>
              {[0, 1].map(c => (
                <Animated.View
                  key={c}
                  style={[
                    styles.pvCell,
                    { margin: gapHalf, borderWidth: lerp('bw'), borderColor: color, borderRadius: lerp('radius'), borderStyle },
                  ]}>
                  {s.gridBorder === 'double' && (
                    <Animated.View style={[styles.pvInner, { borderColor: color, borderRadius: Animated.multiply(lerp('radius'), 0.5) }]} />
                  )}
                </Animated.View>
              ))}
            </View>
          ))}
        </View>
      </Animated.View>
      <Text style={styles.pvLabel}>Preview</Text>
    </View>
  );
}

/** Section that fades in with a small stagger by its position on the screen. */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <FadeIn delay={Math.max(0, SECTION_ORDER.indexOf(title)) * 50}>
      <PlainSection title={title}>{children}</PlainSection>
    </FadeIn>
  );
}

function Input({ label, value, onChange, placeholder, multiline }: { label: string; value: string; onChange: (v: string) => void; placeholder?: string; multiline?: boolean }) {
  return (
    <View style={cs.rowCol}>
      <Text style={[cs.rowLabel, { marginBottom: 8 }]}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChange}
        placeholder={placeholder}
        placeholderTextColor={C.muted}
        multiline={multiline}
        style={[styles.input, multiline && { minHeight: 70, textAlignVertical: 'top' }]}
      />
    </View>
  );
}

export function SettingsScreen({ settings: s, update, back, openCalibration }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <IconButton small label="‹ Back" onPress={back} />
        <Text style={styles.title}>Settings</Text>
        <View style={{ width: 60 }} />
      </View>
      <ScrollView contentContainerStyle={{ padding: 14, paddingBottom: insets.bottom + 40 }} keyboardShouldPersistTaps="handled">
        <Section title="STAMP STYLE">
          <ChoiceRow label="Layout" value={s.style} onChange={v => update({ style: v })} options={[{ value: 'classic', label: 'Classic text' }, { value: 'card', label: 'Info card' }]} />
          {s.style === 'classic' && (
            <ChoiceRow
              label="Position"
              value={s.position}
              onChange={v => update({ position: v })}
              options={[
                { value: 'tl', label: 'Top Left' },
                { value: 'tr', label: 'Top Right' },
                { value: 'bl', label: 'Bottom Left' },
                { value: 'br', label: 'Bottom Right' },
              ]}
            />
          )}
          <ChoiceRow
            label="Text size"
            value={s.textSize}
            onChange={v => update({ textSize: v })}
            options={[
              { value: 'small', label: 'Small' },
              { value: 'medium', label: 'Medium' },
              { value: 'large', label: 'Large' },
              { value: 'xlarge', label: 'X-Large' },
            ]}
          />
          <ChoiceRow
            label="Font"
            value={s.font}
            onChange={v => update({ font: v })}
            options={[
              { value: 'default', label: 'Default' },
              { value: 'bold', label: 'Bold' },
              { value: 'condensed', label: 'Condensed' },
              { value: 'mono', label: 'Mono' },
              { value: 'serif', label: 'Serif' },
            ]}
          />
          <View style={cs.rowCol}>
            <Text style={[cs.rowLabel, { marginBottom: 8 }]}>Font color</Text>
            <View style={cs.chips}>
              {STAMP_COLORS.map(c => (
                <Pressable key={c} onPress={() => update({ color: c })} style={[styles.swatch, { backgroundColor: c }, s.color === c && styles.swatchOn]} />
              ))}
            </View>
          </View>
          {s.style === 'classic' && <ToggleRow label="Background box" hint="Dark box behind text for readability" value={s.background} onChange={v => update({ background: v })} />}
        </Section>

        <Section title="WHAT TO SHOW ON PHOTO">
          <ToggleRow label="Date & time" value={s.showDate} onChange={v => update({ showDate: v })} />
          {s.showDate && (
            <>
              <ChoiceRow label="Date format" value={s.dateFormat} onChange={v => update({ dateFormat: v })} options={[{ value: 'dmy', label: '28 Oct 2026' }, { value: 'mdy', label: 'Oct 28, 2026' }, { value: 'ymd', label: '2026-10-28' }]} />
              <ToggleRow label="24-hour time" value={s.timeFormat24h} onChange={v => update({ timeFormat24h: v })} />
              <ToggleRow label="Show time zone" value={s.showTimezone} onChange={v => update({ showTimezone: v })} />
            </>
          )}
          <ToggleRow label="Latitude / Longitude" value={s.showCoords} onChange={v => update({ showCoords: v })} />
          {s.showCoords && (
            <ChoiceRow
              label="Coordinate format"
              value={s.coordFormat}
              onChange={v => update({ coordFormat: v })}
              options={[
                { value: 'dms', label: `28°40'34"N` },
                { value: 'decimal', label: '28.676270' },
                { value: 'ddm', label: `28°40.57'N` },
              ]}
            />
          )}
          <ToggleRow label="Direction (heading)" hint="e.g. 108° ESE" value={s.showHeading} onChange={v => update({ showHeading: v })} />
          <ToggleRow label="Compass rose" value={s.showCompass} onChange={v => update({ showCompass: v })} />
          <ToggleRow label="Camera tilt" value={s.showPitch} onChange={v => update({ showPitch: v })} />
          <ToggleRow label="Address" value={s.showAddress} onChange={v => update({ showAddress: v })} />
          {s.showAddress && <ChoiceRow label="Address detail" value={s.addressDetail} onChange={v => update({ addressDetail: v })} options={[{ value: 'short', label: 'Area, City, State' }, { value: 'full', label: 'Full address' }]} />}
          <ToggleRow label="Altitude" value={s.showAltitude} onChange={v => update({ showAltitude: v })} />
          <ToggleRow label="GPS accuracy" value={s.showAccuracy} onChange={v => update({ showAccuracy: v })} />
          <ToggleRow label="Speed" value={s.showSpeed} onChange={v => update({ showSpeed: v })} />
          <ToggleRow label="Photo number" hint="#0001, #0002…" value={s.showIndex} onChange={v => update({ showIndex: v })} />
          <Input label="Project / Tag" value={s.tag} onChange={v => update({ tag: v })} placeholder="e.g. Site Survey – Block A" />
          <Input label="Custom note (multi-line)" value={s.note} onChange={v => update({ note: v })} placeholder="Any text, emoji supported" multiline />
          <Input label="Watermark text" value={s.watermark} onChange={v => update({ watermark: v })} placeholder="e.g. your company name" />
          <ChoiceRow label="Units" value={s.units} onChange={v => update({ units: v })} options={[{ value: 'metric', label: 'Metric (m, km/h)' }, { value: 'imperial', label: 'Imperial (ft, mph)' }]} />
        </Section>

        <Section title="COMPASS">
          <ChoiceRow label="North reference" value={s.northRef} onChange={v => update({ northRef: v })} options={[{ value: 'true', label: 'True North (GPS corrected)' }, { value: 'magnetic', label: 'Magnetic North' }]} />
          <ChoiceRow
            label="Compass accuracy"
            value={s.compassAccuracy}
            onChange={v => update({ compassAccuracy: v })}
            options={[
              { value: 'high', label: 'High (best, more battery)' },
              { value: 'medium', label: 'Medium' },
              { value: 'low', label: 'Low (saves battery)' },
            ]}
          />
          <Pressable style={cs.row} onPress={openCalibration}>
            <Text style={[cs.rowLabel, { flex: 1 }]}>Calibrate compass</Text>
            <Text style={{ color: C.accent, fontWeight: '700' }}>Open ›</Text>
          </Pressable>
        </Section>

        <Section title="CAMERA">
          <ChoiceRow label="Photo quality" value={s.quality} onChange={v => update({ quality: v })} options={[{ value: 'max', label: 'Maximum' }, { value: 'high', label: 'High (~8 MP)' }, { value: 'medium', label: 'Small (~3 MP)' }]} />
          <ChoiceRow label="Aspect ratio" value={s.aspect} onChange={v => update({ aspect: v })} options={[{ value: '4:3', label: '4:3' }, { value: '16:9', label: '16:9' }]} />
          <ChoiceRow label="Self-timer" value={s.timer} onChange={v => update({ timer: v })} options={[{ value: 0, label: 'Off' }, { value: 3, label: '3 s' }, { value: 10, label: '10 s' }]} />
          <ToggleRow label="Grid lines" value={s.grid} onChange={v => update({ grid: v })} />
          <ToggleRow label="Level indicator" value={s.level} onChange={v => update({ level: v })} />
          <ToggleRow label="Shutter sound" value={s.shutterSound} onChange={v => update({ shutterSound: v })} />
          <ToggleRow label="Save copy to phone Gallery" hint="Pictures/GeoStamp" value={s.saveToGallery} onChange={v => update({ saveToGallery: v })} />
          <ToggleRow label="Mirror front camera" value={s.mirrorFront} onChange={v => update({ mirrorFront: v })} />
        </Section>

        <Section title="PDF & EXPORTS">
          <Input label="Report title" value={s.reportTitle} onChange={v => update({ reportTitle: v })} />
          <Input label="Prepared by" value={s.reportAuthor} onChange={v => update({ reportAuthor: v })} placeholder="Your name / company" />
          <ToggleRow
            label="Include details with each photo"
            hint="Table of date, coordinates, direction, address… under the photo (PDF & Word, 1 or 2 per page). Turn off if the stamp already shows what you need."
            value={s.pageDetails}
            onChange={v => update({ pageDetails: v })}
          />
          <ChoiceRow label="Multiple pages layout" value={s.pdfLayout} onChange={v => update({ pdfLayout: v })} options={[{ value: 'single', label: '1 photo per page' }, { value: 'double', label: '2 photos per page' }]} />
        </Section>

        <Section title="PAGE LAYOUT (PDF & WORD)">
          <LayoutPreview s={s} />
          <ChoiceRow
            label="Lines"
            value={s.gridBorder}
            onChange={v => update({ gridBorder: v })}
            options={[
              { value: 'none', label: 'None' },
              { value: 'thin', label: 'Thin' },
              { value: 'thick', label: 'Thick' },
              { value: 'double', label: 'Double' },
              { value: 'dashed', label: 'Dashed' },
              { value: 'dotted', label: 'Dotted' },
            ]}
          />
          <View style={cs.rowCol}>
            <Text style={[cs.rowLabel, { marginBottom: 8 }]}>Line colour</Text>
            <View style={cs.chips}>
              {PAGE_LINE_COLORS.map(c => (
                <Pressable key={c} onPress={() => update({ gridBorderColor: c })} style={[styles.swatch, { backgroundColor: c }, s.gridBorderColor === c && styles.swatchOn]} />
              ))}
            </View>
          </View>
          <ChoiceRow
            label="Spacing"
            value={s.gridGap}
            onChange={v => update({ gridGap: v })}
            options={[
              { value: 'none', label: 'None' },
              { value: 'small', label: 'Small' },
              { value: 'large', label: 'Large' },
            ]}
          />
          <ChoiceRow
            label="Caption under photo"
            value={s.gridCaption}
            onChange={v => update({ gridCaption: v })}
            options={[
              { value: 'none', label: 'None' },
              { value: 'index', label: 'Photo no.' },
              { value: 'date', label: 'Date & time' },
              { value: 'indexHeading', label: 'No. + direction' },
            ]}
          />
          <ChoiceRow
            label="Page size"
            value={s.pageSize}
            onChange={v => update({ pageSize: v })}
            options={[
              { value: 'a4', label: 'A4' },
              { value: 'letter', label: 'Letter' },
            ]}
          />
          <ChoiceRow
            label="Orientation"
            value={s.pageOrientation}
            onChange={v => update({ pageOrientation: v })}
            options={[
              { value: 'portrait', label: 'Portrait' },
              { value: 'landscape', label: 'Landscape' },
            ]}
          />
          <ToggleRow label="Show header (title, prepared by, date)" value={s.pageHeader} onChange={v => update({ pageHeader: v })} />
          <ToggleRow label="Rounded corners (PDF only)" value={s.gridRounded} onChange={v => update({ gridRounded: v })} />
        </Section>

        <Section title="OTHER">
          <Pressable
            style={cs.row}
            onPress={() =>
              appAlert('Reset photo number', 'Next photo will be #0001.', [
                { text: 'Reset', style: 'primary', onPress: () => GeoStore.resetIndex() },
                { text: 'Cancel', style: 'cancel' },
              ])
            }>
            <Text style={[cs.rowLabel, { flex: 1 }]}>Reset photo number</Text>
          </Pressable>
          <Pressable
            style={cs.row}
            onPress={() =>
              appAlert('Restore defaults', 'Reset all settings?', [
                { text: 'Reset', style: 'destructive', onPress: () => update({ ...DEFAULTS, onboarded: true }) },
                { text: 'Cancel', style: 'cancel' },
              ])
            }>
            <Text style={[cs.rowLabel, { flex: 1, color: C.danger }]}>Restore default settings</Text>
          </Pressable>
        </Section>
        <Text style={styles.footer}>GeoStamp Camera · v1.2 · Works offline (address needs internet once)</Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 12, height: 56 },
  title: { color: C.text, fontSize: 18, fontWeight: '800' },
  input: { backgroundColor: C.surface2, color: C.text, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15, borderWidth: 1, borderColor: C.border },
  swatch: { width: 34, height: 34, borderRadius: 17, borderWidth: 2, borderColor: C.border },
  swatchOn: { borderColor: C.accent, borderWidth: 4 },
  footer: { color: C.muted, textAlign: 'center', fontSize: 12, marginTop: 6 },
  pvWrap: { alignItems: 'center', paddingVertical: 14, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.border },
  pvPage: { backgroundColor: '#fff', padding: 8, borderRadius: 2 },
  pvHeader: { height: 5, width: '55%', backgroundColor: '#94A3B8', marginBottom: 5, borderRadius: 1 },
  pvCell: { flex: 1, backgroundColor: '#CBD5E1' },
  pvInner: { position: 'absolute', left: 2, top: 2, right: 2, bottom: 2, borderWidth: 1 },
  pvLabel: { color: C.muted, fontSize: 11, marginTop: 6 },
});
