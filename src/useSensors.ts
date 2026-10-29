import { useEffect, useRef, useState } from 'react';
import { DeviceEventEmitter } from 'react-native';
import { GeoHeading, GeoLocation, HeadingEvent, LocationEvent } from './native';

export function useSensors(active: boolean) {
  const [heading, setHeading] = useState<HeadingEvent | null>(null);
  const [location, setLocation] = useState<LocationEvent | null>(null);
  const latest = useRef<{ heading: HeadingEvent | null; location: LocationEvent | null }>({ heading: null, location: null });

  useEffect(() => {
    const h = DeviceEventEmitter.addListener('GeoHeading', (e: HeadingEvent) => {
      latest.current.heading = e;
      setHeading(e);
    });
    const l = DeviceEventEmitter.addListener('GeoLocation', (e: LocationEvent) => {
      // keep last known address if a new fix arrives before geocoding finishes
      const prev = latest.current.location;
      const merged = e.address ? e : { ...e, address: prev?.address };
      latest.current.location = merged;
      setLocation(merged);
    });
    return () => {
      h.remove();
      l.remove();
    };
  }, []);

  useEffect(() => {
    if (!active) return;
    GeoHeading.start();
    GeoLocation.start();
    return () => {
      GeoHeading.stop();
      GeoLocation.stop();
    };
  }, [active]);

  return { heading, location, latest };
}
