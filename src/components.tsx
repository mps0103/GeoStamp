import React, { useEffect, useRef } from 'react';
import { Animated, Pressable, StyleSheet, Switch, Text, TextStyle, View, ViewStyle } from 'react-native';
import { C } from './theme';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

/** Spring the pressed element to 0.95 and back (native driver, ~150-250 ms). */
function usePressScale() {
  const scale = useRef(new Animated.Value(1)).current;
  const to = (v: number) => Animated.spring(scale, { toValue: v, speed: 40, bounciness: 6, useNativeDriver: true }).start();
  return { scale, onPressIn: () => to(0.95), onPressOut: () => to(1) };
}

/** Fades in and slides up once on mount. */
export function FadeIn({
  children,
  delay = 0,
  dy = 10,
  duration = 220,
  style,
}: {
  children: React.ReactNode;
  delay?: number;
  dy?: number;
  duration?: number;
  style?: ViewStyle;
}) {
  const v = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(v, { toValue: 1, duration, delay, useNativeDriver: true }).start();
  }, [v, duration, delay]);
  return (
    <Animated.View style={[style, { opacity: v, transform: [{ translateY: v.interpolate({ inputRange: [0, 1], outputRange: [dy, 0] }) }] }]}>
      {children}
    </Animated.View>
  );
}

export function CompassRose({ heading, size = 96, showDegrees = false }: { heading: number; size?: number; showDegrees?: boolean }) {
  const r = size / 2;
  const labels: [string, number][] = [
    ['N', 0],
    ['E', 90],
    ['S', 180],
    ['W', 270],
  ];
  const ticks = Array.from({ length: 24 }, (_, i) => i * 15);
  return (
    <View style={{ width: size, height: size }}>
      <View
        style={[
          StyleSheet.absoluteFill,
          { borderRadius: r, borderWidth: size * 0.08, borderColor: 'rgba(40,40,40,0.85)', backgroundColor: 'rgba(0,0,0,0.3)' },
        ]}
      />
      <View style={[StyleSheet.absoluteFill, { transform: [{ rotate: `${-heading}deg` }] }]}>
        {ticks.map(t => (
          <View key={t} style={[StyleSheet.absoluteFill, { alignItems: 'center', transform: [{ rotate: `${t}deg` }] }]}>
            <View
              style={{
                marginTop: size * 0.13,
                width: t % 90 === 0 ? 2 : 1,
                height: t % 30 === 0 ? size * 0.07 : size * 0.04,
                backgroundColor: 'rgba(255,255,255,0.75)',
              }}
            />
          </View>
        ))}
        {labels.map(([l, d]) => (
          <View key={l} style={[StyleSheet.absoluteFill, { alignItems: 'center', transform: [{ rotate: `${d}deg` }] }]}>
            <Text style={{ color: l === 'N' ? C.north : '#fff', fontWeight: '800', fontSize: size * 0.14, marginTop: -size * 0.005 }}>
              {l}
            </Text>
          </View>
        ))}
      </View>
      {/* Fixed needle = direction the camera faces */}
      <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}>
        <View
          style={{
            width: 0,
            height: 0,
            borderLeftWidth: size * 0.07,
            borderRightWidth: size * 0.07,
            borderBottomWidth: size * 0.5,
            borderLeftColor: 'transparent',
            borderRightColor: 'transparent',
            borderBottomColor: C.accent,
            marginTop: -size * 0.12,
          }}
        />
        <View style={{ position: 'absolute', width: size * 0.1, height: size * 0.1, borderRadius: size, backgroundColor: '#fff' }} />
      </View>
      {showDegrees && (
        <Text style={{ position: 'absolute', bottom: -18, alignSelf: 'center', color: '#fff', fontWeight: '700', fontSize: 12 }}>
          {Math.round(heading)}°
        </Text>
      )}
    </View>
  );
}

export function IconButton({
  label,
  onPress,
  active,
  style,
  small,
}: {
  label: string;
  onPress: () => void;
  active?: boolean;
  style?: ViewStyle;
  small?: boolean;
}) {
  const { scale, onPressIn, onPressOut } = usePressScale();
  return (
    <AnimatedPressable
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      hitSlop={8}
      style={[
        styles.iconBtn,
        small && { paddingHorizontal: 10, paddingVertical: 6 },
        active && { backgroundColor: C.accent },
        style,
        { transform: [{ scale }] },
      ]}>
      <Text style={[styles.iconBtnText, active && { color: '#04202A' }, small && { fontSize: 12 }]}>{label}</Text>
    </AnimatedPressable>
  );
}

export function Button({
  title,
  onPress,
  variant = 'primary',
  style,
  disabled,
}: {
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'ghost' | 'danger';
  style?: ViewStyle;
  disabled?: boolean;
}) {
  const bg = variant === 'primary' ? C.accent : variant === 'danger' ? C.danger : C.surface2;
  const fg = variant === 'primary' ? '#04202A' : '#fff';
  const { scale, onPressIn, onPressOut } = usePressScale();
  return (
    <AnimatedPressable
      disabled={disabled}
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      style={[styles.btn, { backgroundColor: bg, opacity: disabled ? 0.4 : 1 }, style, { transform: [{ scale }] }]}>
      <Text style={[styles.btnText, { color: fg }]}>{title}</Text>
    </AnimatedPressable>
  );
}

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={{ marginBottom: 18 }}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

export function ToggleRow({ label, hint, value, onChange }: { label: string; hint?: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <Pressable onPress={() => onChange(!value)} style={styles.row}>
      <View style={{ flex: 1, paddingRight: 12 }}>
        <Text style={styles.rowLabel}>{label}</Text>
        {hint ? <Text style={styles.rowHint}>{hint}</Text> : null}
      </View>
      <Switch value={value} onValueChange={onChange} trackColor={{ true: C.accentDark, false: C.border }} thumbColor={value ? C.accent : '#cbd5e1'} />
    </Pressable>
  );
}

export function ChoiceRow<T extends string | number>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <View style={styles.rowCol}>
      <Text style={[styles.rowLabel, { marginBottom: 8 }]}>{label}</Text>
      <View style={styles.chips}>
        {options.map(o => (
          <Pressable
            key={String(o.value)}
            onPress={() => onChange(o.value)}
            style={[styles.chip, o.value === value && { backgroundColor: C.accent, borderColor: C.accent }]}>
            <Text style={[styles.chipText, o.value === value && { color: '#04202A' }]}>{o.label}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

export function Badge({ text, color, style }: { text: string; color: string; style?: TextStyle }) {
  return <Text style={[styles.badge, { backgroundColor: color }, style]}>{text}</Text>;
}

export const styles = StyleSheet.create({
  iconBtn: {
    backgroundColor: 'rgba(15,23,42,0.72)',
    borderRadius: 999,
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  iconBtnText: { color: '#fff', fontWeight: '700', fontSize: 13 },
  btn: { borderRadius: 14, paddingVertical: 13, paddingHorizontal: 18, alignItems: 'center', justifyContent: 'center' },
  btnText: { fontWeight: '800', fontSize: 15 },
  sectionTitle: { color: C.accent, fontWeight: '800', fontSize: 12, letterSpacing: 1, marginBottom: 8, marginLeft: 4 },
  card: { backgroundColor: C.surface, borderRadius: 16, borderWidth: 1, borderColor: C.border, overflow: 'hidden' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: C.border,
  },
  rowCol: { paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.border },
  rowLabel: { color: C.text, fontSize: 15, fontWeight: '600' },
  rowHint: { color: C.sub, fontSize: 12, marginTop: 2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 999, borderWidth: 1, borderColor: C.border, backgroundColor: C.surface2 },
  chipText: { color: C.text, fontWeight: '600', fontSize: 13 },
  badge: { color: '#fff', fontSize: 11, fontWeight: '800', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 999, overflow: 'hidden' },
});
