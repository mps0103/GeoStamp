import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Animated, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from './components';
import { C } from './theme';

/**
 * In-app replacement for Alert.alert: same call shape, but drawn with the app's own look.
 * 'primary' = accent button, 'default' = secondary, 'destructive' = red, 'cancel' = quiet text link (shown last).
 */
export type DialogButton = { text: string; style?: 'default' | 'primary' | 'cancel' | 'destructive'; onPress?: () => void };
type DialogRequest = { title: string; message?: string; buttons: DialogButton[]; cancelable: boolean; onDismiss?: () => void };

let showDialog: ((d: DialogRequest) => void) | null = null;

export function appAlert(
  title: string,
  message?: string,
  buttons?: DialogButton[],
  options?: { cancelable?: boolean; onDismiss?: () => void },
) {
  const list = buttons && buttons.length ? buttons : [{ text: 'OK', style: 'primary' as const }];
  if (!showDialog) {
    // Host not mounted yet: fall back to the system dialog so nothing is lost
    Alert.alert(title, message, list.map(b => ({ text: b.text, style: b.style === 'primary' ? 'default' : b.style, onPress: b.onPress })));
    return;
  }
  showDialog({ title, message, buttons: list, cancelable: options?.cancelable ?? true, onDismiss: options?.onDismiss });
}

/** Mount once near the root of the app. */
export function DialogHost() {
  const [cur, setCur] = useState<DialogRequest | null>(null);
  const curRef = useRef<DialogRequest | null>(null);
  const queue = useRef<DialogRequest[]>([]);
  const closing = useRef(false);
  const anim = useRef(new Animated.Value(0)).current;

  const open = useCallback(
    (req: DialogRequest) => {
      curRef.current = req;
      closing.current = false;
      setCur(req);
      anim.setValue(0);
      Animated.timing(anim, { toValue: 1, duration: 180, useNativeDriver: true }).start();
    },
    [anim],
  );

  useEffect(() => {
    showDialog = req => (curRef.current ? queue.current.push(req) : open(req));
    return () => {
      showDialog = null;
    };
  }, [open]);

  // Fade out, clear, show the next queued dialog, then run the chosen action
  const close = useCallback(
    (after?: () => void) => {
      if (closing.current) return;
      closing.current = true;
      Animated.timing(anim, { toValue: 0, duration: 120, useNativeDriver: true }).start(() => {
        curRef.current = null;
        setCur(null);
        const next = queue.current.shift();
        if (next) open(next);
        after?.();
      });
    },
    [anim, open],
  );

  const dismiss = () => {
    const req = curRef.current;
    if (req && req.cancelable) close(req.onDismiss);
  };

  if (!cur) return null;
  const main = cur.buttons.filter(b => b.style !== 'cancel');
  const cancel = cur.buttons.filter(b => b.style === 'cancel');

  return (
    <Modal transparent visible animationType="none" statusBarTranslucent onRequestClose={dismiss}>
      <Animated.View style={[styles.backdrop, { opacity: anim }]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={dismiss} />
        <Animated.View style={[styles.card, { opacity: anim, transform: [{ scale: anim.interpolate({ inputRange: [0, 1], outputRange: [0.94, 1] }) }] }]}>
          <Text style={styles.title}>{cur.title}</Text>
          {cur.message ? <Text style={styles.message}>{cur.message}</Text> : null}
          <View style={styles.buttons}>
            {main.map((b, i) => (
              <Button
                key={'m' + i}
                title={b.text}
                variant={b.style === 'primary' ? 'primary' : b.style === 'destructive' ? 'danger' : 'ghost'}
                onPress={() => close(b.onPress)}
              />
            ))}
            {cancel.map((b, i) => (
              <Pressable key={'c' + i} onPress={() => close(b.onPress)} style={styles.cancel} hitSlop={6}>
                <Text style={styles.cancelText}>{b.text}</Text>
              </Pressable>
            ))}
          </View>
        </Animated.View>
      </Animated.View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(3,7,18,0.72)', alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: {
    width: '100%',
    maxWidth: 380,
    backgroundColor: C.surface,
    borderRadius: 22,
    borderWidth: 1,
    borderColor: C.border,
    padding: 20,
    elevation: 12,
  },
  title: { color: C.text, fontSize: 18, fontWeight: '800' },
  message: { color: C.sub, fontSize: 14, lineHeight: 20, marginTop: 8 },
  buttons: { marginTop: 18, gap: 10 },
  cancel: { alignItems: 'center', paddingVertical: 10 },
  cancelText: { color: C.sub, fontSize: 15, fontWeight: '700' },
});
