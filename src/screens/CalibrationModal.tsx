import React, { useEffect, useRef } from 'react';
import { Animated, Easing, Modal, StyleSheet, Text, View } from 'react-native';
import type { HeadingEvent } from '../native';
import { Badge, Button, CompassRose } from '../components';
import { C } from '../theme';

const ACC = ['Unreliable', 'Low', 'Medium', 'High'];
const ACC_COLOR = [C.danger, C.warn, '#EAB308', C.ok];

export function CalibrationModal({ visible, heading, close }: { visible: boolean; heading: HeadingEvent | null; close: () => void }) {
  const t = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!visible) return;
    const loop = Animated.loop(Animated.timing(t, { toValue: 1, duration: 2400, easing: Easing.linear, useNativeDriver: true }));
    loop.start();
    return () => loop.stop();
  }, [visible, t]);

  // Figure-8 path (lemniscate) approximation
  const steps = 16;
  const inputRange = Array.from({ length: steps + 1 }, (_, i) => i / steps);
  const tx = inputRange.map(v => 70 * Math.sin(v * 2 * Math.PI));
  const ty = inputRange.map(v => 30 * Math.sin(v * 4 * Math.PI));
  const acc = heading ? Math.max(0, Math.min(3, heading.accuracy)) : 0;

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={close}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <Text style={styles.title}>Calibrate compass</Text>
          <Text style={styles.sub}>Move your phone in a figure-8 a few times, rotating it along all axes. Keep away from metal, magnets, laptops and vehicles.</Text>
          <View style={styles.anim}>
            <Animated.View
              style={{
                transform: [{ translateX: t.interpolate({ inputRange, outputRange: tx }) }, { translateY: t.interpolate({ inputRange, outputRange: ty }) }],
              }}>
              <View style={styles.phone} />
            </Animated.View>
          </View>
          <View style={styles.statusRow}>
            <CompassRose heading={heading ? heading.true : 0} size={84} />
            <View style={{ flex: 1, marginLeft: 16, gap: 8 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <Text style={styles.label}>Accuracy</Text>
                <Badge text={heading ? ACC[acc] : 'No sensor'} color={ACC_COLOR[acc]} />
              </View>
              <Text style={styles.label}>Magnetic field: {heading ? `${Math.round(heading.field)} µT` : '—'}</Text>
              {heading?.interference ? (
                <Text style={[styles.label, { color: C.warn }]}>Magnetic interference detected — move away from metal, magnets, chargers and electronics, then do a figure-8.</Text>
              ) : (
                <Text style={[styles.label, { color: C.sub }]}>
                  {heading && heading.expectedField > 0 ? `Expected here: ${Math.round(heading.expectedField)} µT` : 'Normal range is 25–65 µT'}
                </Text>
              )}
              {heading && (
                <Text style={[styles.label, { color: C.sub }]}>
                  Declination {heading.declination >= 0 ? '+' : ''}
                  {heading.declination.toFixed(1)}° · {heading.fused ? 'Gyro-fused' : 'Basic sensor'}
                </Text>
              )}
            </View>
          </View>
          <Button title={acc >= 2 ? 'Done' : 'Close'} onPress={close} style={{ marginTop: 18 }} />
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  sheet: { backgroundColor: C.surface, borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 22, paddingBottom: 30 },
  title: { color: C.text, fontSize: 20, fontWeight: '800' },
  sub: { color: C.sub, marginTop: 6, lineHeight: 19 },
  anim: { height: 110, alignItems: 'center', justifyContent: 'center' },
  phone: { width: 28, height: 50, borderRadius: 6, borderWidth: 3, borderColor: C.accent },
  statusRow: { flexDirection: 'row', alignItems: 'center' },
  label: { color: C.text, fontSize: 13 },
});
