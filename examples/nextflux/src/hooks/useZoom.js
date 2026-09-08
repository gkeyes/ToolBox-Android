import { useEffect } from 'react';
import { useStore } from '@nanostores/react';
import { settingsState } from '@/stores/settingsStore';

export function useZoom() {
  const { interfaceFontSize } = useStore(settingsState);

  useEffect(() => {
    document.documentElement.style.setProperty('--interface-font-size', `${interfaceFontSize}px`);

  }, [interfaceFontSize]);
}
