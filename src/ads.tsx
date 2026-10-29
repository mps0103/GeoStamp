import React, { useState } from 'react';
import { View } from 'react-native';
import mobileAds, { AdEventType, AdsConsent, BannerAd, BannerAdSize, InterstitialAd, TestIds } from 'react-native-google-mobile-ads';

// ---- Replace with real AdMob unit IDs before Play Store release ----
// (App ID goes in app.json → "react-native-google-mobile-ads".android_app_id)
const PROD_BANNER_ID = 'ca-app-pub-2904788540387890/8958054730';
const PROD_INTERSTITIAL_ID = 'ca-app-pub-2904788540387890/5090961911';

const USE_TEST_ADS = __DEV__ || PROD_BANNER_ID.includes('XXXX');
export const BANNER_ID = USE_TEST_ADS ? TestIds.ADAPTIVE_BANNER : PROD_BANNER_ID;
const INTERSTITIAL_ID = USE_TEST_ADS ? TestIds.INTERSTITIAL : PROD_INTERSTITIAL_ID;

const INTERSTITIAL_MIN_GAP_MS = 90 * 1000; // never more than once per 90 s

let initialized = false;
let interstitial: InterstitialAd | null = null;
let interstitialLoaded = false;
let lastShown = 0;

/** Consent (UMP, for EEA/UK) + SDK init. Safe to call once at startup. */
export async function initAds() {
  if (initialized) return;
  initialized = true;
  try {
    await AdsConsent.gatherConsent();
  } catch {}
  try {
    const { canRequestAds } = await AdsConsent.getConsentInfo();
    if (!canRequestAds) return;
    await mobileAds().initialize();
    loadInterstitial();
  } catch {}
}

function loadInterstitial() {
  try {
    interstitialLoaded = false;
    interstitial = InterstitialAd.createForAdRequest(INTERSTITIAL_ID);
    interstitial.addAdEventListener(AdEventType.LOADED, () => {
      interstitialLoaded = true;
    });
    interstitial.addAdEventListener(AdEventType.ERROR, () => {
      interstitialLoaded = false;
      setTimeout(loadInterstitial, 60 * 1000);
    });
    interstitial.load();
  } catch {}
}

/**
 * Shows the interstitial (if ready and not shown recently) and resolves when it is
 * closed, so the caller can continue (e.g. open the share sheet) afterwards.
 */
export function showInterstitial(): Promise<void> {
  return new Promise(resolve => {
    const ad = interstitial;
    if (!ad || !interstitialLoaded || Date.now() - lastShown < INTERSTITIAL_MIN_GAP_MS) {
      resolve();
      return;
    }
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      offClose();
      offErr();
      loadInterstitial();
      resolve();
    };
    const offClose = ad.addAdEventListener(AdEventType.CLOSED, finish);
    const offErr = ad.addAdEventListener(AdEventType.ERROR, finish);
    lastShown = Date.now();
    interstitialLoaded = false;
    ad.show().catch(finish);
  });
}

/** Adaptive anchored banner; collapses to nothing if no ad fills. */
export function AdBanner() {
  const [failed, setFailed] = useState(false);
  if (failed) return null;
  return (
    <View style={{ alignItems: 'center', backgroundColor: '#0B1220' }}>
      <BannerAd unitId={BANNER_ID} size={BannerAdSize.ANCHORED_ADAPTIVE_BANNER} onAdFailedToLoad={() => setFailed(true)} />
    </View>
  );
}
