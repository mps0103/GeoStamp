import type { Address } from './native';
import type { Settings } from './settings';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DIRS16 = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

const pad = (n: number, l = 2) => String(n).padStart(l, '0');

export function cardinal(deg: number): string {
  const i = Math.round((((deg % 360) + 360) % 360) / 22.5) % 16;
  return DIRS16[i];
}

export function headingText(deg: number): string {
  const d = Math.round(((deg % 360) + 360) % 360) % 360;
  return `${d}° ${cardinal(d)}`;
}

function dms(value: number, pos: string, neg: string): string {
  const hemi = value >= 0 ? pos : neg;
  const v = Math.abs(value);
  let d = Math.floor(v);
  let m = Math.floor((v - d) * 60);
  let s = (v - d - m / 60) * 3600;
  if (s >= 59.9995) {
    s = 0;
    m += 1;
  }
  if (m >= 60) {
    m = 0;
    d += 1;
  }
  return `${d}°${m}'${s.toFixed(3)}"${hemi}`;
}

function ddm(value: number, pos: string, neg: string): string {
  const hemi = value >= 0 ? pos : neg;
  const v = Math.abs(value);
  const d = Math.floor(v);
  const m = (v - d) * 60;
  return `${d}°${m.toFixed(4)}'${hemi}`;
}

export function coordsText(lat: number, lon: number, fmt: Settings['coordFormat']): string {
  if (fmt === 'decimal') return `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
  if (fmt === 'ddm') return `${ddm(lat, 'N', 'S')} ${ddm(lon, 'E', 'W')}`;
  return `${dms(lat, 'N', 'S')} ${dms(lon, 'E', 'W')}`;
}

export function dateText(t: number, s: Settings, tzShort: string): string {
  const d = new Date(t);
  const day = d.getDate();
  const mon = MONTHS[d.getMonth()];
  const y = d.getFullYear();
  let date: string;
  if (s.dateFormat === 'mdy') date = `${mon} ${day}, ${y}`;
  else if (s.dateFormat === 'ymd') date = `${y}-${pad(d.getMonth() + 1)}-${pad(day)}`;
  else date = `${day} ${mon} ${y}`;
  let time: string;
  if (s.timeFormat24h) {
    time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  } else {
    const h = d.getHours() % 12 || 12;
    time = `${h}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${d.getHours() < 12 ? 'am' : 'pm'}`;
  }
  return `${date} ${time}${s.showTimezone && tzShort ? ' ' + tzShort : ''}`;
}

export function distanceText(m: number, units: Settings['units']): string {
  if (units === 'imperial') return `${Math.round(m * 3.28084)} ft`;
  return `${m >= 100 ? Math.round(m) : m.toFixed(1)} m`;
}

export function speedText(mps: number, units: Settings['units']): string {
  if (units === 'imperial') return `${(mps * 2.23694).toFixed(1)} mph`;
  return `${(mps * 3.6).toFixed(1)} km/h`;
}

export function addressLines(a: Address | undefined, detail: Settings['addressDetail']): string[] {
  if (!a) return [];
  if (detail === 'full') {
    return a.full ? [a.full] : [];
  }
  const out: string[] = [];
  const first = a.subLocality || a.thoroughfare || a.featureName;
  if (first) out.push(first);
  const second = a.subAdminArea || a.locality;
  if (second && second !== first) out.push(second);
  const third = a.adminArea;
  if (third && third !== second) out.push(third + (a.postalCode ? ` ${a.postalCode}` : ''));
  if (out.length === 0 && a.full) out.push(a.full);
  return out;
}

export function shortDate(t: number): string {
  const d = new Date(t);
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}
