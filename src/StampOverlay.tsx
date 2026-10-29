import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { StampSpec } from './native';
import { CompassRose } from './components';

const FACTOR = { small: 0.026, medium: 0.034, large: 0.044, xlarge: 0.054 } as const;

function fontFamily(font: string) {
  switch (font) {
    case 'mono':
      return 'monospace';
    case 'serif':
      return 'serif';
    case 'condensed':
      return 'sans-serif-condensed';
    default:
      return undefined;
  }
}

/** Live preview replica of the stamp drawn natively on the photo. */
export function StampOverlay({ spec, width, height }: { spec: StampSpec; width: number; height: number }) {
  const unit = Math.min(width, height);
  const ts = unit * FACTOR[spec.textSize];
  const margin = unit * 0.03;
  const bold = spec.font === 'bold';
  const family = fontFamily(spec.font);
  const right = spec.position.endsWith('r');
  const bottom = spec.position.startsWith('b');

  if (spec.style === 'card') {
    const roseSize = unit * 0.15;
    return (
      <View pointerEvents="none" style={StyleSheet.absoluteFill}>
        <View style={[styles.card, { left: margin, right: margin, bottom: margin, padding: ts * 0.8, borderRadius: ts * 0.6 }]}>
          <View style={[styles.accent, { width: ts * 0.28, borderRadius: ts * 0.14 }]} />
          <View style={{ flex: 1, paddingLeft: ts * 0.2 }}>
            {spec.lines.map((l, i) => (
              <Text
                key={i}
                style={{ color: spec.color, fontSize: ts * 0.92, lineHeight: ts * 1.2, fontWeight: bold ? '700' : '400', fontFamily: family }}>
                {l}
              </Text>
            ))}
          </View>
          {spec.compass && spec.heading != null && (
            <View style={{ marginLeft: ts * 0.6, alignSelf: 'center' }}>
              <CompassRose heading={spec.heading} size={roseSize} />
            </View>
          )}
        </View>
        {!!spec.watermark && (
          <Text style={[styles.wm, { right: margin, bottom: margin * 1.6 + ts * 6, fontSize: ts * 0.75 }]}>{spec.watermark}</Text>
        )}
      </View>
    );
  }

  const roseSize = unit * 0.17;
  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      <View
        style={[
          styles.block,
          right ? { right: margin } : { left: margin },
          bottom ? { bottom: margin } : { top: margin },
          { alignItems: right ? 'flex-end' : 'flex-start', maxWidth: width * 0.68 },
          spec.background && { backgroundColor: 'rgba(0,0,0,0.47)', padding: ts * 0.5, borderRadius: ts * 0.4 },
        ]}>
        {spec.lines.map((l, i) => (
          <Text
            key={i}
            style={[
              styles.shadow,
              {
                color: spec.color,
                fontSize: ts,
                lineHeight: ts * 1.28,
                textAlign: right ? 'right' : 'left',
                fontWeight: bold ? '700' : '400',
                fontFamily: family,
              },
            ]}>
            {l}
          </Text>
        ))}
      </View>
      {spec.compass && spec.heading != null && (
        <View
          style={[
            { position: 'absolute' },
            right ? { left: margin + ts * 0.3 } : { right: margin + ts * 0.3 },
            bottom ? { top: margin + ts * 0.3 } : { bottom: margin + ts * 0.3 },
          ]}>
          <CompassRose heading={spec.heading} size={roseSize} />
        </View>
      )}
      {!!spec.watermark && (
        <Text
          style={[
            styles.wm,
            { fontSize: ts * 0.75 },
            right ? { left: margin } : { right: margin },
            bottom ? { top: margin } : { bottom: margin },
          ]}>
          {spec.watermark}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  block: { position: 'absolute' },
  shadow: { textShadowColor: 'rgba(0,0,0,0.85)', textShadowOffset: { width: 1, height: 1 }, textShadowRadius: 3 },
  card: { position: 'absolute', flexDirection: 'row', backgroundColor: 'rgba(0,0,0,0.59)', overflow: 'hidden' },
  accent: { position: 'absolute', left: 0, top: 0, bottom: 0, backgroundColor: '#22C3EE' },
  wm: {
    position: 'absolute',
    color: 'rgba(255,255,255,0.8)',
    fontWeight: '700',
    textShadowColor: 'rgba(0,0,0,0.8)',
    textShadowRadius: 3,
  },
});
