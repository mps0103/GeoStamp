import { GeoStore } from './native';

export type Settings = {
  // Stamp look
  style: 'classic' | 'card';
  position: 'tl' | 'tr' | 'bl' | 'br';
  textSize: 'small' | 'medium' | 'large' | 'xlarge';
  color: string;
  font: 'default' | 'bold' | 'mono' | 'serif' | 'condensed';
  background: boolean;
  // Stamp fields
  showDate: boolean;
  timeFormat24h: boolean;
  dateFormat: 'dmy' | 'mdy' | 'ymd';
  showTimezone: boolean;
  showCoords: boolean;
  coordFormat: 'dms' | 'decimal' | 'ddm';
  showAltitude: boolean;
  showAccuracy: boolean;
  showSpeed: boolean;
  showHeading: boolean;
  showPitch: boolean;
  showCompass: boolean;
  showAddress: boolean;
  addressDetail: 'full' | 'short';
  showIndex: boolean;
  note: string;
  tag: string;
  watermark: string;
  units: 'metric' | 'imperial';
  // Compass
  northRef: 'true' | 'magnetic';
  compassAccuracy: 'high' | 'medium' | 'low';
  // Camera
  quality: 'max' | 'high' | 'medium';
  aspect: '4:3' | '16:9';
  grid: boolean;
  level: boolean;
  shutterSound: boolean;
  saveToGallery: boolean;
  mirrorFront: boolean;
  timer: 0 | 3 | 10;
  // Reports
  reportTitle: string;
  reportAuthor: string;
  pdfLayout: 'single' | 'double';
  saveExportsToDownloads: boolean;
  // Page layout (PDF & Word)
  gridBorder: 'none' | 'thin' | 'thick' | 'double' | 'dashed' | 'dotted';
  gridBorderColor: '#0F172A' | '#64748B' | '#0EA5E9' | '#DC2626';
  gridGap: 'none' | 'small' | 'large';
  gridCaption: 'none' | 'index' | 'date' | 'indexHeading';
  pageSize: 'a4' | 'letter';
  pageOrientation: 'portrait' | 'landscape';
  pageHeader: boolean;
  gridRounded: boolean;
  pageDetails: boolean; // details table (date, coordinates, address…) under each photo in PDF / Word
  onboarded: boolean;
};

export const DEFAULTS: Settings = {
  style: 'classic',
  position: 'tr',
  textSize: 'medium',
  color: '#FFFFFF',
  font: 'default',
  background: false,
  showDate: true,
  timeFormat24h: false,
  dateFormat: 'dmy',
  showTimezone: true,
  showCoords: true,
  coordFormat: 'dms',
  showAltitude: false,
  showAccuracy: false,
  showSpeed: false,
  showHeading: true,
  showPitch: false,
  showCompass: true,
  showAddress: true,
  addressDetail: 'short',
  showIndex: false,
  note: '',
  tag: '',
  watermark: '',
  units: 'metric',
  northRef: 'true',
  compassAccuracy: 'high',
  quality: 'max',
  aspect: '4:3',
  grid: false,
  level: true,
  shutterSound: true,
  saveToGallery: true,
  mirrorFront: false,
  timer: 0,
  reportTitle: 'Geo-Tagged Photo Report',
  reportAuthor: '',
  pdfLayout: 'single',
  saveExportsToDownloads: true,
  gridBorder: 'thin',
  gridBorderColor: '#0F172A',
  gridGap: 'small',
  gridCaption: 'none',
  pageSize: 'a4',
  pageOrientation: 'portrait',
  pageHeader: true,
  gridRounded: false,
  pageDetails: true,
  onboarded: false,
};

const KEY = 'settings.v1';

export async function loadSettings(): Promise<Settings> {
  try {
    const raw = await GeoStore.getItem(KEY);
    if (!raw) return DEFAULTS;
    return { ...DEFAULTS, ...JSON.parse(raw) };
  } catch {
    return DEFAULTS;
  }
}

export function saveSettings(s: Settings) {
  GeoStore.setItem(KEY, JSON.stringify(s)).catch(() => {});
}
