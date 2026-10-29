import { NativeModules, requireNativeComponent, ViewProps } from 'react-native';

export type HeadingEvent = {
  magnetic: number;
  true: number;
  declination: number;
  pitch: number;
  roll: number;
  flat: boolean;
  accuracy: number; // 0 unreliable .. 3 high
  accuracyDeg: number; // -1 if unknown
  field: number; // µT
  expectedField: number; // µT at this location, 0 if unknown
  interference: boolean;
  fused: boolean;
};

export type Address = {
  full?: string;
  featureName?: string;
  thoroughfare?: string;
  subLocality?: string;
  locality?: string;
  subAdminArea?: string;
  adminArea?: string;
  postalCode?: string;
  countryName?: string;
  countryCode?: string;
};

export type LocationEvent = {
  latitude: number;
  longitude: number;
  accuracy: number;
  hasAltitude: boolean;
  altitude: number;
  speed: number;
  time: number;
  provider: string;
  address?: Address;
};

export type DetailRow = { label: string; value: string };

export type PhotoMeta = {
  latitude?: number | null;
  longitude?: number | null;
  altitude?: number | null;
  accuracy?: number | null;
  heading?: number | null;
  headingRef?: 'true' | 'magnetic';
  pitch?: number | null;
  address?: string;
  note?: string;
  tag?: string;
  index?: number;
  dateText?: string;
  details?: DetailRow[];
};

export type PhotoRecord = {
  id: string;
  path: string;
  thumb: string;
  width: number;
  height: number;
  createdAt: number;
  galleryUri: string | null;
  meta: PhotoMeta;
};

export type StampSpec = {
  title: string;
  lines: string[];
  position: 'tl' | 'tr' | 'bl' | 'br';
  style: 'classic' | 'card';
  textSize: 'small' | 'medium' | 'large' | 'xlarge';
  color: string;
  font: string;
  background: boolean;
  compass: boolean;
  heading: number | null;
  watermark: string;
};

export type ExportResult = { path: string; name: string; savedTo: string | null };

type HeadingNative = {
  start(): void;
  stop(): void;
  isAvailable(): Promise<{ rotationVector: boolean; magnetometer: boolean; available: boolean }>;
};
type LocationNative = {
  start(): void;
  stop(): void;
  isEnabled(): Promise<{ gps: boolean; network: boolean; enabled: boolean }>;
  openLocationSettings(): void;
  refreshAddress(): void;
};
type CameraNative = {
  takePhoto(opts: {
    stamp: StampSpec | null;
    meta: PhotoMeta;
    quality: 'max' | 'high' | 'medium';
    jpegQuality: number;
    saveToGallery: boolean;
    shutterSound: boolean;
    mirrorFront: boolean;
  }): Promise<PhotoRecord>;
};
type StoreNative = {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<boolean>;
  nextIndex(): Promise<number>;
  peekIndex(): Promise<number>;
  resetIndex(): Promise<boolean>;
  getDeviceInfo(): Promise<{ tzShort: string; tzId: string; tzOffsetMin: number; sdk: number; model: string }>;
  openAppSettings(): void;
  list(): Promise<PhotoRecord[]>;
  remove(ids: string[], alsoGallery: boolean): Promise<boolean>;
  updateMeta(id: string, patch: Partial<PhotoMeta>): Promise<PhotoRecord>;
  shareFiles(paths: string[], mime: string, title: string): Promise<boolean>;
  openFile(path: string, mime: string): Promise<boolean>;
  exportPdf(ids: string[], options: { title: string; author: string; layout: 'single' | 'double'; saveToDownloads: boolean }): Promise<ExportResult>;
  exportCsv(ids: string[], saveToDownloads: boolean): Promise<ExportResult>;
  exportKml(ids: string[], saveToDownloads: boolean): Promise<ExportResult>;
};

export const GeoHeading = NativeModules.GeoHeading as HeadingNative;
export const GeoLocation = NativeModules.GeoLocation as LocationNative;
export const GeoCamera = NativeModules.GeoCamera as CameraNative;
export const GeoStore = NativeModules.GeoStore as StoreNative;

export type CameraViewProps = ViewProps & {
  lens: 'back' | 'front';
  flash: 'off' | 'on' | 'auto';
  torch: boolean;
  aspect: '4:3' | '16:9';
  zoom: number;
  active: boolean;
};

export const GeoCameraView = requireNativeComponent<CameraViewProps>('GeoCameraView');
