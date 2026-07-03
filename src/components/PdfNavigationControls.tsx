import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, TextInput, StyleSheet } from 'react-native';
import type { PdfNavigationControlsProps } from '../types';

/**
 * Navigation controls for PDF pages
 * Provides previous/next buttons and page number input
 */
export const PdfNavigationControls: React.FC<PdfNavigationControlsProps> = ({
  currentPage,
  totalPages,
  onNextPage,
  onPrevPage,
  onPageChange,
  style,
}) => {
  const [inputPage, setInputPage] = useState((currentPage + 1).toString());
  const [isEditing, setIsEditing] = useState(false);

  useEffect(() => {
    setInputPage((currentPage + 1).toString());
  }, [currentPage]);

  const handleChangePageInput = (text: string) => {
    setInputPage(text);
  };

  const commitPageInput = () => {
    setIsEditing(false);
    const pageNumber = parseInt(inputPage, 10);
    if (!isNaN(pageNumber) && pageNumber >= 1 && pageNumber <= totalPages) {
      onPageChange(pageNumber - 1);
    } else {
      // Reset to current page if invalid
      setInputPage((currentPage + 1).toString());
    }
  };

  const isFirstPage = currentPage === 0;
  const isLastPage = currentPage === totalPages - 1;
  const progress =
    totalPages > 1 ? currentPage / (totalPages - 1) : totalPages > 0 ? 1 : 0;

  return (
    <View style={[styles.wrapper, style]} pointerEvents="box-none">
      <View style={styles.pill}>
        <TouchableOpacity
          onPress={onPrevPage}
          disabled={isFirstPage}
          activeOpacity={0.6}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          style={[styles.navButton, isFirstPage && styles.disabledButton]}
          accessibilityLabel="Previous page"
          accessibilityRole="button"
        >
          <Text style={styles.chevron}>‹</Text>
        </TouchableOpacity>

        <View style={styles.center}>
          <View style={styles.pageRow}>
            <TextInput
              style={[styles.pageInput, isEditing && styles.pageInputActive]}
              value={inputPage}
              keyboardType="number-pad"
              returnKeyType="done"
              selectTextOnFocus
              onFocus={() => setIsEditing(true)}
              onChangeText={handleChangePageInput}
              onEndEditing={commitPageInput}
              onSubmitEditing={commitPageInput}
              accessibilityLabel={`Current page: ${currentPage + 1} of ${totalPages}`}
            />
            <Text style={styles.pageDivider}>/</Text>
            <Text style={styles.pageTotal}>{totalPages}</Text>
          </View>

          <View style={styles.track}>
            <View style={[styles.trackFill, { width: `${progress * 100}%` }]} />
          </View>
        </View>

        <TouchableOpacity
          onPress={onNextPage}
          disabled={isLastPage}
          activeOpacity={0.6}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          style={[styles.navButton, isLastPage && styles.disabledButton]}
          accessibilityLabel="Next page"
          accessibilityRole="button"
        >
          <Text style={styles.chevron}>›</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    position: 'absolute',
    bottom: 28,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(24, 24, 27, 0.92)',
    borderRadius: 22,
    paddingVertical: 8,
    paddingHorizontal: 8,
    // subtle border to lift off any background
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255, 255, 255, 0.12)',
    // elevation / shadow
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.3,
    shadowRadius: 12,
    elevation: 8,
  },
  navButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  disabledButton: {
    opacity: 0.35,
  },
  chevron: {
    color: '#fff',
    fontSize: 26,
    lineHeight: 28,
    fontWeight: '400',
    marginTop: -2,
  },
  center: {
    minWidth: 88,
    paddingHorizontal: 14,
    alignItems: 'center',
  },
  pageRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  pageInput: {
    minWidth: 30,
    height: 30,
    borderRadius: 8,
    paddingHorizontal: 6,
    textAlign: 'center',
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
    backgroundColor: 'rgba(255, 255, 255, 0.10)',
  },
  pageInputActive: {
    backgroundColor: 'rgba(96, 165, 250, 0.25)',
  },
  pageDivider: {
    color: 'rgba(255, 255, 255, 0.45)',
    fontSize: 15,
    marginHorizontal: 5,
  },
  pageTotal: {
    color: 'rgba(255, 255, 255, 0.7)',
    fontSize: 15,
    fontWeight: '600',
  },
  track: {
    marginTop: 7,
    height: 3,
    width: '100%',
    borderRadius: 2,
    backgroundColor: 'rgba(255, 255, 255, 0.15)',
    overflow: 'hidden',
  },
  trackFill: {
    height: '100%',
    borderRadius: 2,
    backgroundColor: '#60a5fa',
  },
});
