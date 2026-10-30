import type { DetailRow, HeadingEvent, LocationEvent, PhotoMeta, StampSpec } from './native';
import type { Settings } from './settings';
import { addressLines, coordsText, dateText, distanceText, headingText, speedText } from './format';

export type SensorState = {
  heading: HeadingEvent | null;
  location: LocationEvent | null;
  tzShort: string;
};

export function currentHeading(h: HeadingEvent | null, s: Settings, front = false): number | null {
  if (!h) return null;
  const base = s.northRef === 'true' ? h.true : h.magnetic;
  // Selfie camera looks opposite to the back camera (the phone's heading is the back camera's)
  if (front && !h.flat) return (base + 180) % 360;
  return base;
}

export function buildStamp(s: Settings, sensors: SensorState, index: number, now: number, front = false) {
  const { location: loc, heading: h, tzShort } = sensors;
  const lines: string[] = [];
  const details: DetailRow[] = [];
  const hdg = currentHeading(h, s, front);
  const hdgDecimals = s.compassAccuracy === 'high' ? 1 : 0;
  const tilt = h ? (front && !h.flat ? -h.pitch : h.pitch) : null;
  const date = dateText(now, s, tzShort);
  const addr = addressLines(loc?.address, s.addressDetail);
  const coords = loc ? coordsText(loc.latitude, loc.longitude, s.coordFormat) : 'Waiting for GPS…';

  if (s.showDate) lines.push(date);
  if (s.showCoords) lines.push(coords);
  if (s.showHeading && hdg != null) {
    let t = headingText(hdg, hdgDecimals);
    if (s.showPitch && tilt != null) t += `  ·  Tilt ${Math.round(tilt)}°`;
    lines.push(t);
  }
  const extra: string[] = [];
  if (s.showAltitude && loc?.hasAltitude) extra.push(`Alt ${distanceText(loc.altitude, s.units)}`);
  if (s.showAccuracy && loc) extra.push(`±${distanceText(loc.accuracy, s.units)}`);
  if (s.showSpeed && loc && loc.speed >= 0) extra.push(speedText(loc.speed, s.units));
  if (extra.length) lines.push(extra.join('  ·  '));
  if (s.showAddress) lines.push(...addr);
  if (s.tag.trim()) lines.push(s.tag.trim());
  if (s.note.trim()) lines.push(...s.note.trim().split('\n'));
  if (s.showIndex) lines.push(`#${String(index).padStart(4, '0')}`);

  // Details (for viewer + PDF)
  details.push({ label: 'Date & Time', value: date });
  if (loc) {
    details.push({ label: 'Coordinates', value: coordsText(loc.latitude, loc.longitude, s.coordFormat) });
    details.push({ label: 'Decimal', value: `${loc.latitude.toFixed(6)}, ${loc.longitude.toFixed(6)}` });
    if (loc.hasAltitude) details.push({ label: 'Altitude', value: distanceText(loc.altitude, s.units) });
    details.push({ label: 'GPS Accuracy', value: `±${distanceText(loc.accuracy, s.units)}` });
  }
  if (hdg != null) {
    details.push({ label: 'Direction', value: `${headingText(hdg, hdgDecimals)} (${s.northRef === 'true' ? 'True' : 'Magnetic'} North)` });
    if (tilt != null) details.push({ label: 'Camera Tilt', value: `${Math.round(tilt)}°` });
  }
  details.push({ label: 'Camera', value: front ? 'Front (selfie)' : 'Back' });
  const fullAddr = loc?.address?.full || addr.join(', ');
  if (fullAddr) details.push({ label: 'Address', value: fullAddr });
  if (s.tag.trim()) details.push({ label: 'Tag / Project', value: s.tag.trim() });
  if (s.note.trim()) details.push({ label: 'Note', value: s.note.trim() });
  details.push({ label: 'Photo No.', value: `#${String(index).padStart(4, '0')}` });

  const spec: StampSpec = {
    title: '',
    lines,
    position: s.position,
    style: s.style,
    textSize: s.textSize,
    color: s.color,
    font: s.font,
    background: s.background,
    compass: s.showCompass && hdg != null,
    heading: hdg,
    watermark: s.watermark.trim(),
  };

  const meta: PhotoMeta = {
    latitude: loc?.latitude ?? null,
    longitude: loc?.longitude ?? null,
    altitude: loc?.hasAltitude ? loc.altitude : null,
    accuracy: loc?.accuracy ?? null,
    heading: hdg,
    headingRef: s.northRef,
    pitch: tilt,
    address: fullAddr,
    note: s.note.trim(),
    tag: s.tag.trim(),
    index,
    dateText: date,
    details,
  };

  return { spec, meta };
}
