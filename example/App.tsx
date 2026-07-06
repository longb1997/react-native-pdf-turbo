/**
 * react-native-pdf-turbo — example app
 *
 * Demonstrates:
 *  - Remote PDF loading with automatic caching + expiration
 *  - Switching between multiple documents
 *  - Page / page-count / load / error callbacks
 *  - Built-in navigation controls vs. controlled `page` prop
 *  - Cache inspection & clearing via `PdfCacheService`
 */
import React, {useCallback, useMemo, useState} from 'react';
import {
  Alert,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import PdfTurboView, {
  PdfCacheService,
  type PdfSource,
  type PdfPageRect,
  type PdfScrollMode,
} from 'react-native-pdf-turbo';

type Sample = {
  label: string;
  source: PdfSource;
};

const SAMPLES: Sample[] = [
  {
    label: 'Intel SDM (large, ~5000 pages)',
    source: {
      uri: 'https://cdrdv2-public.intel.com/825743/325462-sdm-vol-1-2abcd-3abcd-4.pdf',
      cache: true,
      expiration: 86400, // 24h
    },
  },
];

export default function App(): React.JSX.Element {
  const [sampleIndex, setSampleIndex] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [scrollMode, setScrollMode] = useState<PdfScrollMode>('continuous');

  const sample = SAMPLES[sampleIndex];
  const source = sample.source;
  const isContinuous = scrollMode === 'continuous';

  const selectSample = useCallback((index: number) => {
    setSampleIndex(index);
    setCurrentPage(0);
    setTotalPages(0);
  }, []);

  // Continuous mode: native does not emit onPageChange while scrolling, so
  // derive the "current" page from the visible-pages layout stream (the page
  // whose rect crosses the top of the viewport, else the first visible one).
  const handlePagesLayout = useCallback((pages: PdfPageRect[]) => {
    if (!pages.length) {
      return;
    }
    const top =
      pages.find(p => p.y <= 1 && p.y + p.height > 1) ?? pages[0];
    setCurrentPage(top.page);
  }, []);

  const handleClearCache = useCallback(async () => {
    try {
      await PdfCacheService.clearCache(source);
      Alert.alert('Cache', 'Cleared cache for current document');
    } catch {
      Alert.alert('Cache', 'Failed to clear cache');
    }
  }, [source]);

  const handleCacheSize = useCallback(async () => {
    try {
      const bytes = await PdfCacheService.getCacheSize();
      Alert.alert('Cache size', `${(bytes / 1024 / 1024).toFixed(2)} MB`);
    } catch {
      Alert.alert('Cache', 'Failed to read cache size');
    }
  }, []);

  const pdf = useMemo(
    () => (
      <PdfTurboView
        // Remount only on source change (fresh download/state). scrollMode is
        // switched live natively — no remount, so toggling paged/continuous
        // does not re-download the document.
        key={source.uri}
        source={source}
        scrollMode={scrollMode}
        maximumZoom={5}
        enableAntialiasing
        // Built-in next/prev pill only makes sense in paged mode; in continuous
        // mode the scroll gesture is the navigation.
        showNavigationControls={!isContinuous}
        style={styles.pdf}
        onLoadComplete={(page, dimensions) =>
          console.log('onLoadComplete', page, dimensions)
        }
        onPageCount={count => {
          console.log('onPageCount', count);
          setTotalPages(count);
        }}
        onPageChange={page => {
          console.log('onPageChange', page);
          setCurrentPage(page);
        }}
        onPagesLayout={isContinuous ? handlePagesLayout : undefined}
        onError={error => {
          console.warn('onError', error.nativeEvent.message);
          Alert.alert('PDF error', error.nativeEvent.message);
        }}
      />
    ),
    [source, scrollMode, isContinuous, handlePagesLayout],
  );

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor="#fff" />

      <View style={styles.header}>
        <Text style={styles.title}>PDF Turbo</Text>
        <Text style={styles.subtitle}>
          Page {totalPages ? currentPage + 1 : 0} / {totalPages}
        </Text>
      </View>

      <View style={styles.tabs}>
        {SAMPLES.map((s, i) => (
          <TouchableOpacity
            key={s.label}
            style={[styles.tab, i === sampleIndex && styles.tabActive]}
            onPress={() => selectSample(i)}>
            <Text
              style={[
                styles.tabText,
                i === sampleIndex && styles.tabTextActive,
              ]}>
              {s.label}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {pdf}

      <View style={styles.footer}>
        <FooterButton
          label={isContinuous ? 'Paged mode' : 'Scroll mode'}
          onPress={() =>
            setScrollMode(m => (m === 'continuous' ? 'paged' : 'continuous'))
          }
        />
        <FooterButton label="Cache size" onPress={handleCacheSize} />
        <FooterButton label="Clear cache" onPress={handleClearCache} />
      </View>
    </SafeAreaView>
  );
}

function FooterButton({
  label,
  onPress,
}: {
  label: string;
  onPress: () => void;
}): React.JSX.Element {
  return (
    <TouchableOpacity style={styles.button} onPress={onPress}>
      <Text style={styles.buttonText}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#f5f5f5'},
  header: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  title: {fontSize: 20, fontWeight: '700', color: '#111'},
  subtitle: {fontSize: 13, color: '#666', marginTop: 2},
  tabs: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    paddingHorizontal: 8,
    paddingBottom: 8,
    gap: 8,
  },
  tab: {
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 16,
    backgroundColor: '#eee',
  },
  tabActive: {backgroundColor: '#2563eb'},
  tabText: {fontSize: 13, color: '#333'},
  tabTextActive: {color: '#fff', fontWeight: '600'},
  pdf: {flex: 1},
  footer: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#e0e0e0',
  },
  button: {
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: '#f0f0f0',
  },
  buttonText: {fontSize: 13, color: '#2563eb', fontWeight: '600'},
});
